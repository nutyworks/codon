package works.nuty.bastion.core.service;

import org.junit.jupiter.api.Test;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.CommandSnippet;
import works.nuty.bastion.core.model.EntityRef;
import works.nuty.bastion.core.model.ExecutionFlowStage;
import works.nuty.bastion.core.model.ExecutionFlowTrace;
import works.nuty.bastion.core.model.PauseSource;
import works.nuty.bastion.core.model.SourceLocation;
import works.nuty.bastion.core.model.Vec3d;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionFlowRecorderTest {
    private final SourceLocation location = new SourceLocation.Block(
        new BlockLocation(0, 64, 0, "minecraft:overworld"));

    @Test
    void recordsObservedBranchesChangesDropsAndTerminalResultsSeparately() {
        ExecutionFlowRecorder recorder = new ExecutionFlowHistory().start(41, location);
        PauseSource root = source("root", 0, "minecraft:overworld");
        long rootId = recorder.createContext(root);

        recorder.beginStage(snippet("execute as @e at @s if entity @s run say ok", 8, 13),
            List.of(rootId), 1, false);
        PauseSource first = source("same-uuid", 0, "minecraft:overworld");
        PauseSource second = source("same-uuid", 4, "minecraft:the_nether");
        long firstId = recorder.addOutput(rootId, first);
        long secondId = recorder.addOutput(rootId, second);
        recorder.finishStage(2, 0);

        assertNotEquals(firstId, secondId, "occurrences stay distinct even with the same entity UUID");
        recorder.beginStage(snippet("execute as @e at @s if entity @s run say ok", 14, 19),
            List.of(firstId, secondId), 2, false);
        PauseSource moved = source("same-uuid", 12, "minecraft:overworld");
        long movedId = recorder.addOutput(firstId, moved);
        recorder.inputDropped(secondId);
        recorder.finishStage(1, 1);

        recorder.beginStage(snippet("execute as @e at @s if entity @s run say ok", 20, 32),
            List.of(movedId), 1, false);
        long passedId = recorder.addOutput(movedId, moved);
        recorder.finishStage(1, 0);

        recorder.beginStage(snippet("execute as @e at @s if entity @s run say ok", 37, 43),
            List.of(passedId), 1, true);
        recorder.executionStarted();
        recorder.executionStarted();
        recorder.executionResult(true);

        ExecutionFlowTrace trace = recorder.snapshot();
        assertEquals(4, trace.stages().size());
        ExecutionFlowStage as = trace.stages().get(0);
        assertEquals(1, as.inputCount());
        assertEquals(2, as.outputCount());
        assertEquals(2, as.edges().size());
        assertEquals(8, as.command().highlightStart());
        assertEquals(13, as.command().highlightEnd());

        ExecutionFlowStage at = trace.stages().get(1);
        assertEquals(2, at.inputCount());
        assertEquals(1, at.outputCount());
        assertEquals(1, at.droppedCount());
        assertEquals(List.of(secondId), at.droppedContextIds());
        assertTrue(at.isDroppedContext(secondId));
        assertEquals(2, at.displayContexts().size(), "output plus the explicitly dropped input are inspectable");
        assertEquals("minecraft:the_nether", at.displayContexts().get(1).source().dimension());

        assertEquals(1, trace.finalContextCount());
        assertEquals(2, trace.executionCount());
        assertEquals(1, trace.successCount());
        assertFalse(trace.truncated());
    }

    @Test
    void distinguishesInProgressGapAndCompletedZeroOutputStages() {
        ExecutionFlowRecorder recorder = new ExecutionFlowHistory().start(9, location);
        PauseSource source = source("one", 0, "minecraft:overworld");
        long input = recorder.createContext(source);
        recorder.beginStage(CommandSnippet.plain("execute if entity @s run say no"), List.of(input), 1, false);

        ExecutionFlowStage inProgress = recorder.snapshot().stages().getFirst();
        assertFalse(inProgress.complete());
        assertTrue(inProgress.lineageComplete());
        assertEquals(List.of(input), inProgress.displayContexts().stream().map(c -> c.id()).toList());

        recorder.inputDropped(input);
        recorder.finishStage(0, 1);
        ExecutionFlowStage completed = recorder.snapshot().stages().getFirst();
        assertTrue(completed.complete());
        assertTrue(completed.isDroppedContext(input));
        assertEquals(0, completed.outputCount());

        recorder.beginStage(CommandSnippet.plain("execute if function test:x run say no"), List.of(), 0, false);
        recorder.abandonStage();
        ExecutionFlowStage gap = recorder.snapshot().stages().get(1);
        assertTrue(gap.complete());
        assertFalse(gap.lineageComplete());
        assertFalse(gap.isDroppedContext(input), "an unobserved continuation is not a condition failure");
    }

    @Test
    void appliesHistoryAndPerInvocationLimitsWithoutLosingAggregateCounts() {
        ExecutionFlowHistory history = new ExecutionFlowHistory();
        for (int invocation = 0; invocation < ExecutionFlowHistory.MAX_TRACES + 2; invocation++) {
            history.start(invocation, location);
        }
        assertEquals(ExecutionFlowHistory.MAX_TRACES, history.snapshot().size());
        assertEquals(2, history.snapshot().getFirst().invocationId());

        ExecutionFlowRecorder recorder = history.start(100, location);
        long first = 0;
        for (int i = 0; i < ExecutionFlowRecorder.MAX_CONTEXTS + 5; i++) {
            long id = recorder.createContext(source("source-" + i, i, "minecraft:overworld"));
            if (i == 0) first = id;
        }
        recorder.beginStage(CommandSnippet.plain("execute as @e run say capped"), List.of(first), 1, false);
        for (int i = 0; i < 20; i++) recorder.addOutput(first, source("output-" + i, i, "minecraft:overworld"));
        recorder.finishStage(20, 0);
        assertTrue(recorder.snapshot().truncated());
        assertEquals(20, recorder.snapshot().stages().getFirst().outputCount());
    }

    @Test
    void successCallbacksCannotExceedActualExecutionAttempts() {
        ExecutionFlowRecorder recorder = new ExecutionFlowHistory().start(7, location);
        long input = recorder.createContext(source("one", 0, "minecraft:overworld"));
        recorder.beginStage(CommandSnippet.plain("say ok"), List.of(input), 1, true);
        recorder.executionResult(true);
        recorder.executionStarted();
        recorder.executionResult(true);
        recorder.executionResult(true);
        assertEquals(1, recorder.snapshot().successCount());
        assertEquals(1, recorder.snapshot().executionCount());
    }

    private static CommandSnippet snippet(String text, int start, int end) {
        return new CommandSnippet(text, start, end);
    }

    private static PauseSource source(String key, double x, String dimension) {
        UUID id = UUID.nameUUIDFromBytes(key.replaceAll("-?\\d+$", "").getBytes());
        return new PauseSource(new Vec3d(x, 64, 0), 0, 0, new EntityRef(id, key), dimension);
    }
}
