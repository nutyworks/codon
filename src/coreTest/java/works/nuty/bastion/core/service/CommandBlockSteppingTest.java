package works.nuty.bastion.core.service;

import org.junit.jupiter.api.Test;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.CommandSnippet;
import works.nuty.bastion.core.model.FunctionId;
import works.nuty.bastion.core.model.FunctionLocation;
import works.nuty.bastion.core.model.PauseReason;
import works.nuty.bastion.core.model.SourceLocation;
import works.nuty.bastion.core.port.ExecutionController;
import works.nuty.bastion.core.support.ImmediateExecutionController;
import works.nuty.bastion.core.support.RecordingEventSink;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandBlockSteppingTest {
    private final BreakpointRegistry breakpoints = new BreakpointRegistry();
    private final StepController step = new StepController();
    private final ImmediateExecutionController controller = new ImmediateExecutionController();
    private final RecordingEventSink sink = new RecordingEventSink();
    private final DebuggerEngine engine = new DebuggerEngine(
        breakpoints, step, new CallStack(), controller, sink
    );

    private static BlockLocation block(int x) {
        return new BlockLocation(x, 64, 0, "minecraft:overworld");
    }

    private static CommandStageEvent blockStage(long chainId, int depth, BlockLocation block) {
        return new CommandStageEvent(
            chainId,
            depth,
            new SourceLocation.Block(block),
            CommandSnippet.plain("say block"),
            List::of
        );
    }

    private static CommandStageEvent functionStage(long chainId, int depth, int line) {
        return new CommandStageEvent(
            chainId,
            depth,
            new SourceLocation.Function(new FunctionLocation(new FunctionId("test", "child"), line)),
            CommandSnippet.plain("say function"),
            List::of
        );
    }

    private void pauseAtFirstBlock(BlockLocation first) {
        pauseAtFirstBlock(first, true);
    }

    private void pauseAtFirstBlock(BlockLocation first, boolean clearBreakpoint) {
        breakpoints.toggleBlock(first);
        engine.onCommandStage(blockStage(1, 0, first));
        assertTrue(engine.isPaused());
        assertEquals(PauseReason.BREAKPOINT, sink.lastPause().reason());
        if (clearBreakpoint) {
            breakpoints.clear();
        }
    }

    @Test
    void stepIntoSurvivesInnerQueueCompletionAndStopsAtTheNextBlock() {
        BlockLocation first = block(1);
        BlockLocation second = block(2);
        engine.onExecutionStarted();
        engine.onExecutionStarted();
        pauseAtFirstBlock(first);

        engine.stepInto();
        engine.onExecutionFinished();
        engine.onExecutionStarted(); // a connected command block has a fresh queue
        engine.onCommandStage(blockStage(2, 0, second));

        assertTrue(engine.isPaused());
        assertEquals(new SourceLocation.Block(second), sink.lastPause().location());
        assertEquals(PauseReason.STEP, sink.lastPause().reason());
    }

    @Test
    void stepOverSurvivesInnerQueueCompletionAndStopsAtTheNextBlock() {
        BlockLocation first = block(1);
        BlockLocation second = block(2);
        engine.onExecutionStarted();
        engine.onExecutionStarted();
        pauseAtFirstBlock(first);

        engine.stepOver();
        engine.onExecutionFinished();
        engine.onExecutionStarted();
        engine.onCommandStage(blockStage(2, 0, second));

        assertTrue(engine.isPaused());
        assertEquals(new SourceLocation.Block(second), sink.lastPause().location());
        assertEquals(PauseReason.STEP, sink.lastPause().reason());
    }

    @Test
    void stepIntoFromACommandBlockEntersAFunctionFrame() {
        BlockLocation first = block(1);
        engine.onExecutionStarted();
        engine.onExecutionStarted();
        pauseAtFirstBlock(first);

        engine.stepInto();
        engine.onCommandStage(functionStage(2, 1, 7));

        assertTrue(engine.isPaused());
        assertEquals(new SourceLocation.Function(new FunctionLocation(new FunctionId("test", "child"), 7)),
            sink.lastPause().location());
        assertEquals(PauseReason.STEP, sink.lastPause().reason());
    }

    @Test
    void stepOverSkipsFunctionDepthAndLandsAtTheFollowingBlock() {
        BlockLocation first = block(1);
        BlockLocation second = block(2);
        engine.onExecutionStarted();
        engine.onExecutionStarted();
        pauseAtFirstBlock(first);

        engine.stepOver();
        engine.onCommandStage(functionStage(2, 1, 7));
        assertFalse(engine.isPaused());
        engine.onExecutionFinished();
        engine.onExecutionStarted();
        engine.onCommandStage(blockStage(3, 0, second));

        assertTrue(engine.isPaused());
        assertEquals(new SourceLocation.Block(second), sink.lastPause().location());
        assertEquals(PauseReason.STEP, sink.lastPause().reason());
    }

    @Test
    void stepOutFromAFunctionLandsAtTheFollowingBlockAfterItsQueueEnds() {
        BlockLocation first = block(1);
        BlockLocation second = block(2);
        engine.onExecutionStarted();
        engine.onExecutionStarted();
        pauseAtFirstBlock(first);

        engine.stepInto();
        engine.onCommandStage(functionStage(2, 1, 7));
        assertTrue(engine.isPaused());

        engine.stepOut();
        engine.onExecutionFinished();
        engine.onExecutionStarted();
        engine.onCommandStage(blockStage(3, 0, second));

        assertTrue(engine.isPaused());
        assertEquals(new SourceLocation.Block(second), sink.lastPause().location());
        assertEquals(PauseReason.STEP, sink.lastPause().reason());
    }

    @Test
    void rootStepOutStopsAtOuterCompletionAndLeavesTheNextChainAlone() {
        BlockLocation first = block(1);
        BlockLocation second = block(2);
        engine.onExecutionStarted();
        engine.onExecutionStarted();
        pauseAtFirstBlock(first);

        engine.stepOut();
        engine.onExecutionFinished();
        assertFalse(engine.isPaused(), "an inner queue cannot complete the root step-out");
        engine.onExecutionFinished();
        assertTrue(engine.isPaused());
        assertEquals(PauseReason.EXECUTION_COMPLETE, sink.lastPause().reason());
        engine.resume();
        engine.onExecutionStarted();
        engine.onCommandStage(blockStage(2, 0, second));

        assertFalse(engine.isPaused());
        assertFalse(step.isStepping());
    }

    @Test
    void outerCompletionClearsStepBeforeAnUnrelatedScopeInTheSameTick() {
        BlockLocation first = block(1);
        BlockLocation unrelated = block(99);
        engine.onExecutionStarted();
        engine.onExecutionStarted();
        pauseAtFirstBlock(first);

        engine.stepOver();
        engine.onCommandStage(functionStage(2, 1, 7));
        engine.onExecutionFinished();
        assertTrue(step.isStepping());
        engine.onExecutionFinished();

        assertTrue(engine.isPaused());
        assertEquals(PauseReason.EXECUTION_COMPLETE, sink.lastPause().reason());
        engine.resume();

        engine.onExecutionStarted();
        engine.onExecutionStarted();
        engine.onCommandStage(blockStage(3, 0, unrelated));

        assertFalse(engine.isPaused());
        assertFalse(step.isStepping());
    }

    @Test
    void tickBoundaryDoesNotClearStepDuringAnActiveScope() {
        BlockLocation first = block(1);
        BlockLocation second = block(2);
        engine.onExecutionStarted();
        engine.onExecutionStarted();
        pauseAtFirstBlock(first);

        engine.stepOver();
        engine.onTickBoundary();
        engine.onCommandStage(functionStage(2, 1, 7));
        assertFalse(engine.isPaused());
        engine.onExecutionFinished();
        engine.onExecutionStarted();
        engine.onCommandStage(blockStage(3, 0, second));

        assertTrue(engine.isPaused());
        assertEquals(new SourceLocation.Block(second), sink.lastPause().location());
        assertEquals(PauseReason.STEP, sink.lastPause().reason());
    }

    @Test
    void resetSessionDuringNestedScopesDoesNotPoisonTheNextSession() {
        BlockLocation first = block(1);
        engine.onExecutionStarted();
        engine.onExecutionStarted();
        pauseAtFirstBlock(first, false);

        engine.resetSession();
        engine.onExecutionFinished();
        engine.onExecutionFinished();

        engine.onExecutionStarted();
        engine.onExecutionStarted();
        engine.onCommandStage(blockStage(2, 0, first));

        assertTrue(engine.isPaused());
        assertEquals(new SourceLocation.Block(first), sink.lastPause().location());
        assertEquals(PauseReason.BREAKPOINT, sink.lastPause().reason());
    }

    @Test
    void cancellationDuringNestedScopesDoesNotPoisonTheNextSession() {
        BlockLocation first = block(1);
        controller.result = ExecutionController.ParkResult.CANCELLED;
        engine.onExecutionStarted();
        engine.onExecutionStarted();
        breakpoints.toggleBlock(first);
        engine.onCommandStage(blockStage(1, 0, first));

        assertFalse(engine.isPaused());
        assertNull(engine.currentSnapshot());
        engine.onExecutionFinished();
        engine.onExecutionFinished();

        controller.result = ExecutionController.ParkResult.RESUMED;
        engine.onExecutionStarted();
        engine.onExecutionStarted();
        engine.onCommandStage(blockStage(2, 0, first));

        assertTrue(engine.isPaused());
        assertEquals(new SourceLocation.Block(first), sink.lastPause().location());
    }
}
