package works.nuty.codon.client.ui.layout;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.client.state.ClientStagePreviewState;

import java.util.List;
import java.util.function.IntUnaryOperator;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandFlowLayoutTest {
    private static final String PREVIEW_COMMAND = "execute if function pack:truthy as @a run say truthy returned";

    @Test
    void serverPreviewMakesUnobservedStagesTargetableWithoutInventingRecords() {
        var preview = preview();
        var first = preview.spans().getFirst();
        var flow = trace(new ExecutionFlowStage(0, new CommandSnippet(PREVIEW_COMMAND, first.start(), first.end()),
            List.of(), List.of(), List.of(), List.of(), 1, -1, 0, false, -1, -1, false, false, false));
        var content = CommandFlowLayout.content(CommandSnippet.plain(PREVIEW_COMMAND), flow, preview);
        assertTrue(content.inline());
        assertEquals(PREVIEW_COMMAND, joinParts(content.parts()));
        assertEquals(List.of(-2, 0, -1, -1), content.parts().stream().map(CommandFlowLayout.Part::stageIndex).toList());
        assertEquals(List.of(-2, 0, 1, 2), content.parts().stream().map(CommandFlowLayout.Part::targetStageIndex).toList());
        assertEquals(CommandFlowLayout.Observation.NOT_EXECUTED, content.parts().getLast().observation());
        assertEquals(1, flow.stages().size(), "static preview must not add a measured execution record");
        var layout = CommandFlowLayout.layout(content.parts(), 30, CommandFlowLayoutTest::codePoints,
            ignored -> 0, index -> content.parts().get(index).targetStageIndex() >= 0 ? 15 : 0);
        assertEquals(PREVIEW_COMMAND, joinCells(layout));
        assertGeometry(layout, 30);
        assertTrue(layout.cells().stream().filter(cell -> content.parts().get(cell.partIndex()).targetStageIndex() == 2)
            .allMatch(cell -> content.parts().get(cell.partIndex()).stageIndex() == -1));
    }

    @Test
    void measuredZeroAndMissingRecordingHaveDifferentUnobservedStates() {
        var span = preview().spans().getFirst();
        var first = stage(new CommandSnippet(PREVIEW_COMMAND, span.start(), span.end()));
        var zero = CommandFlowLayout.content(CommandSnippet.plain(PREVIEW_COMMAND), trace(first), preview());
        assertEquals(CommandFlowLayout.Observation.FILTERED_OUT, zero.parts().getLast().observation());
        var incomplete = new ExecutionFlowTrace(0, trace(first).location(), List.of(first), true);
        var unknown = CommandFlowLayout.content(CommandSnippet.plain(PREVIEW_COMMAND), incomplete, preview());
        assertEquals(CommandFlowLayout.Observation.UNAVAILABLE, unknown.parts().getLast().observation());
        assertEquals(2, unknown.parts().getLast().targetStageIndex(), "known static identity remains configurable despite missing measurements");
    }

    @Test
    void staleUnavailableAndInconsistentPreviewsCannotRetargetHistoricalText() {
        var first = stage(new CommandSnippet(PREVIEW_COMMAND, 0, preview().spans().getFirst().end()));
        var flow = trace(first);
        var expected = CommandFlowLayout.content(CommandSnippet.plain(PREVIEW_COMMAND), flow);
        for (var status : List.of(ClientStagePreviewState.Status.LOADING, ClientStagePreviewState.Status.NOT_FOUND,
            ClientStagePreviewState.Status.UNAUTHORIZED, ClientStagePreviewState.Status.INVALID)) {
            assertEquals(expected, CommandFlowLayout.content(CommandSnippet.plain(PREVIEW_COMMAND), flow,
                new ClientStagePreviewState.Preview(status, "", List.of())));
        }
        assertEquals(expected, CommandFlowLayout.content(CommandSnippet.plain(PREVIEW_COMMAND), flow,
            new ClientStagePreviewState.Preview(ClientStagePreviewState.Status.READY, PREVIEW_COMMAND + " changed", preview().spans())));
        var conflicting = trace(stage(new CommandSnippet(PREVIEW_COMMAND, 0, 7)));
        assertEquals(CommandFlowLayout.content(CommandSnippet.plain(PREVIEW_COMMAND), conflicting),
            CommandFlowLayout.content(CommandSnippet.plain(PREVIEW_COMMAND), conflicting, preview()));
        assertEquals(-1, expected.parts().getLast().targetStageIndex());
        assertEquals(CommandFlowLayout.Observation.UNAVAILABLE, expected.parts().getLast().observation());
    }

    private static ClientStagePreviewState.Preview preview() {
        int modifier = PREVIEW_COMMAND.indexOf("as @a");
        int terminal = PREVIEW_COMMAND.indexOf("say truthy");
        return new ClientStagePreviewState.Preview(ClientStagePreviewState.Status.READY, PREVIEW_COMMAND, List.of(
            new ClientStagePreviewState.StageSpan(0, 0, modifier - 1, false),
            new ClientStagePreviewState.StageSpan(1, modifier, terminal - 1, false),
            new ClientStagePreviewState.StageSpan(2, terminal, PREVIEW_COMMAND.length(), true)));
    }

    @Test
    void fillsLinesAcrossWordBoundariesWithoutLosingSpacesOrSplittingUnicode() {
        String command = "ab 😀cd ef";
        assertEquals(List.of("ab 😀", "cd e", "f"),
            CommandFlowLayout.wrapCharacters(command, 4, CommandFlowLayoutTest::codePoints));
        var layout = CommandFlowLayout.layout(List.of(new CommandFlowLayout.Part(command, 2)),
            10, CommandFlowLayoutTest::codePoints, ignored -> 0);
        assertEquals(List.of("ab 😀", "cd e", "f"), layout.cells().stream().map(CommandFlowLayout.Cell::text).toList());
        assertEquals(command, joinCells(layout));
        assertEquals(1, layout.cells().stream().filter(CommandFlowLayout.Cell::first).count());
        assertGeometry(layout, 10);
    }

    @Test
    void reservesIconSpaceWithoutDroppingWrappedCommandText() {
        String command = "tellraw @a abcdefghijklmnopqrstuvwxyz";
        CommandFlowLayout.Layout layout = CommandFlowLayout.layout(
            List.of(new CommandFlowLayout.Part(command, 0)), 40,
            CommandFlowLayoutTest::codePoints, ignored -> 0, ignored -> 14);

        assertEquals(command, joinCells(layout));
        assertTrue(layout.rows() > 1);
        assertTrue(layout.cells().stream().allMatch(cell -> codePoints(cell.text()) + (cell.first() ? 20 : 6) <= cell.width()));
        assertGeometry(layout, 40);
    }

    @Test
    void continuationUsesIconSpaceAndDoesNotReserveTheFirstRowsCountLabel() {
        String text = "x".repeat(60);
        var layout = CommandFlowLayout.layout(List.of(new CommandFlowLayout.Part(text, 0)),
            40, CommandFlowLayoutTest::codePoints, ignored -> 32, ignored -> 14);
        assertEquals(List.of(20, 34, 6), layout.cells().stream().map(cell -> codePoints(cell.text())).toList());
        assertEquals(List.of(40, 40, 12), layout.cells().stream().map(CommandFlowLayout.Cell::width).toList());
        assertEquals(text, joinCells(layout));
        assertGeometry(layout, 40);
    }

    @Test void shortStagesShareANarrowRowWithoutExcessPadding() {
        var layout = CommandFlowLayout.layout(List.of(new CommandFlowLayout.Part("a", 0),
            new CommandFlowLayout.Part("b", 1)), 20, CommandFlowLayoutTest::codePoints, ignored -> 0);
        assertEquals(1, layout.rows(), "Two short stages fit without a needless wrapped row");
        assertEquals(2, layout.cells().get(1).x() - layout.cells().getFirst().width());
        assertEquals("ab", joinCells(layout));
        assertGeometry(layout, 20);
    }

    @Test
    void partitionsAValidTraceWithoutLosingTheUnobservedSuffix() {
        String text = "execute as @e run say hello";
        CommandFlowLayout.Content content = CommandFlowLayout.content(CommandSnippet.plain(text), trace(
            stage(new CommandSnippet(text, 0, 13)),
            stage(new CommandSnippet(text, 14, 17))));

        assertTrue(content.inline());
        assertEquals(text, content.command());
        assertEquals(text, joinParts(content.parts()));
        assertEquals(List.of(-2, 0, 1, -1), content.parts().stream().map(CommandFlowLayout.Part::stageIndex).toList());
        assertEquals("execute ", content.parts().getFirst().text());
        assertEquals("as @e", content.parts().get(1).text());
        assertEquals(" say hello", content.parts().getLast().text());
    }

    @Test
    void malformedOverlappingAndRepeatedRangesKeepEveryRecordedStageInFallback() {
        String text = "execute as @e run say hello";
        CommandFlowLayout.Content content = CommandFlowLayout.content(CommandSnippet.plain(text), trace(
            stage(new CommandSnippet(text, 0, 7)),
            stage(new CommandSnippet(text, 5, 10)),
            stage(new CommandSnippet(text, 5, 10)),
            stage(new CommandSnippet("say different", -1, 2))));

        assertFalse(content.inline());
        assertEquals(text, content.command());
        assertEquals(List.of(0, 1, 2, 3), content.parts().stream().map(CommandFlowLayout.Part::stageIndex).toList());
        assertEquals("execute", content.parts().get(0).text());
        assertEquals("te as", content.parts().get(1).text());
        assertEquals("te as", content.parts().get(2).text());
        assertEquals("say different", content.parts().get(3).text());
    }

    @Test
    void splitsLongUnicodeCommandsAtCodePointBoundariesWithoutLoss() {
        String command = "명령😀이름😀매우긴값그리고더긴명령";
        List<CommandFlowLayout.Part> parts = List.of(new CommandFlowLayout.Part(command, 3));
        CommandFlowLayout.Layout layout = CommandFlowLayout.layout(parts, 20, CommandFlowLayoutTest::codePoints,
            ignored -> 0);

        assertEquals(command, joinCells(layout));
        assertTrue(layout.cells().size() > 1);
        assertTrue(layout.cells().stream().allMatch(cell -> cell.partIndex() == 0));
        assertEquals(1, layout.cells().stream().filter(CommandFlowLayout.Cell::first).count());
        assertGeometry(layout, 20);
    }

    @Test
    void keepsCellsInsideViewportsAndClampsMinimumCountWidths() {
        List<CommandFlowLayout.Part> parts = List.of(
            new CommandFlowLayout.Part("execute as @e", 0),
            new CommandFlowLayout.Part(" run say hello", 1));
        IntUnaryOperator minimum = index -> index == 0 ? 70 : 26;

        for (int width : List.of(320, 190, 20)) {
            CommandFlowLayout.Layout layout = CommandFlowLayout.layout(parts, width,
                CommandFlowLayoutTest::codePoints, minimum);
            assertGeometry(layout, width);
            assertEquals("execute as @e run say hello", joinCells(layout));
            assertTrue(layout.rows() >= 1);
            assertTrue(layout.cells().stream().filter(cell -> cell.partIndex() == 0 && cell.first())
                .allMatch(cell -> cell.width() >= Math.min(width, 70)));
        }
    }

    private static ExecutionFlowTrace trace(ExecutionFlowStage... stages) {
        return new ExecutionFlowTrace(0,
            new SourceLocation.Block(new BlockLocation(0, 64, 0, "minecraft:overworld")),
            List.of(stages), false);
    }

    private static ExecutionFlowStage stage(CommandSnippet command) {
        return new ExecutionFlowStage(0, command, List.of(), List.of(), List.of(), List.of(), 0, 0, 0,
            false, 0, 0, true, true, false);
    }

    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    private static String joinParts(List<CommandFlowLayout.Part> parts) {
        return parts.stream().map(CommandFlowLayout.Part::text).collect(Collectors.joining());
    }

    private static String joinCells(CommandFlowLayout.Layout layout) {
        return layout.cells().stream().map(CommandFlowLayout.Cell::text).collect(Collectors.joining());
    }

    private static void assertGeometry(CommandFlowLayout.Layout layout, int width) {
        assertTrue(layout.cells().stream().allMatch(cell -> cell.x() >= 0 && cell.width() >= 0
            && cell.x() + cell.width() <= width));
        assertEquals(layout.cells().isEmpty() ? 0 : layout.cells().getLast().row() + 1, layout.rows());
    }
}
