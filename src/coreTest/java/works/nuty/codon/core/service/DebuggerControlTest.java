package works.nuty.codon.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.FunctionId;
import works.nuty.codon.core.model.FunctionLocation;
import works.nuty.codon.core.model.PauseReason;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.StepMode;
import works.nuty.codon.core.support.ImmediateExecutionController;
import works.nuty.codon.core.support.RecordingEventSink;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DebuggerControlTest {
    private final BreakpointRegistry breakpoints = new BreakpointRegistry();
    private final StepController step = new StepController();
    private final RecordingEventSink sink = new RecordingEventSink();
    private final DebuggerEngine engine = new DebuggerEngine(breakpoints, step, new CallStack(),
        new ImmediateExecutionController(), sink);
    private static final SourceLocation LOCATION = new SourceLocation.Function(
        new FunctionLocation(new FunctionId("test", "control"), 1));

    private void stage(long invocation, int depth) {
        engine.onCommandStage(new CommandStageEvent(invocation, depth, LOCATION,
            CommandSnippet.plain("say control"), List::of));
    }

    private long startPausedExecution() {
        engine.onExecutionStarted();
        breakpoints.toggleFunction(((SourceLocation.Function) LOCATION).location());
        stage(1, 1);
        return engine.currentSnapshot().pauseId();
    }

    @ParameterizedTest
    @EnumSource(StepMode.class)
    void oldRequestCannotAdvanceTheNextPauseButFreshRequestPreservesAction(StepMode action) {
        long firstPause = startPausedExecution();
        assertTrue(engine.control(StepMode.INTO, firstPause));
        stage(2, 1);
        var second = engine.currentSnapshot();
        assertTrue(second.pauseId() > firstPause);

        assertFalse(engine.control(action, firstPause));
        assertTrue(engine.isPaused());
        assertSame(second, engine.currentSnapshot(), "rejected requests must preserve the exact inspection stop");
        assertEquals(StepMode.NONE, step.mode());
        assertEquals(1, sink.steps);
        assertEquals(0, sink.continues);
        assertEquals(0, sink.resumes);

        assertTrue(engine.control(action, second.pauseId()));
        assertFalse(engine.isPaused());
        assertNull(engine.currentSnapshot());
        assertEquals(action, step.mode());
        breakpoints.clear();
        stage(3, action == StepMode.OUT ? 0 : 1);
        if (action == StepMode.NONE) {
            assertFalse(engine.isPaused(), "Continue must not turn into a step");
            assertEquals(1, sink.continues);
        } else {
            assertTrue(engine.isPaused());
            assertEquals(PauseReason.STEP, engine.currentSnapshot().reason());
        }
    }

    @ParameterizedTest
    @EnumSource(StepMode.class)
    void oldRequestCannotReleaseCompletionButFreshRequestCan(StepMode action) {
        long firstPause = startPausedExecution();
        engine.stepOut();
        engine.onExecutionFinished();
        var completed = engine.currentSnapshot();
        assertEquals(PauseReason.EXECUTION_COMPLETE, completed.reason());

        assertFalse(engine.control(action, firstPause));
        assertSame(completed, engine.currentSnapshot());
        assertEquals(0, sink.resumes);
        assertTrue(engine.control(action, completed.pauseId()));
        assertFalse(engine.isPaused());
        assertFalse(step.isStepping());
        assertEquals(1, sink.resumes);
        assertFalse(engine.control(action, completed.pauseId()), "a duplicate cannot execute after release");
    }

    @Test
    void missingFutureAndPreviousSessionIdsCannotAdvanceExecution() {
        assertFalse(engine.control(StepMode.INTO, 1));
        long oldPause = startPausedExecution();
        assertFalse(engine.control(StepMode.INTO, 0));
        assertFalse(engine.control(StepMode.INTO, -1));
        assertFalse(engine.control(StepMode.INTO, oldPause + 1));
        assertTrue(engine.isPaused());
        engine.resetSession();
        engine.onExecutionStarted();
        stage(1, 1);
        var current = engine.currentSnapshot();
        assertTrue(current.pauseId() > oldPause);
        assertFalse(engine.control(StepMode.NONE, oldPause));
        assertSame(current, engine.currentSnapshot());
        assertTrue(engine.control(StepMode.NONE, current.pauseId()));
    }
}
