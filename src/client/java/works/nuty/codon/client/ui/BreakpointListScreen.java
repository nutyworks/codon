package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.CodonClientMod;
import works.nuty.codon.client.network.ClientNetworking;
import works.nuty.codon.client.state.ClientBreakpointState;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.SourceLocation;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

import static works.nuty.codon.client.ui.DebuggerTheme.*;

/** Compact authoritative breakpoint list, reachable before the first pause. */
public final class BreakpointListScreen extends ScaledCodonScreen {
    private final Screen parent;
    private final ClientDebuggerState state;
    private final @Nullable List<BreakpointTarget> targets;
    private final Map<String, DebuggerButton> buttons = new HashMap<>();
    private List<BreakpointDefinition> displayed = List.of();
    private @Nullable BreakpointTarget selected;
    private @Nullable BreakpointDefinition deleted;
    private long deletedAt;
    private int left, top, panelWidth, panelHeight, offset, rows;
    private @Nullable DebuggerButton undoButton;

    public BreakpointListScreen(Screen parent, ClientDebuggerState state) {
        this(parent, state, null);
    }

    /** A line with coexisting legacy conditions exposes every saved definition, including disabled ones. */
    public BreakpointListScreen(Screen parent, ClientDebuggerState state, @Nullable List<BreakpointTarget> targets) {
        super(Component.translatable("codon.breakpoint.list_title"), state.preferences());
        this.parent = parent;
        this.state = state;
        this.targets = targets == null ? null : List.copyOf(targets);
    }

    @Override protected void init() {
        panelWidth = Math.max(1, Math.min(480, width - 12));
        panelHeight = Math.max(1, Math.min(330, height - 12));
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        rows = Math.max(1, (panelHeight - 92) / 20);
        rebuild();
    }

    private void rebuild() {
        var focused = getFocused();
        clearWidgets();
        displayed = state.breakpoints().definitions().stream()
            .filter(definition -> targets == null || targets.contains(definition.target()))
            .sorted(Comparator.comparing(definition -> BreakpointUi.target(definition.target()))).toList();
        offset = Math.clamp(offset, 0, Math.max(0, displayed.size() - rows));
        if (selected != null && displayed.stream().noneMatch(definition -> definition.target().equals(selected))) selected = null;
        int listTop = top + 30;
        for (int row = 0; row < rows && offset + row < displayed.size(); row++) {
            BreakpointDefinition definition = displayed.get(offset + row);
            BreakpointTarget target = definition.target();
            int y = listTop + row * 20;
            String label = tr(definition.enabled() ? "codon.breakpoint.enabled" : "codon.breakpoint.disabled") + " · "
                + (definition.staleSource() ? "! " + tr("codon.breakpoint.location_review") + " · " : "")
                + BreakpointUi.target(target)
                + " · " + BreakpointUi.condition(definition.condition());
            boolean function = target.location() instanceof SourceLocation.Function;
            int actionWidth = function ? 24 : 0;
            DebuggerButton button = addRenderableWidget(buttons.computeIfAbsent("row:" + target, ignored -> new DebuggerButton()));
            button.configure(left + 8, y, panelWidth - 16 - actionWidth, 18, Component.literal(label),
                true, target.equals(selected), true, false, () -> {
                    selected = target;
                    if (function && targets == null) source();
                    else rebuild();
                });
            button.withTextIcon(BreakpointUi.icon(definition));
            button.setTabOrderGroup(row);
            if (function) {
                DebuggerButton actions = addRenderableWidget(WatchUi.button(left + panelWidth - 8 - actionWidth,
                    y, actionWidth, 18, Component.literal("…"), () -> { selected = target; rebuild(); }));
                actions.setTooltip(Tooltip.create(Component.translatable("codon.breakpoint.actions")));
                actions.setTabOrderGroup(row);
            }
        }
        int actionsY = top + panelHeight - 53;
        int unit = Math.max(42, (panelWidth - 20) / 5);
        BreakpointDefinition current = current();
        button(0, actionsY, unit - 3, tr(current != null && current.enabled() ? "codon.breakpoint.disable_short" : "codon.breakpoint.enable_short"), this::toggle,
            current != null && !current.staleSource());
        button(unit, actionsY, unit - 3, tr("codon.breakpoint.condition_action"), this::condition, selected != null);
        button(unit * 2, actionsY, unit - 3, tr("codon.breakpoint.source"), this::source,
            selected != null && selected.location() instanceof SourceLocation.Function);
        button(unit * 3, actionsY, unit - 3, tr("codon.breakpoint.delete"), this::delete, selected != null);
        undoButton = button(unit * 4, actionsY, unit - 3, tr("codon.breakpoint.undo"), this::undo, canUndo());
        DebuggerButton close = button(0, top + panelHeight - 27, Math.max(56, panelWidth - 16), tr("codon.breakpoint.close"), this::onClose, true);
        if (focused instanceof AbstractWidget widget && children().contains(widget) && widget.active) setFocused(widget);
        else if (focused != null) setFocused(close);
    }

    private DebuggerButton button(int x, int y, int width, String label, Runnable action, boolean active) {
        String key = "action:" + x + (y == top + panelHeight - 27 ? ":close" : "");
        DebuggerButton button = addRenderableWidget(buttons.computeIfAbsent(key, ignored -> new DebuggerButton()));
        button.configure(left + 8 + x, y, width, 20, Component.literal(label), active, false, false, false, action);
        button.setTabOrderGroup(100 + x);
        return button;
    }

    private @Nullable BreakpointDefinition current() {
        return selected == null ? null : state.breakpoints().get(selected);
    }

    private void toggle() {
        BreakpointDefinition definition = current();
        if (definition == null || definition.staleSource()) return;
        ClientNetworking.sendBreakpointEdit(state, ClientBreakpointState.Action.TOGGLE, definition);
    }

    private void condition() {
        BreakpointDefinition definition = current();
        if (definition == null) return;
        int unit = Math.max(42, (panelWidth - 20) / 5);
        BreakpointConditionScreen.Anchor anchor = new BreakpointConditionScreen.Anchor(
            left + 8 + unit, top + panelHeight - 53, unit - 3, 20);
        ScreenLayers.open(this, new BreakpointConditionScreen(this, state, definition, anchor));
    }

    private void source() {
        if (!(selected != null && selected.location() instanceof SourceLocation.Function function)) return;
        var sources = CodonClientMod.sources();
        if (sources == null) return;
        sources.selectAt(function.location());
        Minecraft.getInstance().gui.setScreen(new FunctionSourceScreen(this, sources));
    }

    private void delete() {
        BreakpointDefinition definition = current();
        if (definition == null) return;
        if (ClientNetworking.sendBreakpointEdit(state, ClientBreakpointState.Action.DELETE, definition))
            recordDeleted(definition);
    }

    /** Make the short Undo action available after a deletion from this list or the condition editor. */
    public void recordDeleted(BreakpointDefinition definition) {
        deleted = definition;
        deletedAt = System.nanoTime();
        if (panelWidth > 0) rebuild();
    }

    private boolean canUndo() {
        return deleted != null && System.nanoTime() - deletedAt < 8_000_000_000L
            && state.breakpoints().error(deleted.target()) == null;
    }

    private void undo() {
        if (!canUndo()) return;
        if (ClientNetworking.sendBreakpointEdit(state, ClientBreakpointState.Action.SAVE, deleted)) deleted = null;
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        List<BreakpointDefinition> latest = state.breakpoints().definitions().stream()
            .filter(definition -> targets == null || targets.contains(definition.target()))
            .sorted(Comparator.comparing(definition -> BreakpointUi.target(definition.target()))).toList();
        if (!latest.equals(displayed)) rebuild();
        if (undoButton != null) undoButton.active = canUndo() && deleted != null
            && !state.breakpoints().pending(deleted.target());
        graphics.fill(0, 0, width, height, DebuggerTheme.color(0x70000000));
        graphics.fill(left, top, left + panelWidth, top + panelHeight, DebuggerTheme.color(PANEL));
        graphics.outline(left, top, panelWidth, panelHeight, DebuggerTheme.color(BORDER));
        WatchUi.line(graphics, font, tr(targets == null ? "codon.breakpoint.list_header" : "codon.breakpoint.saved_definitions_header", displayed.size()),
            left + 8, top + 10, panelWidth - 16, TEXT);
        if (displayed.isEmpty()) WatchUi.line(graphics, font, tr("codon.breakpoint.list_empty"), left + 12, top + 43,
            panelWidth - 24, MUTED);
        if (canUndo()) WatchUi.line(graphics, font, state.breakpoints().pending(deleted.target())
                ? tr("codon.breakpoint.deleting") : tr("codon.breakpoint.deleted_undo"),
            left + 8, top + panelHeight - 67, panelWidth - 16, MUTED);
        else if (selected != null) {
            var error = state.breakpoints().error(selected);
            String status = error != null ? tr("codon.breakpoint.error."
                + error.name().toLowerCase(java.util.Locale.ROOT)) :
                state.breakpoints().pending(selected) ? tr("codon.breakpoint.applying") : "";
            WatchUi.line(graphics, font, status, left + 8, top + panelHeight - 67, panelWidth - 16,
                error == null ? MUTED : AMBER);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (x < left || x >= left + panelWidth || y < top + 30 || y >= top + 30 + rows * 20
            || scrollY == 0) return super.mouseScrolled(x, y, scrollX, scrollY);
        offset = Math.clamp(offset - (int) Math.signum(scrollY) * 2, 0, Math.max(0, displayed.size() - rows));
        rebuild();
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(event);
    }

    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) { }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }

    private static String tr(String key, Object... args) { return Component.translatable(key, args).getString(); }
}
