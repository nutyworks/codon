package works.nuty.codon.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.ui.layout.CommandFlowLayout;
import works.nuty.codon.client.ui.layout.CommandFlowLayout.Part;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import works.nuty.codon.core.model.CallFrame;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.ExecutionFlowWarning;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.SourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static works.nuty.codon.client.ui.DebuggerTheme.*;

/** A single command surface: its call path, recorded clauses, and the authoritative stop. */
public final class CommandPanel {
    private static final Bounds EMPTY = new Bounds(0, 0, 0, 0);
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
    private @Nullable Selection lastSelection;
    private @Nullable StackSelection lastStackSelection;
    private @Nullable PauseSnapshot renderedSnapshot;
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
        int base = height < 240 ? 64 : 76;
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
        if (area.width() < 20 || area.height() < 18) return finish();
        graphics.fill(area.x(), area.y(), area.x() + area.width(), area.y() + area.height(), DebuggerTheme.background(PANEL));
        graphics.outline(area.x(), area.y(), area.width(), area.height(), BORDER);
        if (snapshot == null) {
            drawText(graphics, tr("codon.ui.no_snapshot"), area.x() + 7, area.y() + 7, area.width() - 14, MUTED);
            lastSelection = null;
            return finish();
        }

        // The action row is anchored to the screen's bottom, independently of expansion.
        int actionY = Math.max(area.y() + 1, area.y() + area.height() - 20);
        int actionLeft = renderActions(new Bounds(area.x() + 4, actionY, area.width() - 8, 17), snapshot, input, overlay);
        if (area.height() >= 40) {
            int y = area.y() + 3;
            renderPath(graphics, new Bounds(area.x() + 4, y, area.width() - 8, 17), snapshot);
            y += 19;
            Bounds body = new Bounds(area.x() + 5, y, area.width() - 12, Math.max(0, actionY - 4 - y));
            renderClauses(graphics, body, snapshot);
            graphics.fill(area.x() + 1, actionY - 3, area.x() + area.width() - 1, actionY - 2, BORDER);
            ExecutionFlowStage stage = state.selectedExecutionFlowStage();
            boolean warning = stage != null && hasFlowWarning();
            int summaryX = area.x() + 8;
            if (warning && actionLeft - summaryX >= 17) {
                navigationGroup = DebuggerNavigation.Group.ACTIONS;
                warningButton("flow-warning", new Bounds(summaryX, actionY, 16, 14), stage);
                summaryX += 18;
            }
            drawText(graphics, summary(), summaryX, actionY + 4, Math.max(0, actionLeft - summaryX - 6), MUTED);
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
            graphics.fill(left, area.y() + 16, left + available, area.y() + 17, BORDER);
            graphics.fill(thumbX, area.y() + 16, thumbX + thumb, area.y() + 17, TEAL);
        }
    }

    private int renderActions(Bounds area, PauseSnapshot snapshot,
                               InputManager input, DebuggerOverlay overlay) {
        navigationGroup = DebuggerNavigation.Group.ACTIONS;
        int right = area.x() + area.width();
        button("expand", new Bounds(right - 17, area.y(), 17, 16),
            Component.translatable(expanded ? "codon.ui.collapse_command" : "codon.ui.expand_command"), true, false,
            () -> expanded = !expanded).withIcon(expanded ? DebuggerIcon.COLLAPSE : DebuggerIcon.EXPAND);
        right -= 20;
        if (area.width() >= 240) {
            int width = labelWidth("codon.watch.open");
            button("watch", new Bounds(right - width, area.y(), width, 16), Component.translatable("codon.watch.open"),
                true, false, () -> client.gui.setScreen(new WatchScreen(input, state, overlay)));
            right -= width + 3;
        }
        int width = labelWidth("codon.ui.return_current");
        button("current", new Bounds(right - width, area.y(), width, 16), Component.translatable("codon.ui.return_current"),
            !state.isViewingCurrentCommand(), false, () -> { state.selectCurrentCommand(); changed(); });
        right -= width + 3;
        if (right - area.x() >= 38) {
            button("flow-next", new Bounds(right - 16, area.y(), 16, 16), Component.literal("›"),
                state.hasAdjacentExecutionVisit(1), false,
                () -> { state.selectAdjacentExecutionVisit(1); changed(); })
                .setTooltip(Tooltip.create(Component.translatable("codon.ui.next_recorded_command")));
            button("flow-prev", new Bounds(right - 34, area.y(), 16, 16), Component.literal("‹"),
                state.hasAdjacentExecutionVisit(-1), false,
                () -> { state.selectAdjacentExecutionVisit(-1); changed(); })
                .setTooltip(Tooltip.create(Component.translatable("codon.ui.previous_recorded_command")));
            right -= 38;
        }
        return right;
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
        CommandFlowLayout.Content content = CommandFlowLayout.content(snippet, flow);
        List<Part> parts = content.parts();
        if (!content.inline()) {
            parts = new ArrayList<>(parts);
            parts.addFirst(new Part(content.command(), -1));
        }
        List<Part> displayed = parts;
        java.util.function.IntUnaryOperator minimumWidth = index -> {
                int stage = displayed.get(index).stageIndex();
                return stage < 0 ? client.font.width(tr("codon.ui.not_observed")) + 10
                    : client.font.width(counts(flow.stages().get(stage))) + DebuggerIcon.SIZE + 14
                        + (hasWarning(flow.stages().get(stage)) ? 17 : 0);
            };
        java.util.function.IntUnaryOperator leadingInset = index -> {
            int stage = displayed.get(index).stageIndex();
            boolean stopped = state.selectedFlowIndex() == state.pausedFlowIndex()
                && stage == state.pausedFlowStageIndex();
            return stage >= 0 && (stopped || (body.height() < 30 && hasWarning(flow.stages().get(stage))))
                ? DebuggerButton.TEXT_ICON_INSET : 0;
        };
        CommandFlowLayout.Layout layout = CommandFlowLayout.layout(parts, body.width() - 4,
            client.font::width, minimumWidth, leadingInset);
        if (layout.rows() > 1) {
            layout = CommandFlowLayout.layout(parts, body.width() - 4 - DebuggerIcon.SIZE,
                client.font::width, minimumWidth, leadingInset);
        }
        int rowHeight = body.height() < 30 ? 17 : 30;
        int rows = Math.max(1, body.height() / rowHeight);
        maxCommandOffset = Math.max(0, layout.rows() - rows);
        Selection selection = new Selection(snapshot, state.selectedCallFrameIndex(), state.selectedFlowIndex(),
            state.selectedFlowStageIndex(), body.width(), body.height());
        if (!selection.equals(lastSelection)) {
            int selectedStage = state.selectedFlowStageIndex();
            var selectedRows = layout.cells().stream()
                .filter(cell -> displayed.get(cell.partIndex()).stageIndex() == selectedStage)
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
            if (stageIndex >= 0) {
                Runnable reveal = () -> commandOffset = DebuggerOverlay.revealRow(cell.row(), commandOffset, rows, maxCommandOffset);
                navigation.add("clause-" + flow.invocationId() + "-" + stageIndex + "-" + cell.row(),
                    navigationGroup, cell.row() * 2, cell.x(), reveal);
                if (cell.first() && rowHeight >= 30 && hasWarning(flow.stages().get(stageIndex))) {
                    navigation.add("warning-" + flow.invocationId() + "-" + stageIndex,
                        navigationGroup, cell.row() * 2 + 1, cell.x(), reveal);
                }
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
                DebuggerIcon.LINE_WRAP.draw(graphics, x + cell.width(), y + 2, MUTED);
            }
            if (stageIndex >= 0 && flow != null) {
                ExecutionFlowStage stage = flow.stages().get(stageIndex);
                boolean stopped = state.selectedFlowIndex() == state.pausedFlowIndex() && stageIndex == state.pausedFlowStageIndex();
                DebuggerButton clause = button("clause-" + flow.invocationId() + "-" + stage.index() + "-" + cell.row(),
                    new Bounds(x, y, cell.width(), 16), Component.literal(cell.text()), true,
                    stageIndex == state.selectedFlowStageIndex(), () -> {
                        if (state.selectedExecutionFlow() != flow) return;
                        state.selectExecutionFlowStage(stageIndex);
                        changed();
                    });
                if (stopped) clause.withStatusColor(AMBER, AMBER_SURFACE);
                clause.withOpenEdges(!cell.first(), cellIndex + 1 < layout.cells().size()
                    && layout.cells().get(cellIndex + 1).partIndex() == cell.partIndex());
                clause.setTooltip(Tooltip.create(Component.literal(part.text().strip() + "\n" + stageDetails(stage))));
                if (rowHeight < 30 && hasWarning(stage)) clause.withTextIcon(DebuggerIcon.WARNING);
                if (stopped && cell.first()) clause.withTextIcon(DebuggerIcon.PAUSE);
                if (cell.first() && rowHeight >= 30) {
                    String count = counts(stage);
                    drawText(graphics, count, x + 4, y + 20,
                        cell.width() - 5 - (hasWarning(stage) ? 17 : 0), stopped ? AMBER : MUTED);
                    if (hasWarning(stage)) warningButton("warning-" + flow.invocationId() + "-" + stage.index(),
                        new Bounds(x + cell.width() - 17, y + 16, 16, 14), stage);
                }
            } else {
                drawText(graphics, cell.text(), x + 5, y + 4, cell.width() - 10, flow == null ? TEXT : MUTED);
                if (flow != null && cell.first() && rowHeight >= 30) {
                    drawText(graphics, tr("codon.ui.not_observed"), x + 4, y + 20, cell.width() - 5, MUTED);
                }
            }
        }
        scrollbar(graphics, body.x() + body.width() - 1, body.y(), body.height(), commandOffset, maxCommandOffset, rows);
    }

    private void renderRawCommand(GuiGraphicsExtractor graphics, Bounds body, CommandSnippet command, PauseSnapshot snapshot) {
        var lines = client.font.split(ClientFormatting.command(command), Math.max(1, body.width() - 12));
        if (lines.size() > 1) {
            lines = client.font.split(ClientFormatting.command(command),
                Math.max(1, body.width() - 12 - DebuggerIcon.SIZE));
        }
        int rows = Math.max(1, body.height() / 11);
        maxCommandOffset = Math.max(0, lines.size() - rows);
        Selection selection = new Selection(snapshot, state.selectedCallFrameIndex(), state.selectedFlowIndex(), -1,
            body.width(), body.height());
        if (!selection.equals(lastSelection)) { commandOffset = 0; lastSelection = selection; }
        commandOffset = Math.clamp(commandOffset, 0, maxCommandOffset);
        commandBounds = body;
        graphics.fill(body.x(), body.y(), body.x() + 2, body.y() + body.height(),
            state.isViewingCurrentCommand() ? AMBER : MUTED);
        graphics.enableScissor(body.x() + 4, body.y(), body.x() + body.width() - 4, body.y() + body.height());
        for (int i = 0; i < rows && commandOffset + i < lines.size(); i++) {
            graphics.text(client.font, lines.get(commandOffset + i), body.x() + 5, body.y() + i * 11, TEXT, false);
            if (commandOffset + i + 1 < lines.size()) {
                DebuggerIcon.LINE_WRAP.draw(graphics,
                    body.x() + 5 + client.font.width(lines.get(commandOffset + i)), body.y() + i * 11 - 1, MUTED);
            }
        }
        graphics.disableScissor();
        scrollbar(graphics, body.x() + body.width() - 1, body.y(), body.height(), commandOffset, maxCommandOffset, rows);
    }

    private String summary() {
        ExecutionFlowStage stage = state.selectedExecutionFlowStage();
        if (stage == null) return tr("codon.ui.no_flow");
        if (stage.terminal()) {
            String terminal = Component.translatable("codon.ui.terminal_results",
                measuredCount(stage.executionCount()), measuredCount(stage.successCount())).getString();
            return hasFlowWarning() ? terminal + " · " + warningSummary(state.selectedExecutionFlow()) : terminal;
        }
        // Per-clause counts already describe input/output contexts. Keep this fixed row
        // for additional information, rather than repeating those counts below them.
        return hasFlowWarning() ? warningSummary(state.selectedExecutionFlow()) : "";
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
        graphics.text(client.font, clipped, x, y, color, false);
        graphics.disableScissor();
    }

    private void scrollbar(GuiGraphicsExtractor graphics, int x, int y, int height, int offset, int max, int rows) {
        if (max <= 0 || height <= 0) return;
        graphics.fill(x, y, x + 2, y + height, BORDER);
        int thumb = Math.min(height, Math.max(5, height * rows / (rows + max)));
        scrollbars.add("command", false, x, y, height, 2, thumb, offset, max, value -> commandOffset = value);
        int top = y + (height - thumb) * offset / max;
        graphics.fill(x, top, x + 2, top + thumb, TEAL);
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

    private record Selection(PauseSnapshot snapshot, int frame, int flow, int stage, int width, int height) { }
    private record StackSelection(List<CallFrame> frames, int selected, int rows) { }
}
