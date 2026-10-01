package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClientUnobservedFlowSelectionTest {
    private static final String COMMAND = "execute if function pack:truthy run say truthy returned";
    private static final SourceLocation LOCATION = new SourceLocation.Function(new FunctionLocation(new FunctionId("pack", "condition_calls"), 1));
    private static final int TERMINAL_START = COMMAND.indexOf("say truthy");
    private static final PauseSource SOURCE = new PauseSource(new Vec3d(1, 64, 1), 0, 0, null, "minecraft:overworld");

    @Test void staticSelectionClearsCapturedCallPathAndRecordedSelectionRestoresIt() {
        var state = state();
        var frames = state.displayedCallStack();
        assertFalse(frames.isEmpty());
        state.selectUnobservedExecutionFlowStage(1);
        assertTrue(state.displayedCallStack().isEmpty());
        assertEquals(-1, state.selectedCallFrameIndex());
        state.selectCallFrame(0);
        assertEquals(1, state.selectedUnobservedStageIndex(), "old frame buttons must not remain actionable");
        state.selectExecutionFlowStage(0);
        assertEquals(frames, state.displayedCallStack());
        assertEquals(0, state.selectedCallFrameIndex());
        state.selectUnobservedExecutionFlowStage(1);
        state.selectCurrentCommand();
        assertEquals(frames, state.displayedCallStack());
    }

    @Test void staticSelectionRetainsItsRecordedVisitAcrossRepeatedSelectionsAndPreviewRefresh() {
        var first = pause(1).executionFlows().getFirst().stages().getFirst();
        var parent = new ExecutionFlowTrace(7, LOCATION, List.of(first,
            new ExecutionFlowStage(2, first.command(), List.of(), List.of(), List.of(), List.of(),
                0, 0, 0, false, -1, -1, true, true, false, 2, first.callStack())), false);
        var other = new ExecutionFlowTrace(8, LOCATION, List.of(new ExecutionFlowStage(0, first.command(),
            List.of(), List.of(), List.of(), List.of(), 0, 0, 0, false, -1, -1, true, true, false, 1)), false);
        var tail = new ExecutionFlowTrace(9, LOCATION, List.of(new ExecutionFlowStage(0, first.command(),
            List.of(), List.of(), List.of(), List.of(), 0, 0, 0, false, -1, -1, true, true, false, 3)), false);
        var state = new ClientDebuggerState();
        state.applyPause(new PauseSnapshot(LOCATION, first.command(), 0, first.callStack(), List.of(SOURCE),
            List.of(parent, other, tail), PauseReason.BREAKPOINT, 1));
        long request = state.stagePreviews().begin(LOCATION);
        state.stagePreviews().accept(request, LOCATION, ClientStagePreviewState.Status.READY, COMMAND, spans());
        state.selectExecutionFlowStage(1);
        state.selectUnobservedExecutionFlowStage(1);
        state.selectUnobservedExecutionFlowStage(1);
        state.stagePreviews().begin(LOCATION);
        state.selectAdjacentExecutionVisit(-1);
        assertEquals(8, state.selectedExecutionFlow().invocationId(), "Previous stays before the originating return visit");
        state.selectExecutionFlow(0);
        request = state.stagePreviews().begin(LOCATION);
        state.stagePreviews().accept(request, LOCATION, ClientStagePreviewState.Status.READY, COMMAND, spans());
        state.selectUnobservedExecutionFlowStage(1);
        state.selectAdjacentExecutionVisit(1);
        assertEquals(9, state.selectedExecutionFlow().invocationId(), "Next stays after the originating return visit");
        state.selectExecutionFlow(0);
        state.selectExecutionFlowStage(0);
        state.selectUnobservedExecutionFlowStage(1);
        assertFalse(state.hasAdjacentExecutionVisit(-1), "the first visit does not gain a guessed Previous target");
        state.selectAdjacentExecutionVisit(1);
        assertEquals(8, state.selectedExecutionFlow().invocationId());
    }

    @Test void staticSelectionHasIdentityAndCommandButNoBorrowedLiveEvidence() {
        var state = state();
        assertTrue(state.isViewingCurrentCommand());
        state.selectUnobservedExecutionFlowStage(1);
        assertEquals(1, state.selectedUnobservedStageIndex());
        assertEquals(-1, state.selectedFlowStageIndex());
        assertEquals(new CommandSnippet(COMMAND, TERMINAL_START, COMMAND.length()), state.selectedCommand());
        assertEquals(LOCATION, state.selectedLocation());
        assertNull(state.selectedExecutionFlowStage());
        assertFalse(state.isViewingCurrentCommand());
        assertTrue(state.displayedSources().isEmpty());
        assertTrue(state.displayedFlowContexts().isEmpty());
        assertTrue(state.worldSources().isEmpty());
        assertEquals(-1, state.selectedPauseSourceIndex());
        assertNull(state.nbt().executor());
        assertEquals(1, state.selectedExecutionFlow().stages().size());
        assertTrue(state.isPaused(), "inspection must not change the server's pause");
        state.selectCurrentCommand();
        assertEquals(-1, state.selectedUnobservedStageIndex());
        assertTrue(state.isViewingCurrentCommand());
        assertEquals(List.of(SOURCE), state.displayedSources());
        assertEquals(0, state.selectedPauseSourceIndex());
    }

    @Test void unavailableStaleAndAbsentStaticTargetsCannotBeSelected() {
        var state = state();
        state.selectUnobservedExecutionFlowStage(0);
        state.selectUnobservedExecutionFlowStage(20);
        assertTrue(state.isViewingCurrentCommand());
        long request = state.stagePreviews().begin(LOCATION);
        state.selectUnobservedExecutionFlowStage(1);
        assertEquals(-1, state.selectedUnobservedStageIndex());
        state.stagePreviews().accept(request, LOCATION, ClientStagePreviewState.Status.READY, COMMAND + " changed", spans());
        state.selectUnobservedExecutionFlowStage(1);
        assertTrue(state.isViewingCurrentCommand());
    }

    @Test void previewRefreshCannotReuseAStaleTargetAndNextPauseRestoresActualStop() {
        var state = state();
        state.selectUnobservedExecutionFlowStage(1);
        state.stagePreviews().begin(LOCATION);
        assertEquals(-1, state.selectedUnobservedStageIndex());
        assertTrue(state.displayedSources().isEmpty(), "a stale preview never borrows current contexts");
        state.applyPause(pause(2));
        assertTrue(state.isViewingCurrentCommand());
        assertEquals(-1, state.selectedUnobservedStageIndex());
        assertEquals(0, state.selectedExecutionFlowStage().index());
    }

    private static ClientDebuggerState state() {
        var state = new ClientDebuggerState();
        state.applyPause(pause(1));
        long request = state.stagePreviews().begin(LOCATION);
        state.stagePreviews().accept(request, LOCATION, ClientStagePreviewState.Status.READY, COMMAND, spans());
        return state;
    }

    private static List<ClientStagePreviewState.StageSpan> spans() {
        return List.of(new ClientStagePreviewState.StageSpan(0, 0, TERMINAL_START - 1, false),
            new ClientStagePreviewState.StageSpan(1, TERMINAL_START, COMMAND.length(), true));
    }

    private static PauseSnapshot pause(long id) {
        var command = new CommandSnippet(COMMAND, 0, TERMINAL_START - 1);
        var frame = new CallFrame(0, LOCATION, command, 7, 0);
        var stage = new ExecutionFlowStage(0, command, List.of(new ExecutionFlowContext(1, SOURCE)),
            List.of(), List.of(), List.of(), 1, -1, 0, false, -1, -1, false, false, false, 0, List.of(frame));
        return new PauseSnapshot(LOCATION, command, 0, List.of(frame), List.of(SOURCE),
            List.of(new ExecutionFlowTrace(7, LOCATION, List.of(stage), false)), PauseReason.BREAKPOINT, id);
    }
}
