package works.nuty.codon.client.ui.layout;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.SourceLocation;

import java.util.List;
import java.util.function.IntUnaryOperator;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandFlowLayoutTest {
    @Test
    void partitionsAValidTraceWithoutLosingTheUnobservedSuffix() {
        String text = "execute as @e run say hello";
        CommandFlowLayout.Content content = CommandFlowLayout.content(CommandSnippet.plain(text), trace(
            stage(new CommandSnippet(text, 0, 13)),
            stage(new CommandSnippet(text, 14, 17))));

        assertTrue(content.inline());
        assertEquals(text, content.command());
        assertEquals(text, joinParts(content.parts()));
        assertEquals(List.of(0, 1, -1), content.parts().stream().map(CommandFlowLayout.Part::stageIndex).toList());
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
            assertTrue(layout.cells().stream().filter(cell -> cell.partIndex() == 0)
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
