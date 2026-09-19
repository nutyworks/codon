package works.nuty.codon.adapter;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.ExecutionFlowWarning.Reason;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.Vec3d;
import works.nuty.codon.core.service.ExecutionFlowHistory;
import works.nuty.codon.core.service.ExecutionFlowRecorder;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandTraceTest {
    @Test
    void deferredCustomContinuationStartsWithFreshOccurrencesAcrossAnExplicitGap() {
        ExecutionFlowHistory history = new ExecutionFlowHistory();
        SourceLocation location = new SourceLocation.Block(
            new BlockLocation(0, 64, 0, "minecraft:overworld"));
        PauseSource original = source(0);
        PauseSource filtered = source(1);
        CommandTrace trace = new CommandTrace(location, history);

        trace.beginStage(CommandSnippet.plain("execute custom_modifier run say ok"),
            List.of(original), false);
        CommandTrace continuation = trace.forkForContinuation();
        trace.abandonStage();
        continuation.beginStage(CommandSnippet.plain("say ok"), List.of(filtered), true);

        var stages = history.snapshot().getFirst().stages();
        assertFalse(stages.getFirst().lineageComplete());
        assertTrue(stages.getFirst().complete());
        assertNotEquals(stages.getFirst().inputs().getFirst().id(),
            stages.getLast().inputs().getFirst().id(),
            "a same-sized continuation must not reuse an unobserved input occurrence");
    }

    @Test
    void returnRunPassesItsRecordedOutputsToTheActualContinuation() {
        ExecutionFlowHistory history = new ExecutionFlowHistory();
        CommandTrace trace = trace(history);
        List<PauseSource> sources = List.of(source(1), source(2));
        trace.beginStage(CommandSnippet.plain("return run say ok"), sources, false);
        trace.forwardContinuation(sources.size(), limit -> sources.subList(0, Math.min(limit, sources.size())));
        CommandTrace continuation = trace.forkForContinuation();
        trace.invocationEnded(false);
        continuation.beginStage(CommandSnippet.plain("say ok"), 2,
            ignored -> { throw new AssertionError("return-run inputs must reuse observed outputs"); }, true);

        var flow = history.snapshot().getFirst();
        var returned = flow.stages().getFirst();
        assertEquals(2, returned.outputCount());
        assertEquals(2, returned.edges().size());
        assertEquals(returned.outputs(), flow.stages().getLast().inputs());
        assertTrue(returned.complete() && returned.lineageComplete());
        assertFalse(flow.truncated());
        assertTrue(flow.warnings().isEmpty());
    }

    @Test
    void deferredCallbacksLinkDuplicateSourcesByOccurrenceEvenWhenResultsArriveOutOfOrder() {
        ExecutionFlowHistory history = new ExecutionFlowHistory();
        CommandTrace trace = trace(history);
        PauseSource sameSource = source(0);
        trace.beginStage(CommandSnippet.plain("execute if function test:condition run say ok"),
            List.of(sameSource, sameSource), false);
        trace.deferStage();
        var first = trace.registerDeferredInput();
        var second = trace.registerDeferredInput();
        CommandTrace continuation = trace.forkForContinuation();
        trace.finishDeferredScheduling();
        trace.invocationEnded(false);
        assertFalse(history.snapshot().getFirst().stages().getFirst().complete());
        assertTrue(history.snapshot().getFirst().warnings().isEmpty());

        second.acceptOutputs(1, limit -> List.of(sameSource));
        first.acceptOutputs(0, limit -> List.of());
        continuation.beginStage(CommandSnippet.plain("say ok"), 1,
            ignored -> { throw new AssertionError("callback output IDs must be reused"); }, true);
        var flow = history.snapshot().getFirst();
        var condition = flow.stages().getFirst();
        assertEquals(1, condition.outputCount());
        assertEquals(1, condition.droppedCount());
        assertEquals(List.of(condition.inputs().getFirst().id()), condition.droppedContextIds());
        assertEquals(condition.inputs().get(1).id(), condition.edges().getFirst().inputContextId());
        assertEquals(condition.outputs(), flow.stages().getLast().inputs());
        assertTrue(condition.complete() && condition.lineageComplete());
        assertTrue(flow.warnings().isEmpty());
        assertFalse(flow.truncated());
    }

    @Test
    void rejectedConditionsAndEmptyFunctionSetsRecordZeroWithoutAGap() {
        ExecutionFlowHistory history = new ExecutionFlowHistory();
        CommandTrace trace = trace(history);
        trace.beginStage(CommandSnippet.plain("execute if function test:false run say skipped"), List.of(source(0)), false);
        trace.deferStage();
        var input = trace.registerDeferredInput();
        CommandTrace continuation = trace.forkForContinuation();
        trace.finishDeferredScheduling();
        trace.invocationEnded(false);
        input.acceptOutputs(0, ignored -> List.of());
        continuation.beginStage(CommandSnippet.plain("say skipped"), List.of(), true);
        var flow = history.snapshot().getFirst();
        assertEquals(0, flow.finalContextCount());
        assertEquals(0, flow.executionCount());
        assertEquals(1, flow.stages().getFirst().droppedCount());
        assertTrue(flow.warnings().isEmpty());

        CommandTrace noFunctions = trace(history);
        noFunctions.beginStage(CommandSnippet.plain("execute if function #test:empty run say skipped"), List.of(source(0)), false);
        noFunctions.deferStage();
        noFunctions.finishDeferredScheduling();
        noFunctions.invocationEnded(false);
        var empty = history.snapshot().getLast();
        assertEquals(0, empty.finalContextCount());
        assertEquals(1, empty.stages().getFirst().droppedCount());
        assertTrue(empty.stages().getFirst().complete());
        assertTrue(empty.warnings().isEmpty());
    }

    @Test
    void unexecutedContinuationHasAnExplicitReasonOnlyAtExecutionEnd() {
        ExecutionFlowHistory history = new ExecutionFlowHistory();
        CommandTrace trace = trace(history);
        trace.beginStage(CommandSnippet.plain("execute if function test:condition run say ok"), List.of(source(0)), false);
        trace.deferStage();
        trace.registerDeferredInput();
        trace.forkForContinuation();
        trace.finishDeferredScheduling();
        trace.invocationEnded(false);
        assertTrue(history.snapshot().getFirst().warnings().isEmpty());
        history.finishExecution();
        var flow = history.snapshot().getFirst();
        assertEquals(List.of(Reason.CONTINUATION_NOT_RESUMED), flow.warnings().stream().map(warning -> warning.reason()).toList());
        assertFalse(flow.stages().getFirst().lineageComplete());
    }

    @Test
    void missingCallbackIsNotReconstructedFromMatchingSources() {
        ExecutionFlowHistory history = new ExecutionFlowHistory();
        CommandTrace trace = trace(history);
        trace.beginStage(CommandSnippet.plain("execute if function test:condition run say ok"), List.of(source(0)), false);
        trace.deferStage();
        trace.registerDeferredInput();
        CommandTrace continuation = trace.forkForContinuation();
        trace.finishDeferredScheduling();
        trace.invocationEnded(false);
        continuation.beginStage(CommandSnippet.plain("say ok"), List.of(source(0)), true);
        var flow = history.snapshot().getFirst();
        assertEquals(Reason.MODIFIER_RESULT_MISMATCH, flow.warnings().getFirst().reason());
        assertFalse(flow.stages().getFirst().lineageComplete());
        assertNotEquals(flow.stages().getFirst().inputs().getFirst().id(), flow.stages().getLast().inputs().getFirst().id());
    }

    @Test
    void deferredOutputCapPreservesActualCountsAndReportsTheConditionStage() {
        ExecutionFlowHistory history = new ExecutionFlowHistory();
        CommandTrace trace = trace(history);
        List<PauseSource> sources = IntStream.range(0, 100).mapToObj(CommandTraceTest::source).toList();
        trace.beginStage(CommandSnippet.plain("execute if function test:condition run say ok"), sources, false);
        trace.deferStage();
        var callbacks = IntStream.range(0, sources.size()).mapToObj(index -> trace.registerDeferredInput()).toList();
        CommandTrace continuation = trace.forkForContinuation();
        trace.finishDeferredScheduling();
        trace.invocationEnded(false);
        for (int i = 0; i < sources.size(); i++) {
            PauseSource source = sources.get(i);
            callbacks.get(i).acceptOutputs(1, limit -> limit == 0 ? List.of() : List.of(source));
        }
        continuation.beginStage(CommandSnippet.plain("say ok"), 100,
            ignored -> { throw new AssertionError("capped IDs are still explicitly linked"); }, true);
        var flow = history.snapshot().getFirst();
        assertEquals(100, flow.finalContextCount());
        assertEquals(28, flow.stages().getFirst().outputs().size());
        assertTrue(flow.stages().getFirst().lineageComplete());
        assertEquals(Reason.CONTEXT_LIMIT, flow.warnings().getFirst().reason());
        assertEquals(0, flow.warnings().getFirst().stageIndex());
        assertEquals(128, flow.warnings().getFirst().limit());
        assertEquals(flow.stages().getFirst().outputs(), flow.stages().getLast().inputs());
    }

    @Test
    void earlyExitsPreserveSpecificErrorsButHandledFailuresDoNotWarn() {
        ExecutionFlowHistory history = new ExecutionFlowHistory();
        CommandTrace trace = trace(history);
        trace.beginStage(CommandSnippet.plain("execute custom run say ok"), List.of(source(0)), false);
        trace.interrupted(Reason.UNSUPPORTED_MODIFIER, -1, "example.CustomModifier");
        trace.invocationEnded(false);
        assertEquals(Reason.UNSUPPORTED_MODIFIER, history.snapshot().getFirst().warnings().getFirst().reason());

        CommandTrace handled = trace(history);
        handled.beginStage(CommandSnippet.plain("execute failing_modifier run say skipped"), List.of(source(0)), false);
        handled.modifierFailed();
        handled.interrupted(Reason.EXECUTION_ERROR, -1, "handled command error");
        handled.beginStage(CommandSnippet.plain("say skipped"), List.of(), true);
        handled.invocationEnded(false);
        assertTrue(history.snapshot().getLast().warnings().isEmpty());
    }

    private static CommandTrace trace(ExecutionFlowHistory history) {
        return new CommandTrace(new SourceLocation.Block(new BlockLocation(0, 64, 0, "minecraft:overworld")), history);
    }

    @Test
    void commandQuotaReportsQueuedReturnEvenThoughItsPassThroughWasFullyRecorded() {
        ExecutionFlowHistory history = new ExecutionFlowHistory();
        CommandTrace.QueueScope scope = CommandTrace.openQueueScope();
        CommandTrace trace = trace(history);
        trace.beginStage(CommandSnippet.plain("return run say never"), List.of(source(0)), false);
        trace.forwardContinuation(1, ignored -> List.of(source(0)));
        trace.forkForContinuation();
        trace.invocationEnded(false);
        assertTrue(history.snapshot().getFirst().stages().getFirst().complete());
        assertTrue(history.snapshot().getFirst().warnings().isEmpty());
        scope.finish(Reason.COMMAND_LIMIT, 1, "Command quota exhausted");
        history.finishExecution();
        var flow = history.snapshot().getFirst();
        assertEquals(1, flow.warnings().size());
        assertEquals(Reason.COMMAND_LIMIT, flow.warnings().getFirst().reason());
        assertEquals(1, flow.warnings().getFirst().limit());
        assertTrue(flow.stages().getFirst().lineageComplete(), "the return outputs themselves were observed");
        assertTrue(flow.truncated());
    }

    @Test
    void evictedDeferredParentIsRepublishedWhenItsQueueEndsAndNestedQueuesCannotFinalizeIt() {
        ExecutionFlowHistory history = new ExecutionFlowHistory();
        CommandTrace.QueueScope outer = CommandTrace.openQueueScope();
        CommandTrace parent = trace(history);
        parent.beginStage(CommandSnippet.plain("execute if function test:long run say never"), List.of(source(0)), false);
        parent.deferStage();
        parent.registerDeferredInput();
        parent.forkForContinuation();
        parent.finishDeferredScheduling();
        CommandTrace.QueueScope inner = CommandTrace.openQueueScope();
        for (int i = 0; i < ExecutionFlowHistory.MAX_TRACES + 1; i++) {
            trace(history).beginStage(CommandSnippet.plain("say nested"), List.of(source(i)), true);
        }
        inner.finish(Reason.COMMAND_LIMIT, 1, "Inner queue exhausted");
        assertTrue(history.snapshot().stream().noneMatch(flow -> flow.invocationId() == parent.id));
        outer.finish(Reason.QUEUE_LIMIT, 10_000_000, "Queue overflow");
        history.finishExecution();
        var flow = history.snapshot().getLast();
        assertEquals(parent.id, flow.invocationId());
        assertEquals(1, flow.warnings().size());
        assertEquals(Reason.QUEUE_LIMIT, flow.warnings().getFirst().reason());
        assertFalse(flow.stages().getFirst().lineageComplete());
        assertEquals(ExecutionFlowHistory.MAX_TRACES, history.snapshot().size());
    }

    @Test
    void completedOrClearedContinuationsAreNotRepublishedAtQueueEnd() {
        ExecutionFlowHistory history = new ExecutionFlowHistory();
        CommandTrace.QueueScope scope = CommandTrace.openQueueScope();
        CommandTrace trace = trace(history);
        trace.beginStage(CommandSnippet.plain("return run say done"), List.of(source(0)), false);
        trace.forwardContinuation(1, ignored -> List.of(source(0)));
        trace.forkForContinuation().beginStage(CommandSnippet.plain("say done"), List.of(source(0)), true);
        scope.finish(Reason.COMMAND_LIMIT, 1, "Quota reached exactly at completion");
        assertTrue(history.snapshot().getFirst().warnings().isEmpty());

        CommandTrace.QueueScope cancelled = CommandTrace.openQueueScope();
        CommandTrace old = trace(history);
        old.beginStage(CommandSnippet.plain("return run say old"), List.of(source(0)), false);
        old.forwardContinuation(1, ignored -> List.of(source(0)));
        old.forkForContinuation();
        history.clear();
        cancelled.finish(Reason.CONTINUATION_NOT_RESUMED, -1, "Debugger stopped");
        assertTrue(history.snapshot().isEmpty());
    }

    @Test
    void largeModifierResultMapsOnlyTheDetailedOccurrenceBudget() {
        ExecutionFlowHistory history = new ExecutionFlowHistory();
        SourceLocation location = new SourceLocation.Block(
            new BlockLocation(0, 64, 0, "minecraft:overworld"));
        CommandTrace trace = new CommandTrace(location, history);
        trace.beginStage(CommandSnippet.plain("execute as @e run say ok"), List.of(source(0)), false);
        trace.modifierReturned();
        AtomicInteger requested = new AtomicInteger(-1);

        trace.acceptModifierOutputs(10_000, limit -> {
            requested.set(limit);
            return IntStream.range(0, limit).mapToObj(CommandTraceTest::source).toList();
        });
        trace.beginStage(CommandSnippet.plain("say ok"), 10_000,
            ignored -> { throw new AssertionError("accepted output occurrences must be reused"); }, true);

        var flow = history.snapshot().getFirst();
        assertEquals(127, requested.get(), "the root occurrence already consumed one detail slot");
        assertEquals(10_000, flow.stages().getFirst().outputCount());
        assertEquals(127, flow.stages().getFirst().outputs().size());
        assertEquals(10_000, flow.finalContextCount());
        assertTrue(flow.truncated());
    }

    @Test
    void capturesTheActualFlowStageIndexAndUsesMinusOneAfterStageLimit() {
        ExecutionFlowHistory history = new ExecutionFlowHistory();
        SourceLocation location = new SourceLocation.Block(
            new BlockLocation(0, 64, 0, "minecraft:overworld"));
        CommandTrace trace = new CommandTrace(location, history);

        for (int index = 0; index < ExecutionFlowRecorder.MAX_STAGES; index++) {
            trace.beginStage(CommandSnippet.plain("say " + index), List.of(source(index)), true);
            assertEquals(index, trace.flowStageIndex());
        }
        trace.beginStage(CommandSnippet.plain("say truncated"), List.of(source(99)), true);

        assertEquals(-1, trace.flowStageIndex());
    }

    private static PauseSource source(double x) {
        return new PauseSource(new Vec3d(x, 64, 0), 0, 0, null, "minecraft:overworld");
    }
}
