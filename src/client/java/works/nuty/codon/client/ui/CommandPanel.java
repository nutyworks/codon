package works.nuty.codon.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.network.ClientNetworking;
import works.nuty.codon.client.state.ClientBreakpointState;
import works.nuty.codon.client.state.BreakpointTargetPolicy;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientFlowPreviewRequests;
import works.nuty.codon.client.ui.layout.CommandFlowLayout;
import works.nuty.codon.client.ui.layout.CommandFlowLayout.Part;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import works.nuty.codon.core.model.CallFrame;
import works.nuty.codon.core.model.BreakpointCondition;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.ExecutionFlowWarning;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.service.BreakpointConditionEvaluator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static works.nuty.codon.client.ui.DebuggerTheme.*;
import static works.nuty.codon.client.ui.layout.CommandFlowLayout.CELL_HORIZONTAL_PADDING;

/** A single command surface: its call path, recorded clauses, and the authoritative stop. */
public final class CommandPanel {
    private static final Bounds EMPTY = new Bounds(0, 0, 0, 0);
    private static final int DETAIL_HEIGHT = 26;
    private record SelectionDetail(String title, String values, String explanation, int color) { }
    private final Minecraft client = Minecraft.getInstance();
    private final ClientDebuggerState state;
    private final Runnable selectionChanged;
    private final Map<String, DebuggerButton> cache = new HashMap<>();
    private final Set<String> used = new HashSet<>();
    private final List<DebuggerButton> buttons = new ArrayList<>();
    private boolean expanded;
    private int commandOffset;
    private int stackOffset;
    private int maxCommandOffset;
    private int maxStackOffset;
    private Bounds commandBounds = EMPTY;
    private Bounds stackBounds = EMPTY;
    private final Map<DebuggerButton, Runnable> contextMenus = new HashMap<>();
    private @Nullable Selection lastSelection;
    private @Nullable StackSelection lastStackSelection;
    private @Nullable PauseSnapshot renderedSnapshot;
    private final ClientFlowPreviewRequests previewRequests = new ClientFlowPreviewRequests();
    private DebuggerNavigation navigation;
    private ScrollbarInput scrollbars;
    private DebuggerNavigation.Group navigationGroup = DebuggerNavigation.Group.ACTIONS;

    public CommandPanel(ClientDebuggerState state, Runnable selectionChanged) {
        this.state = state;
        this.selectionChanged = selectionChanged;
    }

    public int preferredHeight(int width, int height, @Nullable PauseSnapshot snapshot) {
        if (snapshot == null) return 36;
        // Selection and recording availability must not move the panel's controls.
        int base = (height < 240 ? 64 : 76) + DETAIL_HEIGHT + 2;
        return expanded ? Math.max(120, height / 2) : base;
    }

    public List<DebuggerButton> render(GuiGraphicsExtractor graphics, Bounds area,
                                       @Nullable PauseSnapshot snapshot, InputManager input, DebuggerOverlay overlay,
                                       DebuggerNavigation navigation) {
        this.navigation = navigation;
        this.scrollbars = overlay.scrollbars();
        used.clear();
        buttons.clear();
        renderedSnapshot = snapshot;
        commandBounds = stackBounds = EMPTY;
        contextMenus.clear();
        if (area.width() < 20 || area.height() < 18) return finish();
        graphics.fill(area.x(), area.y(), area.x() + area.width(), area.y() + area.height(), DebuggerTheme.color(PANEL));
        graphics.outline(area.x(), area.y(), area.width(), area.height(), DebuggerTheme.color(BORDER));
        if (snapshot == null) {
            drawText(graphics, tr("codon.ui.no_snapshot"), area.x() + 7, area.y() + 7, area.width() - 14, MUTED);
            lastSelection = null;
            return finish();
        }

        ExecutionFlowTrace flow = state.selectedExecutionFlow();
        CommandSnippet snippet = state.selectedCommand();
        if (flow != null && snippet != null && !(flow.location() instanceof SourceLocation.Player)
            && previewRequests.needsRequest(snapshot, flow.location(), state.stagePreviews().get(flow.location()), snippet.text())
            && ClientNetworking.requestStagePreview(state, flow.location())) previewRequests.requested(flow.location());

        // The action row is anchored to the screen's bottom, independently of expansion.
        int actionY = Math.max(area.y() + 1, area.y() + area.height() - 20);
        int actionLeft = renderActions(new Bounds(area.x() + 4, actionY, area.width() - 8, 17));
        if (area.height() >= 40) {
            int y = area.y() + 3;
            renderPath(graphics, new Bounds(area.x() + 4, y, area.width() - 8, 17), snapshot);
            y += 19;
            // Fixed full-width detail band: selected state never changes clause widths or action positions.
            int detailHeight = Math.min(DETAIL_HEIGHT, Math.max(0, actionY - y - 21));
            int detailY = actionY - 4 - detailHeight;
            Bounds body = new Bounds(area.x() + 5, y, area.width() - 12, Math.max(0, detailY - 2 - y));
            renderClauses(graphics, body, snapshot);
            SelectionDetail detail = selectionDetail();
            if (detailHeight >= client.font.lineHeight + 2) {
                drawText(graphics, detail.title(), area.x() + 8, detailY + 2, area.width() - 16, detail.color());
                if (detailHeight >= 2 * client.font.lineHeight + 5)
                    drawText(graphics, detail.values(), area.x() + 8, detailY + 13, area.width() - 16, TEXT);
                navigationGroup = DebuggerNavigation.Group.ACTIONS;
                Component label = Component.literal(detail.title() + "\n" + detail.values() + "\n" + detail.explanation());
                DebuggerButton details = button("selected-flow-details", new Bounds(area.x() + 5, detailY, area.width() - 10, detailHeight),
                    label, true, false, () -> { }).asHitSurface();
                details.setTooltip(Tooltip.create(label));
                BreakpointTarget target = selectedBreakpoint();
                if (target != null) conditionMenu(details, "selected-flow-details", flow, target,
                    snippet.text(), state.selectedUnobservedStageIndex() >= 0);
            }
            graphics.fill(area.x() + 1, actionY - 3, area.x() + area.width() - 1, actionY - 2, DebuggerTheme.color(BORDER));
            ExecutionFlowStage stage = state.selectedExecutionFlowStage();
            boolean warning = stage != null && hasFlowWarning();
            int summaryX = area.x() + 8;
            if (warning && actionLeft - summaryX >= 17) {
                navigationGroup = DebuggerNavigation.Group.ACTIONS;
                warningButton("flow-warning", new Bounds(summaryX, actionY, 16, 14), stage);
                summaryX += 18;
            }
            String footer = stage == null ? "" : conditionSummary(stage);
            if (hasFlowWarning()) footer = (footer.isEmpty() ? "" : footer + " · ")
                + tr("codon.ui.flow_detail.trace_warning", warningSummary(state.selectedExecutionFlow()));
            drawText(graphics, detailHeight < client.font.lineHeight + 2 ? detail.title() : footer,
                summaryX, actionY + 4, Math.max(0, actionLeft - summaryX - 6), MUTED);
        }
        return finish();
    }

    private void renderPath(GuiGraphicsExtractor graphics, Bounds area, PauseSnapshot snapshot) {
        navigationGroup = DebuggerNavigation.Group.CALL_PATH;
        List<CallFrame> frames = state.displayedCallStack();
        int titleWidth = Math.min(client.font.width(tr("codon.ui.call_stack", frames.size())) + 10,
            Math.max(0, area.width() / 2));
        drawText(graphics, tr("codon.ui.call_stack", frames.size()), area.x() + 3, area.y() + 4, titleWidth - 6, MUTED);
        int left = area.x() + titleWidth;
        int available = Math.max(0, area.width() - titleWidth);
        if (frames.isEmpty()) {
            drawText(graphics, tr("codon.ui.stack_unavailable"), left + 3, area.y() + 4, available - 6, MUTED);
            return;
        }
        if (available < 5) return;
        int total = 0;
        int selectedStart = 0;
        int selectedEnd = 0;
        for (int i = frames.size() - 1; i >= 0; i--) {
            int width = client.font.width(frameLabel(snapshot, i)) + 10 + frameIconWidth(i);
            if (i == state.selectedCallFrameIndex()) { selectedStart = total; selectedEnd = total + width; }
            int start = total;
            int end = total + width;
            navigation.add("path-" + i + "-" + frames.get(i).invocationId(), navigationGroup, 0, frames.size() - 1 - i,
                () -> {
                    if (end - start > available) {
                        if (end <= stackOffset || start >= stackOffset + available) stackOffset = start;
                    } else if (start < stackOffset) stackOffset = start;
                    else if (end > stackOffset + available) stackOffset = Math.max(start, end - available);
                    stackOffset = Math.clamp(stackOffset, 0, maxStackOffset);
                });
            total += width + (i > 0 ? 8 : 0);
        }
        maxStackOffset = Math.max(0, total - available);
        StackSelection selection = new StackSelection(frames, state.selectedCallFrameIndex(), available);
        if (!selection.equals(lastStackSelection)) {
            if (selectedEnd - selectedStart > available) stackOffset = selectedEnd - available;
            else if (selectedStart < stackOffset) stackOffset = selectedStart;
            else if (selectedEnd > stackOffset + available) stackOffset = selectedEnd - available;
            lastStackSelection = selection;
        }
        stackOffset = Math.clamp(stackOffset, 0, maxStackOffset);
        navigation.revealFocus(DebuggerNavigation.Group.CALL_PATH);
        stackBounds = area;
        int x = left - stackOffset;
        for (int i = frames.size() - 1; i >= 0; i--) {
            String label = frameLabel(snapshot, i);
            int width = client.font.width(label) + 10 + frameIconWidth(i);
            int visibleLeft = Math.max(left, x);
            int visibleRight = Math.min(left + available, x + width);
            if (visibleRight - visibleLeft >= 5) {
                DebuggerButton frame = button("path-" + i + "-" + frames.get(i).invocationId(),
                    new Bounds(visibleLeft, area.y(), visibleRight - visibleLeft, 15), Component.literal(label),
                    true, state.selectedCallFrameIndex() == i, frameAction(i))
                    .withHorizontalViewport(visibleLeft - x, width);
                if (frameIconWidth(i) > 0) frame.withTextIcon(DebuggerIcon.PAUSE);
                if (i == state.selectedCallFrameIndex() && state.isViewingCurrentCommand()) frame.withStatusColor(AMBER, AMBER_SURFACE);
                frame.setTooltip(Tooltip.create(frameTooltip(frames.get(i))));
            }
            x += width;
            if (i > 0 && x + 2 >= left && x + 8 <= left + available)
                drawText(graphics, "›", x + 2, area.y() + 4, 6, MUTED);
            x += 8;
        }
        if (maxStackOffset > 0) {
            int thumb = Math.min(available, Math.max(5, available * available / total));
            scrollbars.add("path", true, left, area.y() + 16, available, 1, thumb, stackOffset, maxStackOffset,
                value -> stackOffset = value);
            int thumbX = left + (available - thumb) * stackOffset / maxStackOffset;
            graphics.fill(left, area.y() + 16, left + available, area.y() + 17, DebuggerTheme.color(BORDER));
            graphics.fill(thumbX, area.y() + 16, thumbX + thumb, area.y() + 17, DebuggerTheme.color(SCROLLBAR));
        }
    }

    private int renderActions(Bounds area) {
        navigationGroup = DebuggerNavigation.Group.ACTIONS;
        int right = area.x() + area.width();
        button("expand", new Bounds(right - 17, area.y(), 17, 16),
            Component.translatable(expanded ? "codon.ui.collapse_command" : "codon.ui.expand_command"), true, false,
            () -> expanded = !expanded).withIcon(expanded ? DebuggerIcon.PANEL_COLLAPSE : DebuggerIcon.PANEL_EXPAND);
        right -= 20;
        int width = labelWidth("codon.ui.return_current");
        DebuggerButton current = button("current", new Bounds(right - width, area.y(), width, 16),
            Component.translatable("codon.ui.return_current"),
            !state.isViewingCurrentCommand(), false, () -> { state.selectCurrentCommand(); changed(); });
        if (!state.isViewingCurrentCommand()) current.withStatusColor(AMBER, AMBER_SURFACE)
            .setTooltip(Tooltip.create(Component.translatable("codon.ui.return_pause")));
        right -= width + 3;
        if (right - area.x() >= 38) {
            button("flow-next", new Bounds(right - 16, area.y(), 16, 16), Component.translatable("codon.ui.next_recorded_command"),
                state.hasAdjacentExecutionVisit(1), false,
                () -> { state.selectAdjacentExecutionVisit(1); changed(); })
                .withIcon(DebuggerIcon.HISTORY_NEXT);
            button("flow-prev", new Bounds(right - 34, area.y(), 16, 16), Component.translatable("codon.ui.previous_recorded_command"),
                state.hasAdjacentExecutionVisit(-1), false,
                () -> { state.selectAdjacentExecutionVisit(-1); changed(); })
                .withIcon(DebuggerIcon.HISTORY_PREVIOUS);
            right -= 38;
        }
        return right;
    }

    private @Nullable BreakpointTarget selectedBreakpoint() {
        ExecutionFlowTrace flow = state.selectedExecutionFlow();
        ExecutionFlowStage stage = state.selectedExecutionFlowStage();
        if (flow == null || flow.location() instanceof SourceLocation.Player) return null;
        int unobserved = state.selectedUnobservedStageIndex();
        if (unobserved >= 0) return BreakpointTargetPolicy.target(flow.location(), unobserved,
            state.selectedCommand().text(), stageCount(flow, state.selectedCommand().text()));
        if (stage == null) return null;
        return breakpointTarget(flow, stage);
    }

    private int stageCount(ExecutionFlowTrace flow, String command) {
        return BreakpointTargetPolicy.stageCount(command, state.stagePreviews().get(flow.location()), flow);
    }

    private @Nullable BreakpointTarget breakpointTarget(ExecutionFlowTrace flow, ExecutionFlowStage stage) {
        return BreakpointTargetPolicy.target(flow.location(), stage.index(), stage.command().text(),
            stageCount(flow, stage.command().text()));
    }

    boolean openContextMenu(net.minecraft.client.gui.components.events.GuiEventListener focused) {
        Runnable action = contextMenus.get(focused);
        if (action == null) return false;
        action.run();
        return true;
    }

    void revealSelection() { lastSelection = null; }

    private void conditionMenu(DebuggerButton button, String focusId, ExecutionFlowTrace flow,
                               BreakpointTarget target, String command, boolean unobserved) {
        PauseSnapshot expected = renderedSnapshot;
        Runnable open = () -> {
            var parent = client.gui.screen();
            if (parent == null) return;
            scrollbars.release();
            java.util.function.BooleanSupplier current = () -> state.snapshot() == expected
                && state.selectedExecutionFlow() == flow && (!unobserved || currentPreviewTarget(flow, target, command));
            BreakpointContextMenu.open(parent, state, target,
                new Bounds(button.getX(), button.getY(), button.getWidth(), button.getHeight()), current,
                () -> navigation.requestFocus(focusId));
        };
        button.withSecondaryAction(open);
        contextMenus.put(button, open);
    }

    private boolean currentPreviewTarget(ExecutionFlowTrace flow, BreakpointTarget target, String command) {
        var preview = state.stagePreviews().get(flow.location());
        return preview != null && (preview.status() == works.nuty.codon.client.state.ClientStagePreviewState.Status.LOADING
            || preview.status() == works.nuty.codon.client.state.ClientStagePreviewState.Status.READY
                && preview.savedCommand().equals(command) && (target.wholeCommand() || preview.spans().stream()
                    .anyMatch(span -> span.index() == target.stageIndex())));
    }

    private void renderClauses(GuiGraphicsExtractor graphics, Bounds body, PauseSnapshot snapshot) {
        navigationGroup = DebuggerNavigation.Group.COMMAND;
        if (body.height() < 15 || body.width() < 10) return;
        CommandSnippet snippet = state.selectedCommand();
        if (snippet == null) return;
        ExecutionFlowTrace flow = state.selectedExecutionFlow();
        if (flow == null) {
            renderRawCommand(graphics, body, snippet, snapshot);
            return;
        }
        CommandFlowLayout.Content content = CommandFlowLayout.content(snippet, flow, state.stagePreviews().get(flow.location()));
        List<Part> parts = content.parts();
        if (!content.inline()) {
            parts = new ArrayList<>(parts);
            parts.addFirst(new Part(content.command(), -1));
        }
        List<Part> displayed = parts;
        // Parsed clauses include the separator after the preceding stage. The cell
        // gap/padding already separates them; keep the original text for targets/tooltips.
        List<Part> layoutParts = parts.stream().map(part -> part.targetStageIndex() < 0 ? part
            : new Part(part.text().stripLeading(), part.stageIndex(), part.targetStageIndex(), part.observation())).toList();
        boolean editableSource = !(flow.location() instanceof SourceLocation.Player)
            && stageCount(flow, snippet.text()) > 0;
        // The parser (or conclusive recorded evidence) decides single versus multi-stage.
        // A multi-stage command has a separate whole-command marker before its first part.
        boolean commandMarker = editableSource && stageCount(flow, snippet.text()) > 1;
        java.util.function.IntUnaryOperator minimumWidth = index -> {
                int stage = displayed.get(index).stageIndex();
                // Counts occupy their own line. Breakpoint/warning icons belong only to
                // the command line and are already included by leadingInset below.
                if (stage < 0 || body.height() < 30) return 0;
                return client.font.width(counts(flow.stages().get(stage))) + CELL_HORIZONTAL_PADDING
                    + (hasWarning(flow.stages().get(stage)) ? 17 : 0);
            };
        java.util.function.IntUnaryOperator leadingInset = index -> {
            int stage = displayed.get(index).stageIndex();
            int iconInset = stage >= 0 && body.height() < 30 && hasWarning(flow.stages().get(stage))
                ? DebuggerButton.TEXT_ICON_INSET : 0;
            return iconInset + (displayed.get(index).targetStageIndex() >= 0 && editableSource ? 15 : 0)
                + (commandMarker && index == 0 ? 15 : 0);
        };
        CommandFlowLayout.Layout layout = CommandFlowLayout.layout(layoutParts, body.width() - 4,
            client.font::width, minimumWidth, leadingInset);
        if (layout.rows() > 1) {
            layout = CommandFlowLayout.layout(layoutParts, body.width() - 4 - DebuggerIcon.SIZE,
                client.font::width, minimumWidth, leadingInset);
        }
        int rowHeight = body.height() < 30 ? 17 : 30;
        int rows = Math.max(1, body.height() / rowHeight);
        maxCommandOffset = Math.max(0, layout.rows() - rows);
        Selection selection = new Selection(snapshot, state.selectedCallFrameIndex(), state.selectedFlowIndex(),
            state.selectedFlowStageIndex(), state.selectedUnobservedStageIndex(), body.width(), body.height());
        if (!selection.equals(lastSelection)) {
            int selectedStage = state.selectedFlowStageIndex();
            var selectedRows = layout.cells().stream()
                .filter(cell -> selectedStage >= 0 ? displayed.get(cell.partIndex()).stageIndex() == selectedStage
                    : state.selectedUnobservedStageIndex() >= 0
                        && displayed.get(cell.partIndex()).targetStageIndex() == state.selectedUnobservedStageIndex())
                .mapToInt(CommandFlowLayout.Cell::row).summaryStatistics();
            int firstRow = selectedRows.getCount() == 0 ? 0 : selectedRows.getMin();
            int lastRow = selectedRows.getCount() == 0 ? 0 : selectedRows.getMax();
            // A visible fragment is already reachable. In particular, clicking it must not
            // scroll that same clause away from the pointer, even when it spans several rows.
            if (lastRow < commandOffset) commandOffset = firstRow;
            else if (firstRow >= commandOffset + rows) commandOffset = firstRow - rows + 1;
            commandOffset = Math.clamp(commandOffset, 0, maxCommandOffset);
            lastSelection = selection;
        }
        commandOffset = Math.clamp(commandOffset, 0, maxCommandOffset);
        commandBounds = body;
        for (CommandFlowLayout.Cell cell : layout.cells()) {
            Part part = parts.get(cell.partIndex());
            int stageIndex = part.stageIndex();
            Runnable reveal = () -> commandOffset = DebuggerOverlay.revealRow(cell.row(), commandOffset, rows, maxCommandOffset);
            // Keep each cell's marker, clause and warning together in visual reading order.
            // Four slots also leave room for the first cell's whole-command marker.
            int column = cell.x() * 4;
            if (commandMarker && cell.partIndex() == 0 && cell.first()
                && state.breakpoints().ready() && !state.breakpoints().pending(BreakpointTarget.whole(flow.location()))) {
                navigation.add("flow-line-" + flow.invocationId(), navigationGroup, cell.row(), column++, reveal);
            }
            if (stageIndex >= 0) {
                ExecutionFlowStage stage = flow.stages().get(stageIndex);
                if (cell.first() && editableSource && state.breakpoints().ready()
                    && !state.breakpoints().pending(breakpointTarget(flow, stage))) {
                    navigation.add("breakpoint-" + flow.invocationId() + "-" + stageIndex,
                        navigationGroup, cell.row(), column++, reveal);
                }
                navigation.add("clause-" + flow.invocationId() + "-" + stage.index() + "-" + cell.row(),
                    navigationGroup, cell.row(), column++, reveal);
                if (cell.first() && rowHeight >= 30 && hasWarning(stage)) {
                    navigation.add("warning-" + flow.invocationId() + "-" + stage.index(),
                        navigationGroup, cell.row(), column, reveal);
                }
            } else if (part.targetStageIndex() >= 0) {
                BreakpointTarget target = stageCount(flow, snippet.text()) == 1 ? BreakpointTarget.whole(flow.location())
                    : BreakpointTarget.stage(flow.location(), part.targetStageIndex(), snippet.text());
                if (cell.first() && editableSource && !state.breakpoints().pending(target)) {
                    navigation.add(unobservedKey("breakpoint", flow, part), navigationGroup, cell.row(), column++, reveal);
                }
                navigation.add(unobservedKey("clause", flow, part) + "-" + cell.row(),
                    navigationGroup, cell.row(), column, reveal);
            }
        }
        navigation.revealFocus(DebuggerNavigation.Group.COMMAND);
        for (int cellIndex = 0; cellIndex < layout.cells().size(); cellIndex++) {
            CommandFlowLayout.Cell cell = layout.cells().get(cellIndex);
            Part part = parts.get(cell.partIndex());
            int stageIndex = part.stageIndex();
            int row = cell.row() - commandOffset;
            if (row < 0 || row >= rows) continue;
            int x = body.x() + cell.x();
            int y = body.y() + row * rowHeight;
            if (cellIndex + 1 < layout.cells().size()
                    && layout.cells().get(cellIndex + 1).row() > cell.row()
                    && (content.inline() || layout.cells().get(cellIndex + 1).partIndex() == cell.partIndex())) {
                DebuggerIcon.LINE_WRAP.draw(graphics, x + cell.width(), y + 2, DebuggerTheme.foreground(MUTED));
            }
            if (commandMarker && cell.partIndex() == 0 && cell.first()) {
                renderCommandMarker(flow, snippet.text(), x, y);
                x += 15;
                cell = new CommandFlowLayout.Cell(cell.partIndex(), cell.row(), cell.x() + 15,
                    Math.max(1, cell.width() - 15), cell.text(), cell.first());
            }
            if (stageIndex < 0 && part.targetStageIndex() >= 0) {
                renderUnobservedClause(graphics, flow, part, cell, x, y, rowHeight, editableSource,
                    cellIndex + 1 < layout.cells().size() && layout.cells().get(cellIndex + 1).partIndex() == cell.partIndex());
            } else if (stageIndex >= 0 && flow != null) {
                ExecutionFlowStage stage = flow.stages().get(stageIndex);
                boolean stopped = state.isPaused() && state.selectedFlowIndex() == state.pausedFlowIndex()
                    && stageIndex == state.pausedFlowStageIndex();
                int clauseX = x;
                int clauseWidth = cell.width();
                if (cell.first() && editableSource) {
                    BreakpointTarget target = breakpointTarget(flow, stage);
                    BreakpointDefinition definition = state.breakpoints().get(target);
                    DebuggerButton breakpoint = button("breakpoint-" + flow.invocationId() + "-" + stageIndex,
                        new Bounds(x, y, 14, 16), Component.translatable("codon.breakpoint.toggle"),
                        state.breakpoints().ready() && !state.breakpoints().pending(target), false, () -> {
                            if (state.selectedExecutionFlow() != flow) return;
                            toggleExact(target);
                            state.selectExecutionFlowStage(stageIndex);
                            changed();
                        });
                    breakpoint.withoutChrome().withSmallIcon(BreakpointUi.icon(definition));
                    conditionMenu(breakpoint, "breakpoint-" + flow.invocationId() + "-" + stageIndex,
                        flow, target, stage.command().text(), false);
                    breakpoint.withStatusColor(definition != null && definition.enabled() ? RED : MUTED,
                        definition != null && definition.enabled() ? RED_SURFACE : SURFACE);
                    var error = state.breakpoints().error(target);
                    breakpoint.setTooltip(Tooltip.create(Component.literal(
                        (definition == null ? tr("codon.breakpoint.add")
                            : definition.enabled() ? tr("codon.breakpoint.disable") : tr("codon.breakpoint.enable"))
                            + (definition == null ? "" : " · " + BreakpointUi.condition(definition.condition()))
                            + (error == null ? "" : "\n" + tr("codon.breakpoint.error."
                                + error.name().toLowerCase(java.util.Locale.ROOT))))));
                    clauseX += 15;
                    clauseWidth = Math.max(1, clauseWidth - 15);
                }
                DebuggerButton clause = button("clause-" + flow.invocationId() + "-" + stage.index() + "-" + cell.row(),
                    new Bounds(clauseX, y, clauseWidth, 16), Component.literal(cell.text()), true,
                    stageIndex == state.selectedFlowStageIndex(), () -> {
                        if (state.selectedExecutionFlow() != flow) return;
                        state.selectExecutionFlowStage(stageIndex);
                        changed();
                    });
                clause.withTextPadding(CELL_HORIZONTAL_PADDING);
                if (stopped) clause.withStatusColor(AMBER, AMBER_SURFACE);
                clause.withOpenEdges(!cell.first(), cellIndex + 1 < layout.cells().size()
                    && layout.cells().get(cellIndex + 1).partIndex() == cell.partIndex());
                clause.setTooltip(Tooltip.create(Component.literal(part.text().strip() + "\n"
                    + (stopped ? tr("codon.ui.flow_detail.stop") + " · #" + (stage.index() + 1) + "\n" : "")
                    + stageDetails(stage))));
                if (editableSource) conditionMenu(clause,
                    "clause-" + flow.invocationId() + "-" + stage.index() + "-" + cell.row(),
                    flow, breakpointTarget(flow, stage), stage.command().text(), false);
                if (cell.first() && rowHeight < 30 && hasWarning(stage)) clause.withTextIcon(DebuggerIcon.WARNING);
                if (cell.first() && rowHeight >= 30) {
                    String count = counts(stage);
                    drawText(graphics, count, x + CELL_HORIZONTAL_PADDING / 2, y + 20,
                        cell.width() - CELL_HORIZONTAL_PADDING - (hasWarning(stage) ? 17 : 0), stopped ? AMBER : MUTED);
                    if (hasWarning(stage)) warningButton("warning-" + flow.invocationId() + "-" + stage.index(),
                        new Bounds(x + cell.width() - 17, y + 16, 16, 14), stage);
                }
            } else {
                if (stageIndex == -2) drawText(graphics, cell.text(), x, y + 4, cell.width(), MUTED);
                else drawText(graphics, cell.text(), x + CELL_HORIZONTAL_PADDING / 2, y + 4,
                    cell.width() - CELL_HORIZONTAL_PADDING, flow == null ? TEXT : MUTED);
                if (flow != null && stageIndex == -1 && cell.first() && rowHeight >= 30) {
                    drawText(graphics, observationLabel(part), x + CELL_HORIZONTAL_PADDING / 2,
                        y + 20, cell.width() - CELL_HORIZONTAL_PADDING, MUTED);
                }
            }
        }
        scrollbar(graphics, body.x() + body.width() - 1, body.y(), body.height(), commandOffset, maxCommandOffset, rows);
    }

    private void renderUnobservedClause(GuiGraphicsExtractor graphics, ExecutionFlowTrace flow, Part part,
                                       CommandFlowLayout.Cell cell, int x, int y, int rowHeight,
                                       boolean editableSource, boolean continues) {
        String command = state.selectedCommand().text();
        BreakpointTarget target = stageCount(flow, command) == 1 ? BreakpointTarget.whole(flow.location())
            : BreakpointTarget.stage(flow.location(), part.targetStageIndex(), command);
        BreakpointDefinition definition = state.breakpoints().get(target);
        var preview = state.stagePreviews().get(flow.location());
        PauseSnapshot expected = renderedSnapshot;
        Runnable select = () -> {
            if (state.snapshot() != expected || state.selectedExecutionFlow() != flow
                || state.stagePreviews().get(flow.location()) != preview) return;
            state.selectUnobservedExecutionFlowStage(part.targetStageIndex());
            changed();
        };
        int inset = cell.first() && editableSource ? 15 : 0;
        if (inset > 0) {
            DebuggerButton marker = button(unobservedKey("breakpoint", flow, part), new Bounds(x, y, 14, 16),
                Component.translatable("codon.breakpoint.toggle"), !state.breakpoints().pending(target), false, () -> {
                    if (state.snapshot() != expected || state.selectedExecutionFlow() != flow
                        || state.stagePreviews().get(flow.location()) != preview) return;
                    select.run();
                    if (state.selectedUnobservedStageIndex() != part.targetStageIndex()) return;
                    BreakpointDefinition current = state.breakpoints().get(target);
                    ClientNetworking.sendBreakpointEdit(state, ClientBreakpointState.Action.TOGGLE,
                        current == null ? BreakpointDefinition.plain(target) : current);
                });
            marker.withoutChrome().withSmallIcon(BreakpointUi.icon(definition));
            conditionMenu(marker, unobservedKey("breakpoint", flow, part), flow, target,
                state.selectedCommand().text(), true);
            marker.withStatusColor(definition != null && definition.enabled() ? RED : MUTED,
                definition != null && definition.enabled() ? RED_SURFACE : SURFACE);
            var error = state.breakpoints().error(target);
            marker.setTooltip(Tooltip.create(Component.literal((definition == null ? tr("codon.breakpoint.add")
                : definition.enabled() ? tr("codon.breakpoint.disable") : tr("codon.breakpoint.enable"))
                + (definition == null ? "" : " · " + BreakpointUi.condition(definition.condition()))
                + (error == null ? "" : "\n" + tr("codon.breakpoint.error."
                    + error.name().toLowerCase(java.util.Locale.ROOT))))));
        }
        DebuggerButton clause = button(unobservedKey("clause", flow, part) + "-" + cell.row(),
            new Bounds(x + inset, y, Math.max(1, cell.width() - inset), 16), Component.literal(cell.text()), true,
            state.selectedUnobservedStageIndex() == part.targetStageIndex(), select);
        clause.withTextPadding(CELL_HORIZONTAL_PADDING).withStatusColor(MUTED, SURFACE).withOpenEdges(!cell.first(), continues);
        clause.setTooltip(Tooltip.create(Component.literal(part.text().strip() + "\n" + observationText(part))));
        if (editableSource) conditionMenu(clause, unobservedKey("clause", flow, part) + "-" + cell.row(),
            flow, target, state.selectedCommand().text(), true);
        if (cell.first() && rowHeight >= 30) drawText(graphics, observationLabel(part),
            x + CELL_HORIZONTAL_PADDING / 2, y + 20, cell.width() - CELL_HORIZONTAL_PADDING, MUTED);
    }

    private void toggleExact(BreakpointTarget target) {
        if (!state.breakpoints().ready() || state.breakpoints().pending(target)) return;
        var definition = state.breakpoints().get(target);
        ClientNetworking.sendBreakpointEdit(state, ClientBreakpointState.Action.TOGGLE,
            definition == null ? BreakpointDefinition.plain(target) : definition);
    }

    private void renderCommandMarker(ExecutionFlowTrace flow, String command, int x, int y) {
        BreakpointTarget target = BreakpointTarget.whole(flow.location());
        BreakpointDefinition definition = state.breakpoints().get(target);
        String id = "flow-line-" + flow.invocationId();
        DebuggerButton marker = button(id, new Bounds(x, y, 14, 16), Component.literal(BreakpointUi.target(target)),
            state.breakpoints().ready() && !state.breakpoints().pending(target), false, () -> {
                if (state.selectedExecutionFlow() == flow) toggleExact(target);
            }).withoutChrome().withSmallIcon(BreakpointUi.icon(definition));
        marker.withStatusColor(definition != null && definition.enabled() ? RED : MUTED,
            definition != null && definition.enabled() ? RED_SURFACE : SURFACE);
        marker.setTooltip(Tooltip.create(Component.literal(BreakpointUi.target(target) + "\n"
            + tr(definition == null ? "codon.breakpoint.add" : definition.enabled()
                ? "codon.breakpoint.disable" : "codon.breakpoint.enable")
            + (definition == null ? "" : " · " + BreakpointUi.condition(definition.condition())))));
        conditionMenu(marker, id, flow, target, command, false);
    }

    private static String unobservedKey(String control, ExecutionFlowTrace flow, Part part) {
        return "unobserved-" + control + "-" + flow.invocationId() + "-" + part.targetStageIndex();
    }

    private boolean executionError(int stageIndex) {
        if (stageIndex < 0) return false;
        return warningsForStage(state.selectedExecutionFlow(), stageIndex).stream()
            .anyMatch(warning -> warning.reason() == ExecutionFlowWarning.Reason.EXECUTION_ERROR);
    }

    private String observationKey(Part part) {
        if (executionError(part.targetStageIndex())) return "codon.ui.flow_detail.error";
        if (part.observation() == CommandFlowLayout.Observation.FILTERED_OUT) return "codon.ui.filtered_out";
        // An absent historical clause does not prove non-execution. Only the suffix beyond
        // the actual pause in this same invocation has evidence that execution has not reached it.
        if (part.observation() == CommandFlowLayout.Observation.NOT_EXECUTED && state.isPaused()
            && state.selectedFlowIndex() == state.pausedFlowIndex() && state.pausedFlowStageIndex() >= 0) {
            ExecutionFlowTrace flow = state.selectedExecutionFlow();
            if (flow != null && part.targetStageIndex() > flow.stages().get(state.pausedFlowStageIndex()).index())
                return "codon.ui.not_executed";
        }
        return "codon.ui.stage_unavailable";
    }

    private String observationText(Part part) { return tr(observationKey(part)); }
    private String observationLabel(Part part) {
        String key = observationKey(part);
        // Future clauses need no repeated inline badge; detail/tooltip retain the evidence.
        return key.equals("codon.ui.not_executed") ? "" : tr(key + ".short");
    }

    private void renderRawCommand(GuiGraphicsExtractor graphics, Bounds body, CommandSnippet command, PauseSnapshot snapshot) {
        var lines = rawCommandLines(command, Math.max(1, body.width() - 12));
        if (lines.size() > 1) {
            lines = rawCommandLines(command, Math.max(1, body.width() - 12 - DebuggerIcon.SIZE));
        }
        int rows = Math.max(1, body.height() / 11);
        maxCommandOffset = Math.max(0, lines.size() - rows);
        Selection selection = new Selection(snapshot, state.selectedCallFrameIndex(), state.selectedFlowIndex(), -1, -1,
            body.width(), body.height());
        if (!selection.equals(lastSelection)) { commandOffset = 0; lastSelection = selection; }
        commandOffset = Math.clamp(commandOffset, 0, maxCommandOffset);
        commandBounds = body;
        graphics.fill(body.x(), body.y(), body.x() + 2, body.y() + body.height(), DebuggerTheme.color(state.isViewingCurrentCommand() ? AMBER : MUTED));
        graphics.enableScissor(body.x() + 4, body.y(), body.x() + body.width() - 4, body.y() + body.height());
        for (int i = 0; i < rows && commandOffset + i < lines.size(); i++) {
            graphics.text(client.font, lines.get(commandOffset + i), body.x() + 5, body.y() + i * 11, DebuggerTheme.foreground(TEXT), false);
            if (commandOffset + i + 1 < lines.size()) {
                DebuggerIcon.LINE_WRAP.draw(graphics,
                    body.x() + 5 + client.font.width(lines.get(commandOffset + i)), body.y() + i * 11 - 1, DebuggerTheme.foreground(MUTED));
            }
        }
        graphics.disableScissor();
        scrollbar(graphics, body.x() + body.width() - 1, body.y(), body.height(), commandOffset, maxCommandOffset, rows);
    }

    private List<net.minecraft.util.FormattedCharSequence> rawCommandLines(CommandSnippet command, int width) {
        var lines = new ArrayList<net.minecraft.util.FormattedCharSequence>();
        int offset = 0;
        for (String text : CommandFlowLayout.wrapCharacters(command.text(), width, client.font::width)) {
            lines.add(ClientFormatting.command(new CommandSnippet(text,
                command.highlightStart() - offset, command.highlightEnd() - offset)).getVisualOrderText());
            offset += text.length();
        }
        return lines;
    }

    private SelectionDetail selectionDetail() {
        ExecutionFlowStage stage = state.selectedExecutionFlowStage();
        int unobserved = state.selectedUnobservedStageIndex();
        if (unobserved >= 0) {
            ExecutionFlowTrace flow = state.selectedExecutionFlow();
            CommandSnippet command = state.selectedCommand();
            Part part = flow == null || command == null ? null
                : CommandFlowLayout.content(command, flow, state.stagePreviews().get(flow.location())).parts().stream()
                    .filter(candidate -> candidate.targetStageIndex() == unobserved).findFirst().orElse(null);
            String key = executionError(unobserved) ? "codon.ui.flow_detail.error"
                : part == null ? "codon.ui.stage_unavailable" : observationKey(part);
            String values = tr(key.equals("codon.ui.filtered_out") ? "codon.ui.flow_detail.unreached_counts"
                : key.equals("codon.ui.not_executed") ? "codon.ui.flow_detail.future_counts"
                : key.equals("codon.ui.stage_unavailable") ? "codon.ui.flow_detail.unknown_counts" : "codon.ui.flow_detail.no_counts");
            return new SelectionDetail(tr("codon.ui.flow_detail.title", tr("codon.ui.flow_detail.selected"),
                unobserved + 1, tr(key + ".short")), values, tr(key),
                executionError(unobserved) ? RED : TEAL);
        }
        if (stage == null) return new SelectionDetail(tr("codon.ui.no_flow"),
            tr("codon.ui.flow_detail.no_counts"), tr("codon.ui.stage_unavailable"), MUTED);
        boolean error = executionError(stage.index());
        boolean incomplete = hasWarning(stage);
        boolean resultMissing = stage.terminal() ? stage.executionCount() < 0 || stage.successCount() < 0
            : !stage.complete() || stage.outputCount() < 0;
        String status = error ? "error.short" : incomplete ? "capture_incomplete"
            : resultMissing ? state.isViewingCurrentCommand() ? "awaiting_result" : "unknown_result" : "observed";
        String scope = tr(state.isViewingCurrentCommand() ? "codon.ui.flow_detail.stop" : "codon.ui.flow_detail.history");
        String title = tr("codon.ui.flow_detail.title", scope, stage.index() + 1, tr("codon.ui.flow_detail." + status));
        String values = stage.terminal()
            ? tr("codon.ui.flow_detail.results", detailCount(stage.inputCount()), detailCount(stage.executionCount()),
                detailCount(stage.successCount()))
            : tr("codon.ui.flow_detail.contexts", detailCount(stage.inputCount()),
                detailCount(stage.complete() ? stage.outputCount() : ExecutionFlowStage.UNMEASURED),
                detailCount(stage.complete() && stage.outputCount() >= 0 ? stage.droppedCount() : ExecutionFlowStage.UNMEASURED));
        return new SelectionDetail(title, values, stageDetails(stage) + "\n" + tr("codon.ui.flow_detail.count_hint"),
            error ? RED : incomplete ? AMBER : state.isViewingCurrentCommand() ? AMBER : TEAL);
    }

    private static String detailCount(int count) { return count < 0 ? "?" : Integer.toString(count); }

    private String conditionSummary(ExecutionFlowStage stage) {
        BreakpointTarget target = selectedBreakpoint();
        if (target == null) return "";
        ExecutionFlowTrace flow = state.selectedExecutionFlow();
        // Describe the exact selected target, without borrowing a different line/stage condition.
        if (stage.terminal() && stageCount(flow, stage.command().text()) != 1) return "";
        BreakpointDefinition definition = state.breakpoints().get(target);
        if (definition == null || !definition.enabled()
            || definition.condition().kind() == BreakpointCondition.Kind.ALWAYS) return "";
        int actual = BreakpointConditionEvaluator.observedCount(definition.condition().kind(), stage);
        return BreakpointUi.condition(definition.condition()) + " · "
            + tr("codon.ui.breakpoint_actual", measuredCount(actual));
    }

    private boolean hasWarning(ExecutionFlowStage stage) {
        ExecutionFlowTrace flow = state.selectedExecutionFlow();
        return !warningsForStage(flow, stage.index()).isEmpty() || !stage.lineageComplete() || stage.truncated();
    }

    private String stageDetails(ExecutionFlowStage stage) {
        String details = stageSummary(stage) + "\n" + tr("codon.ui.context_explanation");
        ExecutionFlowTrace flow = state.selectedExecutionFlow();
        List<ExecutionFlowWarning> warnings = warningsForStage(flow, stage.index());
        if (!warnings.isEmpty()) {
            for (ExecutionFlowWarning warning : warnings) details += "\n" + warningText(warning);
        } else if (!stage.lineageComplete() || stage.truncated()) {
            details += "\n" + tr("codon.ui.recording_warning_legacy");
        }
        return details;
    }

    private boolean hasFlowWarning() {
        ExecutionFlowTrace flow = state.selectedExecutionFlow();
        return flow != null && (!flow.warnings().isEmpty() || flow.truncated()
            || flow.stages().stream().anyMatch(stage -> !stage.lineageComplete() || stage.truncated()));
    }

    private static List<ExecutionFlowWarning> warningsForStage(ExecutionFlowTrace flow, int stageIndex) {
        if (flow == null) return List.of();
        return flow.warnings().stream().filter(warning -> warning.stageIndex() == stageIndex).toList();
    }

    static String warningSummary(ExecutionFlowTrace flow) {
        if (flow == null || flow.warnings().isEmpty()) return tr("codon.ui.recording_warning_legacy");
        String result = warningSummary(flow.warnings().getFirst());
        int more = flow.warnings().size() - 1;
        return more == 0 ? result : tr("codon.ui.recording_warning_more", result, more);
    }

    static String warningText(ExecutionFlowWarning warning) {
        String result = warningSummary(warning) + ": " + highlightedCommand(warning.command());
        return warning.detail().isEmpty() ? result : result + "\n" + warning.detail();
    }

    private static String warningSummary(ExecutionFlowWarning warning) {
        String reason = tr("codon.ui.recording_reason." + warning.reason().name().toLowerCase(java.util.Locale.ROOT));
        if (warning.limit() >= 0) reason = tr("codon.ui.recording_warning_limit", reason, warning.limit());
        return warning.stageIndex() >= 0 ? tr("codon.ui.recording_warning_stage", reason, warning.stageIndex() + 1) : reason;
    }

    private static String highlightedCommand(CommandSnippet command) {
        String text = command.text();
        int start = Math.clamp(command.highlightStart(), 0, text.length());
        int end = Math.clamp(command.highlightEnd(), start, text.length());
        return text.substring(start, end);
    }

    private void warningButton(String key, Bounds bounds, ExecutionFlowStage stage) {
        button(key, bounds, Component.translatable("codon.ui.recording_warning"), true, false, () -> { })
            .withIcon(DebuggerIcon.WARNING).withoutChrome().withStatusColor(AMBER, AMBER_SURFACE)
            .setTooltip(Tooltip.create(Component.literal(key.equals("flow-warning") ? flowDetails(stage) : stageDetails(stage))));
    }

    private String flowDetails(ExecutionFlowStage selectedStage) {
        ExecutionFlowTrace flow = state.selectedExecutionFlow();
        if (flow == null) return stageDetails(selectedStage);
        if (flow.warnings().isEmpty()) return flow.truncated()
            ? stageDetails(selectedStage) + "\n" + tr("codon.ui.recording_warning_legacy")
            : stageDetails(selectedStage);
        StringBuilder details = new StringBuilder();
        for (ExecutionFlowWarning warning : flow.warnings()) {
            if (!details.isEmpty()) details.append('\n');
            details.append(warningText(warning));
        }
        return details.toString();
    }

    static String counts(ExecutionFlowStage stage) {
        String result = measuredCount(stage.inputCount()) + "→"
            + (stage.complete() ? measuredCount(stage.outputCount()) : tr("codon.ui.unmeasured"));
        if (stage.droppedCount() > 0) result += "  −" + stage.droppedCount();
        return result;
    }

    static String stageSummary(ExecutionFlowStage stage) {
        String summary = stage.terminal()
            ? Component.translatable("codon.ui.command_results", measuredCount(stage.inputCount()),
                measuredCount(stage.executionCount()), measuredCount(stage.successCount())).getString()
            : Component.translatable("codon.ui.command_contexts", measuredCount(stage.inputCount()),
                stage.complete() ? measuredCount(stage.outputCount()) : tr("codon.ui.unmeasured")).getString();
        if (!stage.terminal() && stage.droppedCount() > 0)
            summary += " · " + tr("codon.ui.flow_dropped", stage.droppedCount());
        return summary;
    }

    private static String measuredCount(int count) {
        return count < 0 ? tr("codon.ui.unmeasured") : Integer.toString(count);
    }

    public boolean scroll(double x, double y, double scrollX, double amount) {
        if (stackBounds.contains(x, y)) {
            double movement = scrollX != 0 ? scrollX : amount;
            if (movement == 0) return false;
            stackOffset = (int) Math.clamp(stackOffset - movement * 24, 0, maxStackOffset);
            return true;
        }
        int delta = amount > 0 ? -1 : amount < 0 ? 1 : 0;
        if (delta == 0) return false;
        if (commandBounds.contains(x, y)) {
            commandOffset = Math.clamp(commandOffset + delta, 0, maxCommandOffset);
        } else return false;
        return true;
    }

    public void clearBounds() {
        commandBounds = stackBounds = EMPTY;
    }

    private Runnable frameAction(int index) {
        CallFrame expected = state.displayedCallStack().get(index);
        return () -> {
            if (index >= state.displayedCallStack().size() || !state.displayedCallStack().get(index).equals(expected)) return;
            state.selectCallFrame(index);
            changed();
        };
    }

    private void changed() {
        selectionChanged.run();
    }

    private DebuggerButton button(String id, Bounds bounds, Component label, boolean active, boolean selected, Runnable action) {
        DebuggerButton button = cache.computeIfAbsent(id, ignored -> new DebuggerButton());
        PauseSnapshot expected = renderedSnapshot;
        button.configure(bounds.x(), bounds.y(), Math.max(1, bounds.width()), bounds.height(), label,
            active, selected, true, false, () -> { if (state.snapshot() == expected) action.run(); });
        button.withFlatChrome();
        used.add(id);
        buttons.add(button);
        navigation.bind(id, navigationGroup, button);
        return button;
    }

    private List<DebuggerButton> finish() {
        cache.keySet().retainAll(used);
        return List.copyOf(buttons);
    }

    private void drawText(GuiGraphicsExtractor graphics, String value, int x, int y, int width, int color) {
        if (width <= 0) return;
        String clipped = client.font.width(value) <= width ? value
            : client.font.plainSubstrByWidth(value, Math.max(0, width - client.font.width("…"))) + "…";
        graphics.enableScissor(x, y, x + width, y + client.font.lineHeight + 1);
        graphics.text(client.font, clipped, x, y, DebuggerTheme.foreground(color), false);
        graphics.disableScissor();
    }

    private void scrollbar(GuiGraphicsExtractor graphics, int x, int y, int height, int offset, int max, int rows) {
        if (max <= 0 || height <= 0) return;
        graphics.fill(x, y, x + 2, y + height, DebuggerTheme.color(BORDER));
        int thumb = Math.min(height, Math.max(5, height * rows / (rows + max)));
        scrollbars.add("command", false, x, y, height, 2, thumb, offset, max, value -> commandOffset = value);
        int top = y + (height - thumb) * offset / max;
        graphics.fill(x, top, x + 2, top + thumb, DebuggerTheme.color(SCROLLBAR));
    }

    private String frameLabel(PauseSnapshot snapshot, int index) {
        return location(state.displayedCallStack().get(index).location());
    }

    private int frameIconWidth(int index) {
        return state.isPausedCallFrame(state.displayedCallStack().get(index)) ? DebuggerButton.TEXT_ICON_INSET : 0;
    }

    private static Component frameTooltip(CallFrame frame) {
        return ClientFormatting.sourceLocation(frame.location()).copy().append("\n" + frame.command().text())
            .append(frame.invocationId() >= 0 ? "\n#" + frame.invocationId() : "");
    }

    private static String location(SourceLocation location) {
        return switch (location) {
            case SourceLocation.Function function -> function.location().function() + ":" + function.location().line();
            case SourceLocation.Block block -> tr("codon.ui.command_block") + " " + block.block().x() + ", " + block.block().y() + ", " + block.block().z();
            case SourceLocation.Player player -> player.name();
        };
    }

    private static String tr(String key, Object... args) { return Component.translatable(key, args).getString(); }

    private int labelWidth(String key) { return client.font.width(Component.translatable(key)) + 10; }

    private record Selection(PauseSnapshot snapshot, int frame, int flow, int stage, int unobserved, int width, int height) { }
    private record StackSelection(List<CallFrame> frames, int selected, int rows) { }
}
