package works.nuty.codon.client.ui;

import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.CodonClientMod;
import works.nuty.codon.client.state.BreakpointTargetPolicy;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.client.ui.layout.BreakpointListOrder;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import works.nuty.codon.client.ui.layout.VisibleWidgetCache;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.SourceLocation;

import static works.nuty.codon.client.ui.DebuggerTheme.*;

/**
 * Docked list of the authoritative saved breakpoints, shown in cursor mode beside Watches.
 * Activating a row only navigates; it never sends an edit.
 */
final class BreakpointPanel {
    private static final Bounds EMPTY = new Bounds(0, 0, 0, 0);
    private static final int HEADER = 22;
    private static final int ROW_HEIGHT = 30;
    private static final int PADDING = 4;
    /** Header plus one row, so a docked panel is never reduced to a title bar. */
    static final int MINIMUM_HEIGHT = HEADER + ROW_HEIGHT + PADDING;

    private final ClientDebuggerState state;
    private final VisibleWidgetCache<String, RowButton> buttons = new VisibleWidgetCache<>();
    private List<BreakpointDefinition> displayed = List.of();
    private Bounds bounds = EMPTY;
    private Bounds scrollBounds = EMPTY;
    private int offset;
    private int maximum;

    private final Supplier<@Nullable Screen> host;

    BreakpointPanel(ClientDebuggerState state) { this(state, () -> Minecraft.getInstance().gui.screen()); }

    /** The host is the screen that shows this panel; navigation needs it to reveal Flow or open Source. */
    BreakpointPanel(ClientDebuggerState state, Supplier<@Nullable Screen> host) {
        this.state = state;
        this.host = host;
    }

    Bounds bounds() { return bounds; }
    void clearBounds() { bounds = scrollBounds = EMPTY; }

    /** Height that shows every saved breakpoint, for the caller to clamp to its space. */
    int preferredHeight() {
        int count = state.breakpoints().definitions().size();
        return HEADER + Math.max(1, count) * ROW_HEIGHT + PADDING;
    }

    String countHeader() {
        long enabled = displayed.stream().filter(BreakpointDefinition::enabled).count();
        return tr("codon.breakpoint.list_header", displayed.size(), enabled);
    }

    List<DebuggerButton> render(GuiGraphicsExtractor graphics, Bounds area, DebuggerNavigation navigation,
                                ScrollbarInput scrollbars) {
        clearBounds();
        if (area.width() < 72 || area.height() < HEADER + PADDING) return List.of();
        var font = Minecraft.getInstance().font;
        displayed = state.breakpoints().definitions().stream()
            .sorted(Comparator.comparing(BreakpointDefinition::target, BreakpointListOrder.TARGETS)).toList();
        int capacity = Math.max(1, (area.height() - HEADER - PADDING) / ROW_HEIGHT);
        maximum = Math.max(0, displayed.size() - capacity);
        // Every row is a keyboard target; revealing one scrolls it into the viewport.
        for (int row = 0; row < displayed.size(); row++) {
            int logical = row;
            navigation.add(rowId(displayed.get(row)), DebuggerNavigation.Group.BREAKPOINT, row, 0,
                () -> offset = Math.clamp(revealRow(logical, offset, capacity), 0, maximum));
        }
        navigation.revealFocus(DebuggerNavigation.Group.BREAKPOINT);
        offset = Math.clamp(offset, 0, maximum);
        int shown = Math.min(capacity, displayed.size() - offset);
        int height = Math.min(area.height(), HEADER + Math.max(1, shown) * ROW_HEIGHT + PADDING);
        bounds = new Bounds(area.x(), area.y(), area.width(), height);
        scrollBounds = new Bounds(area.x() + 3, area.y() + HEADER, area.width() - 6, height - HEADER - PADDING);
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + bounds.width(), bounds.y() + height, DebuggerTheme.color(PANEL));
        graphics.outline(bounds.x(), bounds.y(), bounds.width(), height, DebuggerTheme.color(BORDER));
        WatchUi.line(graphics, font, countHeader(), bounds.x() + 7, bounds.y() + (HEADER - font.lineHeight) / 2 + 1,
            bounds.width() - 14, TEXT);
        if (displayed.isEmpty())
            WatchUi.line(graphics, font, tr("codon.breakpoint.list_empty"), bounds.x() + 7, bounds.y() + HEADER + 8,
                bounds.width() - 14, MUTED);

        buttons.begin();
        var controls = new java.util.ArrayList<DebuggerButton>();
        for (int row = offset; row < offset + shown; row++) {
            BreakpointDefinition definition = displayed.get(row);
            BreakpointTarget target = definition.target();
            String id = rowId(definition);
            RowButton button = buttons.get(id, RowButton::new);
            button.configure(bounds.x() + 4, bounds.y() + HEADER + (row - offset) * ROW_HEIGHT, bounds.width() - 8,
                ROW_HEIGHT - 2, Component.literal(label(definition)), canNavigate(target), false, true, false,
                () -> navigate(target));
            button.asHitSurface().withTextIcon(BreakpointUi.icon(definition));
            describe(button, definition);
            // The row already draws its text; a tooltip adds only clipped text or why it cannot navigate.
            StringBuilder tip = new StringBuilder();
            if (button.clipped()) tip.append(label(definition));
            if (!canNavigate(target)) tip.append(tip.isEmpty() ? "" : "\n").append(tr("codon.breakpoint.flow_unavailable"));
            button.setTooltip(tip.isEmpty() ? null : Tooltip.create(Component.literal(tip.toString())));
            navigation.bind(id, DebuggerNavigation.Group.BREAKPOINT, button);
            controls.add(button);
        }
        buttons.end();
        if (maximum > 0 && scrollBounds.height() > 0) {
            int h = scrollBounds.height();
            int thumb = Math.min(h, Math.max(8, h * capacity / displayed.size()));
            int x = bounds.x() + bounds.width() - 4;
            graphics.fill(x, scrollBounds.y(), x + 2, scrollBounds.y() + h, DebuggerTheme.color(BORDER));
            int y = scrollBounds.y() + (h - thumb) * offset / maximum;
            graphics.fill(x, y, x + 2, y + thumb, DebuggerTheme.color(SCROLLBAR));
            scrollbars.add("breakpoints", false, x, scrollBounds.y(), h, 2, thumb, offset, maximum, value -> offset = value);
        }
        return controls;
    }

    private static String rowId(BreakpointDefinition definition) { return "breakpoint-row:" + definition.target(); }

    boolean scroll(double x, double y, double amount) {
        if (!scrollBounds.contains(x, y) || amount == 0) return false;
        offset = Math.clamp(offset + (amount > 0 ? -1 : 1), 0, maximum);
        return true;
    }

    private static int revealRow(int row, int offset, int capacity) {
        if (row < offset) return row;
        return row >= offset + capacity ? row - capacity + 1 : offset;
    }

    private static String label(BreakpointDefinition definition) {
        return tr(definition.enabled() ? "codon.breakpoint.enabled" : "codon.breakpoint.disabled") + " · "
            + (definition.staleSource() ? "! " + tr("codon.breakpoint.location_review") + " · " : "")
            + BreakpointUi.target(definition.target()) + " · " + BreakpointUi.condition(definition.condition());
    }

    private static void describe(RowButton button, BreakpointDefinition definition) {
        BreakpointTarget target = definition.target();
        String status = tr(definition.enabled() ? "codon.breakpoint.enabled" : "codon.breakpoint.disabled");
        String kind = tr(target.wholeCommand() ? "codon.breakpoint.whole_target" : "codon.breakpoint.stage_target",
            target.stageIndex() + 1);
        String detail = BreakpointUi.condition(definition.condition());
        if (definition.staleSource()) detail += " · ! " + tr("codon.breakpoint.location_review");
        switch (target.location()) {
            case SourceLocation.Block block -> {
                button.headline = status + " · " + kind + " · " + block.block().x() + "," + block.block().y()
                    + "," + block.block().z();
                button.detail = detail + " · " + block.block().dimension();
            }
            case SourceLocation.Function function -> {
                button.headline = status + " · " + tr("codon.breakpoint.line_target", function.location().line())
                    + (target.wholeCommand() ? "" : " · " + kind) + " · " + function.location().function();
                button.detail = detail;
            }
            case SourceLocation.Player ignored -> throw new IllegalStateException("Player breakpoint cannot be saved");
        }
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
        return host.get() instanceof CodonScreen codon ? codon : null;
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
            if (BreakpointTargetPolicy.stageCount(command, state.stagePreviews().get(flow.location()), flow) == 1) {
                if (target.stageIndex() == 0) return new FlowTarget(index, 0, false);
                continue;
            }
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
        return target.location() instanceof SourceLocation.Function
            ? CodonClientMod.sources() != null && debuggerScreen() != null : flowTarget(target) != null;
    }

    private void navigate(BreakpointTarget target) {
        if (state.breakpoints().get(target) == null) return;
        CodonScreen screen = debuggerScreen();
        if (screen == null) return;
        if (target.location() instanceof SourceLocation.Function function) {
            var sources = CodonClientMod.sources();
            if (sources == null) return;
            sources.selectAt(function.location());
            Minecraft.getInstance().gui.setScreen(new FunctionSourceScreen(screen, sources).revealBreakpoint(target));
            return;
        }
        FlowTarget destination = flowTarget(target);
        if (destination == null) return;
        state.selectExecutionFlow(destination.flow());
        if (destination.unobserved()) state.selectUnobservedExecutionFlowStage(destination.stage());
        else state.selectExecutionFlowStage(destination.stage());
        var flow = state.selectedExecutionFlow();
        var marker = !target.wholeCommand() && target.stageIndex() == 0
            && BreakpointTargetPolicy.stageCount(flow.stages().getFirst().command().text(), state.stagePreviews().get(flow.location()), flow) == 1
            ? BreakpointTarget.whole(target.location()) : target;
        screen.revealSelectedFlow(CommandPanel.breakpointFocusId(flow, marker));
    }

    private static String tr(String key, Object... args) { return Component.translatable(key, args).getString(); }
}
