package works.nuty.codon.core.service;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.CallFrame;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.EntityRef;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.ExecutionFlowWarning;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertTrue(as.isCreatedContext(firstId), "each output of a one-to-many branch is created");
        assertTrue(as.isCreatedContext(secondId), "each output of a one-to-many branch is created");

        ExecutionFlowStage at = trace.stages().get(1);
        assertEquals(2, at.inputCount());
        assertEquals(1, at.outputCount());
        assertEquals(1, at.droppedCount());
        assertEquals(List.of(secondId), at.droppedContextIds());
        assertTrue(at.isDroppedContext(secondId));
        assertFalse(at.isCreatedContext(movedId), "a one-to-one source change does not create a branch");
        assertFalse(at.isBranchedContext(movedId), "one-to-one movement is not a created branch");
        assertTrue(at.isChangedContext(movedId), "one-to-one movement changes the recorded source");
        assertEquals(2, at.displayContexts().size(), "output plus the explicitly dropped input are inspectable");
        assertEquals("minecraft:the_nether", at.displayContexts().get(1).source().dimension());

        ExecutionFlowStage condition = trace.stages().get(2);
        assertNotEquals(movedId, passedId, "the recorder still distinguishes consecutive occurrences");
        assertFalse(condition.isCreatedContext(passedId), "an unchanged condition output is not a new source");

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
        assertEquals(ExecutionFlowStage.UNMEASURED, inProgress.outputCount());
        assertTrue(inProgress.lineageComplete());
        assertEquals(List.of(input), inProgress.displayContexts().stream().map(c -> c.id()).toList());

        recorder.inputDropped(input);
        recorder.finishStage(0, 1);
        ExecutionFlowStage completed = recorder.snapshot().stages().getFirst();
        assertTrue(completed.complete());
        assertTrue(completed.isDroppedContext(input));
        assertEquals(0, completed.outputCount());

        recorder.beginStage(CommandSnippet.plain("return run say ok"), List.of(input), 1, false);
        recorder.abandonStage();
        ExecutionFlowStage gap = recorder.snapshot().stages().get(1);
        assertTrue(gap.complete());
        assertFalse(gap.lineageComplete());
        assertEquals(1, gap.inputCount());
        assertEquals(ExecutionFlowStage.UNMEASURED, gap.outputCount());
        assertFalse(gap.isDroppedContext(input), "an unobserved continuation is not a condition failure");
    }

    @Test
    void distinguishesUnmeasuredTerminalResultsFromObservedFailuresAndSuccesses() {
        ExecutionFlowRecorder recorder = new ExecutionFlowHistory().start(10, location);
        assertEquals(ExecutionFlowStage.UNMEASURED, recorder.snapshot().finalContextCount());
        assertEquals(ExecutionFlowStage.UNMEASURED, recorder.snapshot().executionCount());
        assertEquals(ExecutionFlowStage.UNMEASURED, recorder.snapshot().successCount());
        long input = recorder.createContext(source("one", 0, "minecraft:overworld"));
        recorder.beginStage(CommandSnippet.plain("say ok"), List.of(input), 1, true);
        assertEquals(1, recorder.snapshot().finalContextCount());
        assertEquals(ExecutionFlowStage.UNMEASURED, recorder.snapshot().executionCount());
        assertEquals(ExecutionFlowStage.UNMEASURED, recorder.snapshot().successCount());

        recorder.executionStarted();
        assertEquals(1, recorder.snapshot().executionCount());
        assertEquals(ExecutionFlowStage.UNMEASURED, recorder.snapshot().successCount());
        recorder.executionResult(false);
        assertEquals(0, recorder.snapshot().successCount());

        recorder.executionStarted();
        recorder.executionResult(true);
        assertEquals(2, recorder.snapshot().executionCount());
        assertEquals(1, recorder.snapshot().successCount());
    }

    @Test
    void terminalWithoutInputsHasMeasuredZeroResults() {
        ExecutionFlowRecorder recorder = new ExecutionFlowHistory().start(10, location);
        recorder.beginStage(CommandSnippet.plain("say skipped"), List.of(), 0, true);
        assertEquals(0, recorder.snapshot().finalContextCount());
        assertEquals(0, recorder.snapshot().executionCount());
        assertEquals(0, recorder.snapshot().successCount());
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
        assertEquals(ExecutionFlowStage.UNMEASURED, recorder.snapshot().successCount());
        recorder.executionStarted();
        recorder.executionResult(true);
        recorder.executionResult(true);
        assertEquals(1, recorder.snapshot().successCount());
        assertEquals(1, recorder.snapshot().executionCount());
    }

    @Test
    void assignsChronologicalOrdersAcrossNestedAndRepeatedInvocations() {
        ExecutionFlowHistory history = new ExecutionFlowHistory();
        ExecutionFlowRecorder parent = history.start(11, location);
        parent.beginStage(CommandSnippet.plain("execute if function test:child run say parent"), List.of(), 0, false);
        parent.finishStage(0, 0);

        ExecutionFlowRecorder child = history.start(12, location);
        child.beginStage(CommandSnippet.plain("say child first"), List.of(), 0, true);
        child.beginStage(CommandSnippet.plain("say child second"), List.of(), 0, true);

        parent.beginStage(CommandSnippet.plain("say parent"), List.of(), 0, true);

        ExecutionFlowRecorder repeatedIdSibling = history.start(12, location);
        repeatedIdSibling.beginStage(CommandSnippet.plain("say sibling"), List.of(), 0, true);

        List<ExecutionFlowTrace> traces = history.snapshot();
        assertEquals(List.of(12L, 11L, 12L), traces.stream().map(ExecutionFlowTrace::invocationId).toList(),
            "history retains recently observed recorder identities even when invocation IDs repeat");
        assertEquals(List.of(0L, 3L), traces.get(1).stages().stream()
            .map(ExecutionFlowStage::observationOrder).toList());
        assertEquals(List.of(1L, 2L), traces.get(0).stages().stream()
            .map(ExecutionFlowStage::observationOrder).toList());
        assertEquals(4L, traces.get(2).stages().getFirst().observationOrder());
    }

    @Test
    void clearStartsANewObservationLifetimeWithoutLettingOldRecordersConsumeIt() {
        ExecutionFlowHistory history = new ExecutionFlowHistory();
        ExecutionFlowRecorder oldRecorder = history.start(1, location);
        oldRecorder.beginStage(CommandSnippet.plain("say before clear"), List.of(), 0, true);

        history.clear();
        ExecutionFlowRecorder currentRecorder = history.start(2, location);
        currentRecorder.beginStage(CommandSnippet.plain("say new first"), List.of(), 0, true);
        oldRecorder.beginStage(CommandSnippet.plain("say old continuation"), List.of(), 0, true);
        currentRecorder.beginStage(CommandSnippet.plain("say new second"), List.of(), 0, true);

        assertEquals(List.of(0L, 1L), oldRecorder.snapshot().stages().stream()
            .map(ExecutionFlowStage::observationOrder).toList());
        assertEquals(List.of(0L, 1L), currentRecorder.snapshot().stages().stream()
            .map(ExecutionFlowStage::observationOrder).toList());
        assertEquals(List.of(2L), history.snapshot().stream().map(ExecutionFlowTrace::invocationId).toList());
    }

    @Test
    void anEvictedParentContinuationKeepsTheOriginalHistoryCounter() {
        ExecutionFlowHistory history = new ExecutionFlowHistory();
        ExecutionFlowRecorder parent = history.start(1, location);
        long root = parent.createContext(source("parent", 0, "minecraft:overworld"));
        parent.beginStage(CommandSnippet.plain("execute if function test:child run say parent"), List.of(root), 1, true);
        for (int index = 0; index < ExecutionFlowHistory.MAX_TRACES; index++) {
            ExecutionFlowRecorder child = history.start(100 + index, location);
            child.beginStage(CommandSnippet.plain("say child " + index), List.of(), 0, true);
        }

        parent.beginStage(CommandSnippet.plain("say resumed parent"), List.of(root), 1, true);
        history.recordCallStack(1, 1, List.of(
            new CallFrame(0, location, CommandSnippet.plain("say resumed parent"), 1, 1)));

        List<ExecutionFlowStage> parentStages = parent.snapshot().stages();
        assertEquals(List.of(0L, (long) ExecutionFlowHistory.MAX_TRACES + 1), parentStages.stream()
            .map(ExecutionFlowStage::observationOrder).toList());
        assertEquals(1, parentStages.getLast().inputCount());
        assertEquals(1, parentStages.getLast().outputCount());
        assertEquals(1, parentStages.getLast().callStack().size());
        List<ExecutionFlowTrace> traces = history.snapshot();
        assertEquals(ExecutionFlowHistory.MAX_TRACES, traces.size());
        assertEquals(1L, traces.getLast().invocationId());
        assertEquals(parentStages, traces.getLast().stages());
    }

    @Test
    void retainsImmutableHistoricalStacksAndBoundsOverflow() {
        ExecutionFlowHistory history = new ExecutionFlowHistory();
        ExecutionFlowRecorder recorder = history.start(44, location);
        recorder.beginStage(CommandSnippet.plain("say stack"), List.of(), 0, true);
        List<CallFrame> mutable = new ArrayList<>(List.of(
            new CallFrame(0, location, CommandSnippet.plain("say stack"), 44, 0)));
        history.recordCallStack(44, 0, mutable);
        mutable.clear();

        ExecutionFlowStage saved = recorder.snapshot().stages().getFirst();
        assertEquals(1, saved.callStack().size());
        assertThrows(UnsupportedOperationException.class, () -> saved.callStack().clear());

        ExecutionFlowRecorder overflow = history.start(45, location);
        overflow.beginStage(CommandSnippet.plain("say overflow"), List.of(), 0, true);
        List<CallFrame> frames = new ArrayList<>();
        frames.add(new CallFrame(0, location, CommandSnippet.plain("say overflow"), 45, 0));
        for (int depth = 1; depth <= ExecutionFlowRecorder.MAX_STACK_FRAMES; depth++) {
            frames.add(new CallFrame(depth, location, CommandSnippet.plain("say older"), -1, -1));
        }
        history.recordCallStack(45, 0, frames);

        ExecutionFlowTrace overflowTrace = overflow.snapshot();
        assertEquals(ExecutionFlowRecorder.MAX_STACK_FRAMES, overflowTrace.stages().getFirst().callStack().size());
        assertTrue(overflowTrace.stages().getFirst().truncated());
        assertTrue(overflowTrace.truncated());
        assertEquals(ExecutionFlowWarning.Reason.CALL_STACK_LIMIT, overflowTrace.warnings().getFirst().reason());
    }

    @Test
    void recordsSpecificWarningsOnceAndAssignsPreStageContextOverflowToTheNextStage() {
        ExecutionFlowRecorder recorder = new ExecutionFlowHistory().start(46, location);
        for (int index = 0; index <= ExecutionFlowRecorder.MAX_CONTEXTS; index++)
            recorder.createContext(source("source-" + index, index, "minecraft:overworld"));
        CommandSnippet command = CommandSnippet.plain("execute as @e run say capped");
        recorder.beginStage(command, List.of(1L), 1, false);
        recorder.markTruncated(ExecutionFlowWarning.Reason.CONTEXT_LIMIT, ExecutionFlowRecorder.MAX_CONTEXTS,
            "Context detail limit reached");
        recorder.markTruncated(ExecutionFlowWarning.Reason.CONTEXT_LIMIT, ExecutionFlowRecorder.MAX_CONTEXTS,
            "Context detail limit reached");

        ExecutionFlowTrace trace = recorder.snapshot();
        assertEquals(1, trace.warnings().size());
        ExecutionFlowWarning warning = trace.warnings().getFirst();
        assertEquals(ExecutionFlowWarning.Reason.CONTEXT_LIMIT, warning.reason());
        assertEquals(0, warning.stageIndex());
        assertEquals(command, warning.command());
        assertEquals(ExecutionFlowRecorder.MAX_CONTEXTS, warning.limit());
    }

    @Test
    void finishExecutionExplainsAnUnresumedDeferredStage() {
        ExecutionFlowRecorder recorder = new ExecutionFlowHistory().start(47, location);
        recorder.beginStage(CommandSnippet.plain("return run say later"), List.of(), 0, false);
        recorder.deferStage();
        recorder.finishExecution();

        ExecutionFlowTrace trace = recorder.snapshot();
        assertFalse(trace.stages().getFirst().lineageComplete());
        assertEquals(ExecutionFlowWarning.Reason.CONTINUATION_NOT_RESUMED, trace.warnings().getFirst().reason());
    }

    @Test
    void flagsInvalidInputIdsEvenWhenRetainedInputsMatchTheAggregateCount() {
        ExecutionFlowRecorder recorder = new ExecutionFlowHistory().start(48, location);
        long input = recorder.createContext(source("input", 0, "minecraft:overworld"));
        recorder.beginStage(CommandSnippet.plain("execute at @s run say ok"), List.of(input, 0L), 1, false);

        ExecutionFlowTrace trace = recorder.snapshot();
        assertEquals(1, trace.stages().getFirst().inputs().size());
        assertEquals(ExecutionFlowWarning.Reason.MISSING_CONTEXT, trace.warnings().getFirst().reason());
    }

    private static CommandSnippet snippet(String text, int start, int end) {
        return new CommandSnippet(text, start, end);
    }

    private static PauseSource source(String key, double x, String dimension) {
        UUID id = UUID.nameUUIDFromBytes(key.replaceAll("-?\\d+$", "").getBytes());
        return new PauseSource(new Vec3d(x, 64, 0), 0, 0, new EntityRef(id, key), dimension);
    }
}
