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
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.client.state.BreakpointTargetPolicy;
import works.nuty.codon.client.ui.layout.VisibleWidgetCache;
import works.nuty.codon.client.ui.layout.BreakpointListOrder;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.SourceLocation;

import java.util.Comparator;
import java.util.List;

import static works.nuty.codon.client.ui.DebuggerTheme.*;

/** Authoritative breakpoint locations; activating a row only navigates. */
public final class BreakpointListScreen extends ScaledCodonScreen {
    private static final int ROW_HEIGHT = 30;
    private final Screen parent;
    private final ClientDebuggerState state;
    private final @Nullable List<BreakpointTarget> targets;
    private final VisibleWidgetCache<String, DebuggerButton> buttons = new VisibleWidgetCache<>();
    private List<BreakpointDefinition> displayed = List.of();
    private int left, top, panelWidth, panelHeight, offset, rows;

    public BreakpointListScreen(Screen parent, ClientDebuggerState state) { this(parent, state, null); }

    public BreakpointListScreen(Screen parent, ClientDebuggerState state, @Nullable List<BreakpointTarget> targets) {
        super(Component.translatable("codon.breakpoint.list_title"), state.preferences());
        this.parent = parent;
        this.state = state;
        this.targets = targets == null ? null : List.copyOf(targets);
    }

    Screen parentScreen() { return parent; }

    @Override protected void init() {
        panelWidth = Math.max(1, Math.min(480, width - 12));
        panelHeight = Math.max(1, Math.min(330, height - 12));
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        rows = Math.max(1, (panelHeight - 62) / ROW_HEIGHT);
        rebuild();
    }

    private void rebuild() {
        var focused = getFocused();
        clearWidgets();
        buttons.begin();
        displayed = state.breakpoints().definitions().stream()
            .filter(definition -> targets == null || targets.contains(definition.target()))
            .sorted(Comparator.comparing(BreakpointDefinition::target, BreakpointListOrder.TARGETS)).toList();
        offset = Math.clamp(offset, 0, Math.max(0, displayed.size() - rows));
        for (int row = 0; row < rows && offset + row < displayed.size(); row++) {
            BreakpointDefinition definition = displayed.get(offset + row);
            BreakpointTarget target = definition.target();
            String label = tr(definition.enabled() ? "codon.breakpoint.enabled" : "codon.breakpoint.disabled") + " · "
                + (definition.staleSource() ? "! " + tr("codon.breakpoint.location_review") + " · " : "")
                + BreakpointUi.target(target) + " · " + BreakpointUi.condition(definition.condition());
            RowButton button = (RowButton) addRenderableWidget(buttons.get("row:" + target, RowButton::new));
            button.configure(left + 8, top + 30 + row * ROW_HEIGHT, panelWidth - 16, ROW_HEIGHT - 2, Component.literal(label),
                canNavigate(target), false, true, false, () -> navigate(target));
            button.asHitSurface().withTextIcon(BreakpointUi.icon(definition));
            String status = tr(definition.enabled() ? "codon.breakpoint.enabled" : "codon.breakpoint.disabled");
            String kind = tr(target.wholeCommand() ? "codon.breakpoint.whole_target" : "codon.breakpoint.stage_target",
                target.stageIndex() + 1);
            String detail = BreakpointUi.condition(definition.condition());
            if (definition.staleSource()) detail += " · ! " + tr("codon.breakpoint.location_review");
            switch (target.location()) {
                case SourceLocation.Block block -> {
                    button.headline = status + " · " + kind + " · " + block.block().x() + "," + block.block().y() + "," + block.block().z();
                    button.detail = detail + " · " + block.block().dimension();
                }
                case SourceLocation.Function function -> {
                    button.headline = status + " · " + tr("codon.breakpoint.line_target", function.location().line())
                        + (target.wholeCommand() ? "" : " · " + kind) + " · " + function.location().function();
                    button.detail = detail;
                }
                case SourceLocation.Player ignored -> throw new IllegalStateException("Player breakpoint cannot be saved");
            }
            // The row already draws its text; a tooltip adds only clipped text or why it cannot navigate.
            StringBuilder tip = new StringBuilder();
            if (button.clipped()) tip.append(label);
            if (!canNavigate(target)) tip.append(tip.isEmpty() ? "" : "\n").append(tr("codon.breakpoint.flow_unavailable"));
            button.setTooltip(tip.isEmpty() ? null : Tooltip.create(Component.literal(tip.toString())));
            button.setTabOrderGroup(row);
        }
        DebuggerButton close = addRenderableWidget(buttons.get("close", DebuggerButton::new));
        close.configure(left + 8, top + panelHeight - 27, Math.max(1, panelWidth - 16), 20,
            Component.translatable("codon.breakpoint.close"), true, false, false, false, this::onClose);
        if (focused instanceof AbstractWidget widget && children().contains(widget)) setFocused(widget);
        else if (focused != null) setFocused(close);
        buttons.end();
    }

    private static final class RowButton extends DebuggerButton {
        private String headline = "", detail = "";

        private int textX() { return getX() + TEXT_ICON_INSET + 5; }
        private int textWidth() { return Math.max(0, getRight() - 5 - textX()); }

        boolean clipped() {
            var font = Minecraft.getInstance().font;
            return WatchUi.clipped(font, headline, textWidth()) || WatchUi.clipped(font, detail, textWidth());
        }

        @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            if (active && (isHovered() || isFocused()))
                graphics.fill(getX(), getY(), getRight(), getBottom(), DebuggerTheme.color(RAISED));
            var font = Minecraft.getInstance().font;
            int textX = textX();
            int textWidth = textWidth();
            if (icon() != null) icon().draw(graphics, getX() + 4, getY() + 3,
                DebuggerTheme.foreground(active ? TEXT : MUTED));
            WatchUi.line(graphics, font, headline, textX, getY() + 3, textWidth, active ? TEXT : MUTED);
            WatchUi.line(graphics, font, detail, textX, getY() + 16, textWidth, MUTED);
            super.extractContents(graphics, mouseX, mouseY, partialTick);
        }
    }

    private @Nullable CodonScreen debuggerScreen() {
        Screen screen = parent;
        while (true) {
            if (screen instanceof CodonScreen codon) return codon;
            if (screen instanceof FunctionSourceScreen source) screen = source.parentScreen();
            else if (screen instanceof BreakpointListScreen list) screen = list.parentScreen();
            else return null;
        }
    }

    private record FlowTarget(int flow, int stage, boolean unobserved) { }

    private @Nullable FlowTarget flowTarget(BreakpointTarget target) {
        var snapshot = state.snapshot();
        if (snapshot == null || debuggerScreen() == null) return null;
        for (int index = snapshot.executionFlows().size() - 1; index >= 0; index--) {
            var flow = snapshot.executionFlows().get(index);
            if (!flow.location().equals(target.location()) || flow.stages().isEmpty()) continue;
            if (target.wholeCommand()) return new FlowTarget(index, 0, false);
            String command = flow.stages().getFirst().command().text();
            if (!target.commandFingerprint().equals(BreakpointTarget.fingerprint(command))) continue;
            if (BreakpointTargetPolicy.stageCount(command, state.stagePreviews().get(flow.location()), flow) == 1) continue;
            for (int stage = 0; stage < flow.stages().size(); stage++)
                if (flow.stages().get(stage).index() == target.stageIndex()) return new FlowTarget(index, stage, false);
            var preview = state.stagePreviews().get(flow.location());
            if (preview != null && preview.status() == ClientStagePreviewState.Status.READY
                && preview.savedCommand().equals(command)
                && preview.spans().stream().anyMatch(span -> span.index() == target.stageIndex()))
                return new FlowTarget(index, target.stageIndex(), true);
        }
        return null;
    }

    private boolean canNavigate(BreakpointTarget target) {
        return target.location() instanceof SourceLocation.Function ? CodonClientMod.sources() != null : flowTarget(target) != null;
    }

    private void navigate(BreakpointTarget target) {
        if (state.breakpoints().get(target) == null) return;
        if (target.location() instanceof SourceLocation.Function function) {
            var sources = CodonClientMod.sources();
            if (sources == null) return;
            sources.selectAt(function.location());
            Minecraft.getInstance().gui.setScreen(new FunctionSourceScreen(this, sources).revealBreakpoint(target));
            return;
        }
        FlowTarget destination = flowTarget(target);
        CodonScreen screen = debuggerScreen();
        if (destination == null || screen == null) return;
        state.selectExecutionFlow(destination.flow());
        if (destination.unobserved()) state.selectUnobservedExecutionFlowStage(destination.stage());
        else state.selectExecutionFlowStage(destination.stage());
        var flow = state.selectedExecutionFlow();
        screen.revealSelectedFlow(CommandPanel.breakpointFocusId(flow, target));
        Minecraft.getInstance().gui.setScreen(screen);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Reuse button identities while reflecting newly available navigation destinations.
        rebuild();
        graphics.fill(0, 0, width, height, DebuggerTheme.modalColor(0x70000000));
        graphics.fill(left, top, left + panelWidth, top + panelHeight, DebuggerTheme.modalColor(PANEL));
        graphics.outline(left, top, panelWidth, panelHeight, DebuggerTheme.modalColor(BORDER));
        WatchUi.line(graphics, font, tr(targets == null ? "codon.breakpoint.list_header" : "codon.breakpoint.saved_definitions_header", displayed.size()),
            left + 8, top + 10, panelWidth - 16, TEXT);
        if (displayed.isEmpty()) WatchUi.line(graphics, font, tr("codon.breakpoint.list_empty"), left + 12, top + 43,
            panelWidth - 24, MUTED);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (x < left || x >= left + panelWidth || y < top + 30 || y >= top + 30 + rows * ROW_HEIGHT
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
