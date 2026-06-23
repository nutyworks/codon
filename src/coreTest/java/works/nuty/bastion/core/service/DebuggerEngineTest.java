package works.nuty.bastion.core.service;

import org.junit.jupiter.api.Test;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.CommandSnippet;
import works.nuty.bastion.core.model.FunctionId;
import works.nuty.bastion.core.model.FunctionLocation;
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
        return new CommandStageEvent(
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
            0, new SourceLocation.Block(pos), CommandSnippet.plain("setblock ~ ~ ~ stone"), List::of));

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
    void stepOverDoesNotPauseInsideDeeperFrames() {
        breakpoints.toggleFunction(tick(3));
        engine.onCommandStage(functionStage(2, 3)); // pause at depth 2
        breakpoints.clear();

        engine.stepOver(); // target depth 2

        engine.onCommandStage(functionStage(3, 1)); // deeper call -> no pause
        assertFalse(engine.isPaused());

        engine.onCommandStage(functionStage(2, 4)); // back at depth 2 -> pause
        assertTrue(engine.isPaused());
    }
}
