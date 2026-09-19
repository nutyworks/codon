package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.CallFrame;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.PauseReason;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.SourceLocation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClientExecutionFlowTimelineTest {
    private static final SourceLocation LOCATION = new SourceLocation.Block(new BlockLocation(0, 64, 0, "overworld"));

    @Test
    void conditionalFunctionBodyAppearsBetweenParentConditionAndContinuation() {
        ExecutionFlowTrace parent = flow(0, stage(4, 0, "execute unless function test:condition"),
            stage(8, 3, "run"), stage(12, 4, "say test passed"));
        ExecutionFlowTrace body = flow(1, stage(0, 1, "say condition body"));
        ExecutionFlowTrace result = flow(2, stage(0, 2, "return 0"));
        ClientDebuggerState state = paused(parent, List.of(parent, body, result));

        assertSelected(state, 0, 12);
        assertFalse(state.hasAdjacentExecutionVisit(1), "the child trace was created later but ran before the final say");
        state.selectAdjacentExecutionVisit(-1);
        assertSelected(state, 2, 0);
        assertEquals(-1, state.selectedPauseSourceIndex(), "historical function contexts are not live executors");
        state.selectAdjacentExecutionVisit(-1);
        assertSelected(state, 1, 0);
        state.selectAdjacentExecutionVisit(-1);
        assertSelected(state, 0, 4);
        assertFalse(state.hasAdjacentExecutionVisit(-1));
        state.selectAdjacentExecutionVisit(1);
        assertSelected(state, 1, 0);
        state.selectAdjacentExecutionVisit(1);
        assertSelected(state, 2, 0);
        state.selectAdjacentExecutionVisit(1);
        assertSelected(state, 0, 8);
        assertFalse(state.hasAdjacentExecutionVisit(1));
        state.selectCurrentCommand();
        assertSelected(state, 0, 12);
        assertTrue(state.isViewingCurrentCommand());
        state.selectExecutionFlowStage(0);
        state.selectAdjacentExecutionVisit(1);
        assertSelected(state, 1, 0, "clicking a clause also selects its correct chronological visit");
    }

    @Test
    void repeatedEqualFunctionCallsRetainSeparateVisitsAndReturnPoints() {
        ExecutionFlowTrace parent = flow(40, stage(0, 0, "execute if function test:same"),
            stage(1, 2, "if function test:same"), stage(2, 4, "say ok"));
        ExecutionFlowTrace first = flow(90, stage(0, 1, "return 1"));
        ExecutionFlowTrace second = flow(12, stage(0, 3, "return 1"));
        ClientDebuggerState state = paused(parent, List.of(second, parent, first));

        state.selectAdjacentExecutionVisit(-1);
        assertSelected(state, 12, 0);
        state.selectAdjacentExecutionVisit(-1);
        assertSelected(state, 40, 1);
        state.selectAdjacentExecutionVisit(-1);
        assertSelected(state, 90, 0);
        state.selectAdjacentExecutionVisit(-1);
        assertSelected(state, 40, 0);
        assertFalse(state.hasAdjacentExecutionVisit(-1));
    }

    @Test
    void completionSelectsLastObservedStageRatherThanLastCreatedTrace() {
        ExecutionFlowTrace initial = flow(0, stage(0, 0, "execute unless function test:condition"));
        ClientDebuggerState state = paused(initial, List.of(initial));
        state.applyResume();
        state.applyCompletedExecutionFlows(List.of(flow(0, initial.stages().getFirst(), stage(1, 2, "say passed")),
            flow(1, stage(0, 1, "return 0"))));

        assertSelected(state, 0, 1);
        assertFalse(state.isPaused());
        assertFalse(state.hasAdjacentExecutionVisit(1));
        state.selectAdjacentExecutionVisit(-1);
        assertSelected(state, 1, 0);
    }

    @Test
    void unknownAndMixedOrderDisableChronologicalNavigationInsteadOfGuessingFromTraceStorage() {
        ExecutionFlowTrace first = flow(10, stage(0, -1, "legacy first"));
        ExecutionFlowTrace second = flow(20, stage(0, -1, "legacy second"));
        ClientDebuggerState state = paused(second, List.of(first, flow(30), second));
        state.selectAdjacentExecutionVisit(-1);
        assertSelected(state, 20, 0);
        assertFalse(state.hasAdjacentExecutionVisit(-1));
        assertFalse(state.hasAdjacentExecutionVisit(1));
        state = paused(second, List.of(flow(10, stage(0, 3, "known order")), second));
        assertFalse(state.hasAdjacentExecutionVisit(-1));
        assertFalse(state.hasAdjacentExecutionVisit(1));
    }

    @Test
    void resetDiscardsTheCachedChronologicalPath() {
        ExecutionFlowTrace first = flow(10, stage(0, 0, "first"));
        ExecutionFlowTrace second = flow(20, stage(0, 1, "second"));
        ClientDebuggerState state = paused(second, List.of(first, second));
        assertTrue(state.hasAdjacentExecutionVisit(-1));
        state.reset();
        assertFalse(state.hasAdjacentExecutionVisit(-1));
        assertFalse(state.hasAdjacentExecutionVisit(1));
        assertFalse(state.hasAdjacentExecutionVisit(0));
    }

    private static ClientDebuggerState paused(ExecutionFlowTrace current, List<ExecutionFlowTrace> flows) {
        ExecutionFlowStage stage = current.stages().getLast();
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(new PauseSnapshot(LOCATION, stage.command(), 0,
            List.of(new CallFrame(0, LOCATION, stage.command(), current.invocationId(), stage.index())),
            List.of(), flows, PauseReason.STEP, 1));
        return state;
    }

    private static ExecutionFlowTrace flow(long id, ExecutionFlowStage... stages) {
        return new ExecutionFlowTrace(id, LOCATION, List.of(stages), false);
    }

    private static ExecutionFlowStage stage(int index, long order, String text) {
        return new ExecutionFlowStage(index, CommandSnippet.plain(text), List.of(), List.of(), List.of(), List.of(),
            0, 0, 0, false, 0, 0, true, true, false, order);
    }

    private static void assertSelected(ClientDebuggerState state, long invocation, int stage) {
        assertSelected(state, invocation, stage, "selected chronology position");
    }

    private static void assertSelected(ClientDebuggerState state, long invocation, int stage, String reason) {
        assertEquals(invocation, state.selectedExecutionFlow().invocationId(), reason);
        assertEquals(stage, state.selectedExecutionFlowStage().index(), reason);
    }
}
