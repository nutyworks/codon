package works.nuty.bastion.core.service;

import org.junit.jupiter.api.Test;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.CallFrame;
import works.nuty.bastion.core.model.CommandSnippet;
import works.nuty.bastion.core.model.FunctionId;
import works.nuty.bastion.core.model.FunctionLocation;
import works.nuty.bastion.core.model.PauseReason;
import works.nuty.bastion.core.model.SourceLocation;
import works.nuty.bastion.core.support.ImmediateExecutionController;
import works.nuty.bastion.core.support.RecordingEventSink;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebuggerEngineTest {
    private final BreakpointRegistry breakpoints = new BreakpointRegistry();
    private final StepController step = new StepController();
    private final CallStack callStack = new CallStack();
    private final ImmediateExecutionController controller = new ImmediateExecutionController();
    private final RecordingEventSink sink = new RecordingEventSink();
    private final DebuggerEngine engine =
        new DebuggerEngine(breakpoints, step, callStack, controller, sink);

    private static FunctionLocation tick(int line) {
        return new FunctionLocation(new FunctionId("test", "tick"), line);
    }

    private CommandStageEvent functionStage(int depth, int line) {
        return functionStage(0, depth, line);
    }

    private CommandStageEvent functionStage(long chainId, int depth, int line) {
        return new CommandStageEvent(
            chainId,
            depth,
            new SourceLocation.Function(tick(line)),
            CommandSnippet.plain("say hi"),
            List::of
        );
    }

    @Test
    void doesNothingOnHotPathWhenNoBreakpointsAndNotStepping() {
        engine.onCommandStage(functionStage(0, 1));
        assertFalse(engine.isPaused());
        assertEquals(0, sink.pauses.size());
        assertTrue(engine.callStack().isEmpty(), "call stack untouched on the hot path");
    }

    @Test
    void pausesWhenAFunctionBreakpointIsHit() {
        breakpoints.toggleFunction(tick(3));

        engine.onCommandStage(functionStage(0, 2)); // no match
        assertFalse(engine.isPaused());

        engine.onCommandStage(functionStage(1, 3)); // match
        assertTrue(engine.isPaused());
        assertEquals(1, sink.pauses.size());
        assertEquals(1, controller.parkCount);
        assertEquals(new SourceLocation.Function(tick(3)), sink.lastPause().location());
    }

    @Test
    void pausesWhenABlockBreakpointIsHit() {
        BlockLocation pos = new BlockLocation(10, 64, 10, "minecraft:overworld");
        breakpoints.toggleBlock(pos);

        engine.onCommandStage(new CommandStageEvent(
            0, 0, new SourceLocation.Block(pos), CommandSnippet.plain("setblock ~ ~ ~ stone"), List::of));

        assertTrue(engine.isPaused());
        assertEquals(1, sink.pauses.size());
    }

    @Test
    void resumeClearsPausedAndNotifies() {
        breakpoints.toggleFunction(tick(3));
        engine.onCommandStage(functionStage(0, 3));
        assertTrue(engine.isPaused());

        engine.resume();
        assertFalse(engine.isPaused());
        assertEquals(1, sink.resumes);
    }

    @Test
    void steppingCommandsDoNothingWhenNoExecutionIsPaused() {
        engine.stepInto();
        engine.stepOver();
        engine.stepOut();

        engine.onCommandStage(functionStage(72, 1, 1));

        assertFalse(step.isStepping());
        assertFalse(engine.isPaused());
        assertTrue(sink.pauses.isEmpty());
        assertEquals(0, sink.resumes);
        assertEquals(0, sink.steps);
    }

    @Test
    void repeatedStepsPublishAdvancementWithoutEndingTheCameraSession() {
        engine.onExecutionStarted();
        breakpoints.toggleFunction(tick(3));
        engine.onCommandStage(functionStage(1, 2, 3));
        breakpoints.clear();

        Runnable[] actions = {engine::stepInto, engine::stepOver, engine::stepOut};
        for (int i = 0; i < 6; i++) {
            actions[i % actions.length].run();
            assertFalse(engine.isPaused());
            assertNull(engine.currentSnapshot());
            assertEquals(i + 1, sink.steps);
            assertEquals(0, sink.resumes, "a step must not publish a terminal resume");
            engine.onCommandStage(functionStage(i + 2, i % 3 == 2 ? 1 : 2, i + 4));
            assertTrue(engine.isPaused());
        }

        engine.resume();
        engine.onExecutionFinished();
        assertEquals(1, sink.resumes, "only the final continue ends the camera session");
    }

    @Test
    void stepExhaustionPublishesOneTerminalResumeAtTheOutermostExecutionBoundary() {
        engine.onExecutionStarted();
        engine.onExecutionStarted();
        breakpoints.toggleFunction(tick(3));
        engine.onCommandStage(functionStage(1, 2, 3));
        engine.stepOver();

        engine.onExecutionFinished();
        assertEquals(0, sink.resumes, "finishing an inner queue must retain freecam");
        engine.onExecutionFinished();
        assertEquals(1, sink.steps);
        assertEquals(1, sink.resumes, "exhausting the outer queue must release freecam");
        engine.onTickBoundary();
        engine.resetSession();
        assertEquals(1, sink.resumes, "later cleanup must not publish another resume");
    }

    @Test
    void rootStepOutIsATerminalResume() {
        breakpoints.toggleFunction(tick(3));
        engine.onCommandStage(functionStage(1, 0, 3));
        engine.stepOut();

        assertFalse(step.isStepping());
        assertEquals(0, sink.steps);
        assertEquals(1, sink.resumes);
    }

    @Test
    void resetDuringAStepReleasesTheRetainedCameraSession() {
        assertStepCancellationNotifies(engine::resetSession);
    }

    @Test
    void resumeDuringAStepReleasesTheRetainedCameraSession() {
        assertStepCancellationNotifies(engine::resume);
    }

    @Test
    void tickBoundaryDuringAStepReleasesTheRetainedCameraSession() {
        assertStepCancellationNotifies(engine::onTickBoundary);
    }

    private void assertStepCancellationNotifies(Runnable cancellation) {
        breakpoints.toggleFunction(tick(3));
        engine.onCommandStage(functionStage(1, 2, 3));
        engine.stepInto();
        assertEquals(1, sink.steps);
        assertEquals(0, sink.resumes);

        cancellation.run();
        assertFalse(engine.isPaused());
        assertFalse(step.isStepping());
        assertEquals(1, sink.resumes);
        cancellation.run();
        assertEquals(1, sink.resumes);
    }

    @Test
    void resumeSkipsRemainingStagesOfThePausedChain() {
        breakpoints.toggleFunction(tick(3));
        engine.onCommandStage(functionStage(11, 2, 3));

        engine.resume();
        engine.onCommandStage(functionStage(11, 2, 3)); // continuation of the same chain
        assertFalse(engine.isPaused());
        assertEquals(1, sink.pauses.size(), "the same execute chain must not re-hit its breakpoint");

        engine.onCommandStage(functionStage(12, 2, 3)); // a separate chain may
        assertTrue(engine.isPaused());
    }

    @Test
    void resumeSkipSurvivesInterleavedForeignChains() {
        breakpoints.toggleFunction(tick(3));
        engine.onCommandStage(functionStage(11, 1, 3));

        engine.resume();
        engine.onCommandStage(functionStage(12, 2, 5)); // e.g. the called function's body
        assertFalse(engine.isPaused());

        engine.onCommandStage(functionStage(11, 1, 3)); // fork continuation after the interleaver
        assertFalse(engine.isPaused(), "an interleaved chain must not revive the skipped chain");
        assertEquals(1, sink.pauses.size());
    }

    @Test
    void stepOutSkipsRemainingStagesOfThePausedChain() {
        breakpoints.toggleFunction(tick(3));
        engine.onCommandStage(functionStage(21, 2, 3));

        engine.stepOut();
        engine.onCommandStage(functionStage(21, 2, 3)); // rest of the paused chain
        assertFalse(engine.isPaused());

        engine.onCommandStage(functionStage(22, 1, 5)); // returned to the caller frame
        assertTrue(engine.isPaused());
    }

    @Test
    void stagesArrivingWhilePausedAreIgnored() {
        breakpoints.toggleFunction(tick(3));
        engine.onCommandStage(functionStage(31, 1, 3));
        assertTrue(engine.isPaused());
        List<CallFrame> framesAtPause = engine.callStack().frames();

        // e.g. a console command executing while the command thread is parked
        engine.onCommandStage(functionStage(32, 0, 3));

        assertEquals(1, sink.pauses.size(), "a nested execution must not pause again");
        assertEquals(framesAtPause, engine.callStack().frames(), "a nested execution must not touch the stack");
        assertTrue(engine.isPaused());
    }

    @Test
    void tickBoundaryClearsSkippedChainsAndStaleFrames() {
        breakpoints.toggleFunction(tick(3));
        engine.onCommandStage(functionStage(41, 1, 3));
        engine.resume();

        engine.onTickBoundary();

        assertTrue(engine.callStack().isEmpty(), "frames from finished executions are dropped");
        engine.onCommandStage(functionStage(41, 1, 3));
        assertTrue(engine.isPaused(), "skip bookkeeping does not outlive the tick");
    }

    @Test
    void executionFinishedClearsSkippedChainsStepAndFrames() {
        breakpoints.toggleFunction(tick(3));
        engine.onCommandStage(functionStage(42, 2, 3));

        engine.stepOver();
        assertTrue(step.isStepping());
        engine.onExecutionFinished();

        assertFalse(step.isStepping());
        assertTrue(engine.callStack().isEmpty());
        engine.onCommandStage(functionStage(42, 2, 3));
        assertTrue(engine.isPaused(), "a later execution may use the former chain ID");
    }

    @Test
    void tickBoundaryClearsAStepThatCannotLandInTheFinishedExecution() {
        breakpoints.toggleFunction(tick(3));
        engine.onCommandStage(functionStage(43, 2, 3));
        breakpoints.clear();

        engine.stepOver();
        engine.onTickBoundary();
        engine.onCommandStage(functionStage(44, 2, 4));

        assertFalse(engine.isPaused());
        assertFalse(step.isStepping());
    }

    @Test
    void pauseAtABreakpointReportsBreakpointReason() {
        breakpoints.toggleFunction(tick(3));
        engine.onCommandStage(functionStage(0, 3));
        assertEquals(PauseReason.BREAKPOINT, sink.lastPause().reason());
    }

    @Test
    void pauseFromASteppingRequestReportsStepReason() {
        breakpoints.toggleFunction(tick(3));
        engine.onCommandStage(functionStage(0, 3));
        breakpoints.clear();

        engine.stepInto();
        engine.onCommandStage(functionStage(1, 4));
        assertEquals(PauseReason.STEP, sink.lastPause().reason());
    }

    @Test
    void breakpointMutationsNotifyTheSink() {
        engine.toggleFunctionBreakpoint(tick(3));
        engine.toggleBlockBreakpoint(new BlockLocation(0, 0, 0, "minecraft:overworld"));
        engine.clearBreakpoints();
        assertEquals(3, sink.breakpointChanges);
    }

    @Test
    void currentSnapshotIsRetainedWhilePausedAndClearedOnResume() {
        breakpoints.toggleFunction(tick(3));
        assertNull(engine.currentSnapshot());

        engine.onCommandStage(functionStage(0, 3));
        assertNotNull(engine.currentSnapshot());
        assertEquals(new SourceLocation.Function(tick(3)), engine.currentSnapshot().location());

        engine.resume();
        assertNull(engine.currentSnapshot());
    }

    @Test
    void stepIntoPausesAtTheNextStageEvenWithoutBreakpoints() {
        // Arrive at a pause via a breakpoint first.
        breakpoints.toggleFunction(tick(3));
        engine.onCommandStage(functionStage(0, 3));
        breakpoints.clear();

        engine.stepInto();
        assertFalse(engine.isPaused());

        // Next stage has no breakpoint, but step-into still pauses.
        engine.onCommandStage(functionStage(1, 4));
        assertTrue(engine.isPaused());
        assertEquals(2, sink.pauses.size());
    }

    @Test
    void stepIntoCanStopAtTheNextStageOfTheSameCommandChain() {
        breakpoints.toggleFunction(tick(3));
        engine.onCommandStage(functionStage(88, 1, 3));
        breakpoints.clear();

        engine.stepInto();
        engine.onCommandStage(functionStage(88, 1, 4));

        assertTrue(engine.isPaused());
        assertEquals(PauseReason.STEP, sink.lastPause().reason());
    }

    @Test
    void stepOverDoesNotPauseInsideDeeperFrames() {
        breakpoints.toggleFunction(tick(3));
        engine.onCommandStage(functionStage(90, 2, 3)); // pause at depth 2
        breakpoints.clear();

        engine.stepOver(); // target depth 2

        engine.onCommandStage(functionStage(91, 3, 1)); // deeper call -> no pause
        assertFalse(engine.isPaused());

        engine.onCommandStage(functionStage(92, 2, 4)); // back at depth 2 -> pause
        assertTrue(engine.isPaused());
    }

    @Test
    void rootDepthStepOutSimplyResumesWithoutAnUnreachableStepRequest() {
        breakpoints.toggleFunction(tick(3));
        engine.onCommandStage(functionStage(93, 0, 3));
        breakpoints.clear();

        engine.stepOut();

        assertFalse(engine.isPaused());
        assertFalse(step.isStepping());
        engine.onCommandStage(functionStage(94, 0, 4));
        assertFalse(engine.isPaused());
    }

    @Test
    void resetSessionDropsPauseAndExecutionStateButKeepsBreakpoints() {
        breakpoints.toggleFunction(tick(3));
        engine.onCommandStage(functionStage(95, 1, 3));
        assertTrue(engine.isPaused());

        engine.resetSession();

        assertFalse(engine.isPaused());
        assertNull(engine.currentSnapshot());
        assertFalse(step.isStepping());
        assertTrue(engine.callStack().isEmpty());
        engine.onCommandStage(functionStage(96, 1, 3));
        assertTrue(engine.isPaused(), "configured breakpoints survive a server-session reset");
    }

    @Test
    void cancelledParkCleansUpTheEngineState() {
        controller.result = works.nuty.bastion.core.port.ExecutionController.ParkResult.CANCELLED;
        breakpoints.toggleFunction(tick(3));

        engine.onCommandStage(functionStage(97, 1, 3));

        assertFalse(engine.isPaused());
        assertNull(engine.currentSnapshot());
        assertFalse(step.isStepping());
        assertTrue(engine.callStack().isEmpty());
    }

    @Test
    void failedParkCleansUpTheEngineStateBeforePropagating() {
        controller.failure = new IllegalStateException("server stopped");
        breakpoints.toggleFunction(tick(3));

        IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> engine.onCommandStage(functionStage(98, 1, 3))
        );

        assertEquals("server stopped", failure.getMessage());
        assertFalse(engine.isPaused());
        assertNull(engine.currentSnapshot());
        assertTrue(engine.callStack().isEmpty());
    }
}
