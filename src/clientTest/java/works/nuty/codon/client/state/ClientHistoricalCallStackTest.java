package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.*;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ClientHistoricalCallStackTest {
    private static final SourceLocation ROOT = new SourceLocation.Block(new BlockLocation(7, -60, 11, "overworld"));
    private static final SourceLocation FUNCTION = new SourceLocation.Function(new FunctionLocation(new FunctionId("demo", "test"), 2));
    private static final PauseSource SOURCE = new PauseSource(new Vec3d(7, -60, 11), 0, 0,
        new EntityRef(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), "Pig"), "overworld");
    private static final CallFrame PARENT = new CallFrame(0, ROOT, CommandSnippet.plain("execute unless function demo:test run say passed"), 10, 2);
    private static final CallFrame CHILD = new CallFrame(1, FUNCTION, CommandSnippet.plain("say 2"), 20, 5);
    private static final CallFrame LIVE = new CallFrame(0, ROOT, CommandSnippet.plain("say later block"), 30, 4);
    private static final List<CallFrame> RECORDED_STACK = List.of(CHILD, PARENT);

    @Test
    void browsingReturnedFunctionShowsItsOwnStackAndSelectingCallersPreservesThatContext() {
        ClientDebuggerState state = state(true, true);
        state.selectExecutionFlow(1);
        assertEquals(RECORDED_STACK, state.displayedCallStack());
        assertEquals(0, state.selectedCallFrameIndex());
        assertEquals(-1, state.selectedFrameIndex());
        assertEquals("say 2", state.selectedCommand().text());
        assertFalse(state.isPausedCallFrame(CHILD));
        assertFalse(state.isPausedCallFrame(PARENT));
        assertFalse(state.isViewingCurrentCommand());
        assertEquals(-1, state.selectedPauseSourceIndex());
        assertNull(state.nbt().executor());

        state.selectCallFrame(1);
        assertEquals(RECORDED_STACK, state.displayedCallStack(), "Inspecting a caller retains the child context");
        assertEquals(1, state.selectedCallFrameIndex());
        assertEquals(10, state.selectedExecutionFlow().invocationId());
        assertEquals(2, state.selectedExecutionFlowStage().index());
        assertEquals(PARENT.command(), state.selectedCommand());
        assertEquals(-1, state.selectedPauseSourceIndex());
        state.selectCallFrame(0);
        assertEquals(20, state.selectedExecutionFlow().invocationId());

        state.selectCurrentCommand();
        assertEquals(List.of(LIVE), state.displayedCallStack());
        assertEquals(0, state.selectedCallFrameIndex());
        assertTrue(state.isPausedCallFrame(LIVE));
        assertTrue(state.isViewingCurrentCommand());
        assertEquals(0, state.selectedPauseSourceIndex());
    }

    @Test
    void evictedCallerStillHasItsRecordedCommandWithoutBorrowingLiveData() {
        ClientDebuggerState state = state(false, true);
        state.selectExecutionFlow(0);
        state.selectCallFrame(1);
        assertEquals(RECORDED_STACK, state.displayedCallStack());
        assertEquals(PARENT.command(), state.selectedCommand());
        assertEquals(PARENT.location(), state.selectedLocation());
        assertNull(state.selectedExecutionFlow());
        assertEquals(List.of(), state.displayedSources());
        assertEquals(-1, state.selectedPauseSourceIndex());
        assertNull(state.nbt().executor());
        state.selectCallFrame(0);
        assertEquals(CHILD.command(), state.selectedCommand());
    }

    @Test
    void unknownHistoricalStackDoesNotFallBackToUnrelatedLiveFrame() {
        ClientDebuggerState state = state(false, false);
        state.selectExecutionFlow(0);
        assertEquals(List.of(), state.displayedCallStack());
        assertEquals(-1, state.selectedCallFrameIndex());
        assertEquals(CHILD.command(), state.selectedCommand());
        assertEquals(-1, state.selectedPauseSourceIndex());
    }

    @Test
    void unknownEarlierStageDoesNotBorrowTheCurrentStageStackOfTheSameInvocation() {
        CallFrame earlier = new CallFrame(0, ROOT, CommandSnippet.plain("execute as @e"), LIVE.invocationId(), 1);
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(pause(List.of(new ExecutionFlowTrace(LIVE.invocationId(), ROOT,
            List.of(stage(earlier, List.of(), 0), stage(LIVE, List.of(LIVE), 1)), false))));
        state.selectExecutionFlowStage(0);
        assertEquals(List.of(), state.displayedCallStack());
        assertFalse(state.isViewingCurrentCommand());
        state.selectCurrentCommand();
        assertEquals(List.of(LIVE), state.displayedCallStack());
    }

    @Test
    void unrecordedStageIdsNeverIdentifyHistoricalFramesAsTheCurrentStop() {
        CallFrame truncatedStop = new CallFrame(0, ROOT, CommandSnippet.plain("say after cap"), 30, -1);
        CallFrame sameValueHistorical = new CallFrame(0, ROOT, CommandSnippet.plain("say after cap"), 30, -1);
        CallFrame differentHistorical = new CallFrame(0, ROOT, CommandSnippet.plain("say earlier after cap"), 30, -1);
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(new PauseSnapshot(ROOT, truncatedStop.command(), 0, List.of(truncatedStop),
            List.of(SOURCE), List.of(), PauseReason.STEP, 1));
        assertTrue(state.isPausedCallFrame(truncatedStop));
        assertFalse(state.isPausedCallFrame(sameValueHistorical), "An unavailable stage ID and equal command are not recorded identity");
        assertFalse(state.isPausedCallFrame(differentHistorical));
    }

    @Test
    void historicalCallerAtAnEarlierStageOfTheLiveInvocationIsNotThePausedFrame() {
        CallFrame continuedParent = new CallFrame(0, ROOT, CommandSnippet.plain("say passed"), 10, 9);
        ExecutionFlowTrace parent = new ExecutionFlowTrace(10, ROOT, List.of(
            stage(PARENT, List.of(PARENT), 0), stage(continuedParent, List.of(continuedParent), 2)), false);
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(new PauseSnapshot(ROOT, continuedParent.command(), 0, List.of(continuedParent), List.of(SOURCE),
            List.of(parent, trace(CHILD, RECORDED_STACK, 1)), PauseReason.STEP, 1));
        state.selectExecutionFlow(1);
        state.selectCallFrame(1);
        assertEquals(0, state.selectedFrameIndex(), "The caller invocation is also present in the current pause");
        assertFalse(state.isPausedCallFrame(PARENT), "Its earlier stage is not the stopped stage");
        assertFalse(state.isViewingCurrentCommand());
        assertEquals(-1, state.selectedPauseSourceIndex(), "A historical caller cannot query the live source even with the same occurrence ID");
        assertNull(state.nbt().executor());
        state.selectCurrentCommand();
        assertEquals(List.of(continuedParent), state.displayedCallStack());
        assertTrue(state.isViewingCurrentCommand());
    }

    @Test
    void completedHistoryKeepsRecordedStacksAndNewPauseRestoresTheLiveStack() {
        ClientDebuggerState state = state(true, true);
        state.applyResume();
        state.applyCompletedExecutionFlows(List.of(trace(CHILD, RECORDED_STACK, 3)));
        assertEquals(RECORDED_STACK, state.displayedCallStack());
        assertFalse(state.isPausedCallFrame(CHILD));
        assertEquals(-1, state.selectedPauseSourceIndex());
        state.applyPause(pause(List.of(trace(LIVE, List.of(LIVE), 4))));
        assertEquals(List.of(LIVE), state.displayedCallStack());
        assertTrue(state.isViewingCurrentCommand());
        state.reset();
        assertEquals(List.of(), state.displayedCallStack());
    }

    private static ClientDebuggerState state(boolean keepParent, boolean captured) {
        ClientDebuggerState state = new ClientDebuggerState();
        ExecutionFlowTrace child = trace(CHILD, captured ? RECORDED_STACK : List.of(), 1);
        ExecutionFlowTrace live = trace(LIVE, List.of(LIVE), 2);
        state.applyPause(pause(keepParent ? List.of(trace(PARENT, List.of(PARENT), 0), child, live) : List.of(child, live)));
        return state;
    }

    private static PauseSnapshot pause(List<ExecutionFlowTrace> flows) {
        return new PauseSnapshot(ROOT, LIVE.command(), 0, List.of(LIVE), List.of(SOURCE), flows, PauseReason.STEP, 1);
    }

    private static ExecutionFlowTrace trace(CallFrame frame, List<CallFrame> stack, long order) {
        return new ExecutionFlowTrace(frame.invocationId(), frame.location(), List.of(stage(frame, stack, order)), false);
    }

    private static ExecutionFlowStage stage(CallFrame frame, List<CallFrame> stack, long order) {
        return new ExecutionFlowStage(frame.flowStageIndex(), frame.command(), List.of(new ExecutionFlowContext(1, SOURCE)),
            List.of(), List.of(), List.of(), 1, 1, 0, true, 1, 1, true, true, false, order, stack);
    }
}
