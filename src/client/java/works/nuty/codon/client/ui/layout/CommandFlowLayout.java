package works.nuty.codon.client.ui.layout;

import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.ExecutionFlowTrace;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;
import java.util.function.ToIntFunction;

/** Minecraft-free command and execution-flow layout shared by the command panel renderer. */
public final class CommandFlowLayout {
    public static final int CELL_HORIZONTAL_PADDING = 6;
    private static final int GAP = 2;

    private CommandFlowLayout() {
    }

    public enum Observation { RECORDED, NOT_EXECUTED, FILTERED_OUT, UNAVAILABLE }

    /** stageIndex is a recorded list position; targetStageIndex is an exact Brigadier stage identity. */
    public record Part(String text, int stageIndex, int targetStageIndex, Observation observation) {
        public Part(String text, int stageIndex) {
            this(text, stageIndex, stageIndex, stageIndex >= 0 ? Observation.RECORDED : Observation.UNAVAILABLE);
        }
    }

    public record Content(String command, List<Part> parts, boolean inline) {
        public Content {
            parts = List.copyOf(parts);
        }
    }

    public record Cell(int partIndex, int row, int x, int width, String text, boolean first) {
    }

    public record Layout(List<Cell> cells, int rows) {
        public Layout {
            cells = List.copyOf(cells);
        }
    }

    /**
     * Turns a trace into command segments only when the recorded ranges form an exact, ordered
     * partition of the displayed command. Otherwise every recorded stage remains visible in a
     * separate fallback list so a renderer never has to infer a correspondence.
     */
    public static Content content(CommandSnippet command, @Nullable ExecutionFlowTrace flow) {
        String text = command.text();
        if (flow == null) return new Content(text, List.of(new Part(text, -1)), true);

        List<ExecutionFlowStage> stages = flow.stages();
        int cursor = 0;
        boolean valid = true;
        for (ExecutionFlowStage stage : stages) {
            CommandSnippet recorded = stage.command();
            if (recorded == null || !text.equals(recorded.text()) || !validRange(recorded, text.length())
                    || recorded.highlightStart() < cursor) {
                valid = false;
                break;
            }
            cursor = recorded.highlightEnd();
        }

        if (valid) {
            List<Part> parts = new ArrayList<>(stages.size() + 2);
            cursor = 0;
            int prefixEnd = executePrefixEnd(text);
            if (prefixEnd > 0 && !stages.isEmpty()
                && stages.getFirst().command().highlightStart() <= prefixEnd
                && stages.getFirst().command().highlightEnd() > prefixEnd) {
                parts.add(new Part(text.substring(0, prefixEnd), -2));
                cursor = prefixEnd;
            }
            for (int index = 0; index < stages.size(); index++) {
                int end = stages.get(index).command().highlightEnd();
                parts.add(new Part(text.substring(cursor, end), index, stages.get(index).index(), Observation.RECORDED));
                cursor = end;
            }
            if (cursor < text.length() || parts.isEmpty()) parts.add(new Part(text.substring(cursor), -1));
            return new Content(text, parts, true);
        }

        List<Part> parts = new ArrayList<>(stages.size());
        for (int index = 0; index < stages.size(); index++) {
            CommandSnippet recorded = stages.get(index).command();
            String recordedText = recorded == null ? "" : recorded.text();
            String stageText = recorded != null && validRange(recorded, recordedText.length())
                ? recordedText.substring(recorded.highlightStart(), recorded.highlightEnd())
                : recordedText;
            parts.add(new Part(stageText, index, stages.get(index).index(), Observation.RECORDED));
        }
        return new Content(text, parts, false);
    }

    /** Adds only server-parsed stages whose command and ranges agree exactly with this recording. */
    public static Content content(CommandSnippet command, ExecutionFlowTrace flow,
                                  ClientStagePreviewState.@Nullable Preview preview) {
        if (preview == null || preview.status() != ClientStagePreviewState.Status.READY
            || !command.text().equals(preview.savedCommand()) || preview.spans().isEmpty()) return content(command, flow);
        String text = command.text();
        List<ClientStagePreviewState.StageSpan> spans = preview.spans();
        int previousEnd = 0;
        for (int index = 0; index < spans.size(); index++) {
            var span = spans.get(index);
            if (span.index() != index || span.start() < previousEnd || span.start() >= span.end()
                || span.end() > text.length() || span.terminal() != (index == spans.size() - 1)) return content(command, flow);
            previousEnd = span.end();
        }
        if (previousEnd != text.length()) return content(command, flow);
        int[] recorded = new int[spans.size()];
        java.util.Arrays.fill(recorded, -1);
        int previousIndex = -1;
        for (int index = 0; index < flow.stages().size(); index++) {
            ExecutionFlowStage stage = flow.stages().get(index);
            if (stage.index() <= previousIndex || stage.index() >= spans.size())
                return content(command, flow);
            var span = spans.get(stage.index());
            if (!text.equals(stage.command().text()) || span.start() != stage.command().highlightStart()
                || span.end() != stage.command().highlightEnd() || span.terminal() != stage.terminal()) return content(command, flow);
            recorded[stage.index()] = index;
            previousIndex = stage.index();
        }
        Observation missing = Observation.NOT_EXECUTED;
        if (flow.truncated() || !flow.warnings().isEmpty()) missing = Observation.UNAVAILABLE;
        else if (!flow.stages().isEmpty()) {
            ExecutionFlowStage last = flow.stages().getLast();
            if (!last.terminal() && last.complete() && last.lineageComplete() && last.outputCount() == 0)
                missing = Observation.FILTERED_OUT;
        }
        List<Part> parts = new ArrayList<>();
        int cursor = executePrefixEnd(text);
        if (spans.getFirst().start() > cursor || spans.getFirst().end() <= cursor) cursor = 0;
        if (cursor > 0) parts.add(new Part(text.substring(0, cursor), -2));
        for (var span : spans) {
            int index = recorded[span.index()];
            parts.add(new Part(text.substring(cursor, span.end()), index, span.index(),
                index >= 0 ? Observation.RECORDED : span.index() < previousIndex ? Observation.UNAVAILABLE : missing));
            cursor = span.end();
        }
        return new Content(text, parts, true);
    }

    /** Keep the command verb outside the first modifier's breakpoint target in the UI. */
    public static int executePrefixEnd(String command) {
        if (command == null || !command.startsWith("execute") || command.length() <= 7
            || !Character.isWhitespace(command.charAt(7))) return 0;
        int end = 8;
        while (end < command.length() && Character.isWhitespace(command.charAt(end))) end++;
        return end < command.length() ? end : 0;
    }

    /**
     * Lays out every command character without ellipsizing. A part can produce several cells;
     * all keep its part index. Only the first cell reserves leading icons and the minimum
     * count-label width; continuation cells use the full viewport with ordinary text padding.
     */
    public static Layout layout(List<Part> parts, int width, ToIntFunction<String> measure,
                                IntUnaryOperator minimumWidth) {
        return layout(parts, width, measure, minimumWidth, ignored -> 0);
    }

    public static Layout layout(List<Part> parts, int width, ToIntFunction<String> measure,
                                IntUnaryOperator minimumWidth, IntUnaryOperator leadingInset) {
        int available = Math.max(0, width);
        if (available == 0 || parts == null || parts.isEmpty()) return new Layout(List.of(), 0);

        List<Cell> cells = new ArrayList<>();
        int row = 0;
        int x = 0;
        for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
            Part part = parts.get(partIndex);
            if (part == null || part.text() == null || part.text().isBlank()) continue;

            int requestedMinimum = Math.max(0, minimumWidth.applyAsInt(partIndex));
            int clampedMinimum = Math.min(available, requestedMinimum);
            int inset = Math.clamp(leadingInset.applyAsInt(partIndex), 0, available);
            int padding = part.stageIndex() == -2 ? 0 : CELL_HORIZONTAL_PADDING;
            List<String> fragments = wrapCharacters(part.text(), Math.max(1, available - padding - inset),
                Math.max(1, available - padding), measure);
            boolean first = true;
            for (String fragment : fragments) {
                int measured = Math.max(0, measure.applyAsInt(fragment));
                int cellWidth = Math.min(available,
                    Math.max(first ? clampedMinimum : 0, saturatedAdd(measured, padding + (first ? inset : 0))));
                if (x > 0 && x + GAP + cellWidth > available) {
                    row++;
                    x = 0;
                } else if (x > 0) {
                    x += GAP;
                }
                cells.add(new Cell(partIndex, row, x, cellWidth, fragment, first));
                x += cellWidth;
                first = false;
            }
        }
        return new Layout(cells, cells.isEmpty() ? 0 : row + 1);
    }

    private static boolean validRange(CommandSnippet command, int length) {
        return command.highlightStart() >= 0 && command.highlightStart() < command.highlightEnd()
            && command.highlightEnd() <= length;
    }

    /** Fill each line by code point, preserving spaces and surrogate pairs instead of backing up to a word. */
    public static List<String> wrapCharacters(String text, int limit, ToIntFunction<String> measure) {
        return wrapCharacters(text, limit, limit, measure);
    }

    private static List<String> wrapCharacters(String text, int firstLimit, int continuationLimit,
                                                ToIntFunction<String> measure) {
        List<String> fragments = new ArrayList<>();
        int codePoints = text.codePointCount(0, text.length());
        int[] boundaries = new int[codePoints + 1];
        for (int index = 1; index <= codePoints; index++) {
            boundaries[index] = text.offsetByCodePoints(boundaries[index - 1], 1);
        }

        int start = 0;
        while (start < codePoints) {
            int limit = start == 0 ? firstLimit : continuationLimit;
            int low = start;
            int high = start + 1;
            // Bracket this line with growing prefixes before binary search. Probing the
            // entire remaining suffix for every narrow line repeatedly measures/copies it.
            while (measure.applyAsInt(text.substring(boundaries[start], boundaries[high])) <= limit) {
                low = high;
                if (high == codePoints) break;
                high += Math.min(high - start, codePoints - high);
            }
            if (low < high) high--;
            while (low < high) {
                int middle = low + (high - low + 1) / 2;
                if (measure.applyAsInt(text.substring(boundaries[start], boundaries[middle])) <= limit) {
                    low = middle;
                } else {
                    high = middle - 1;
                }
            }
            if (low == codePoints) {
                fragments.add(text.substring(boundaries[start]));
                break;
            }
            int end = low == start ? start + 1 : low;
            fragments.add(text.substring(boundaries[start], boundaries[end]));
            start = end;
        }
        return fragments;
    }

    private static int saturatedAdd(int left, int right) {
        return left > Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
    }
}
