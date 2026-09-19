package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.CallFrame;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.EntityRef;
import works.nuty.codon.core.model.ExecutionFlowContext;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.PauseReason;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.Vec3d;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Selection contracts for call-stack and execution-flow views carrying modern identity metadata. */
class ClientCommandSelectionTest {
    private static final SourceLocation LOCATION = new SourceLocation.Block(new BlockLocation(4, 70, 9, "overworld"));

    @Test
    void pauseUsesTheTopFramesExactInvocationAndRecordedNonSequentialStage() {
        CommandSnippet stopped = CommandSnippet.plain("execute as @e run say stopped");
        PauseSnapshot pause = snapshot(stopped, 41, List.of(
            frame(0, stopped, 0, 40),
            frame(1, CommandSnippet.plain("function recurse"), 71, 3)), List.of(
            flow(71, stage(3, "wrong latest", true)),
            flow(0, stage(4, "old stage", true), stage(40, "actual stop", false))));
        ClientDebuggerState state = new ClientDebuggerState();

        state.applyPause(pause);

        assertEquals(0, state.selectedFrameIndex());
        assertEquals(1, state.pausedFlowIndex());
        assertEquals(1, state.pausedFlowStageIndex());
        assertEquals(0, state.selectedExecutionFlow().invocationId(), "ID zero is a valid invocation identity");
        assertEquals(40, state.selectedExecutionFlowStage().index(), "do not guess from the final completed stage");
        assertSame(state.selectedExecutionFlowStage().command(), state.selectedCommand(),
            "the recorded stage command takes precedence over the frame's source command");
        assertTrue(state.isViewingCurrentCommand());
    }

    @Test
    void stackAndFlowNavigationFollowInvocationIdentityAcrossRecursiveEqualCommands() {
        CommandSnippet recursive = CommandSnippet.plain("function recurse");
        PauseSnapshot pause = snapshot(recursive, 42, List.of(
            frame(0, recursive, 101, 10),
            frame(1, recursive, 202, 30)), List.of(
            flow(101, stage(10, "outer branch", false)),
            flow(202, stage(30, "inner branch", false))));
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(pause);

        state.selectFrame(1);
        assertEquals(1, state.selectedFrameIndex());
        assertEquals(202, state.selectedExecutionFlow().invocationId());
        assertEquals(30, state.selectedExecutionFlowStage().index());

        state.selectExecutionFlow(0);
        assertEquals(0, state.selectedFrameIndex());
        assertEquals(101, state.selectedExecutionFlow().invocationId());
        state.selectExecutionFlowStage(0);
        assertEquals(0, state.selectedFrameIndex());
        assertEquals(10, state.selectedExecutionFlowStage().index());
    }

    @Test
    void unavailableOrTruncatedFlowNeverBorrowsAnotherInvocationsCommand() {
        CommandSnippet truncatedCommand = CommandSnippet.plain("function partial");
        CommandSnippet missingCommand = CommandSnippet.plain("function missing");
        PauseSnapshot pause = snapshot(CommandSnippet.plain("say stop"), 43, List.of(
            frame(0, CommandSnippet.plain("say stop"), 11, 0),
            frame(1, truncatedCommand, 22, 8),
            frame(2, missingCommand, 33, 9)), List.of(
            flow(11, stage(0, "live", false)),
            new ExecutionFlowTrace(22, LOCATION, List.of(stage(3, "partial", false)), true)));
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(pause);

        state.selectFrame(1);
        assertEquals(1, state.selectedFrameIndex());
        assertEquals(-1, state.selectedFlowStageIndex());
        assertSame(truncatedCommand, state.selectedCommand());

        state.selectFrame(2);
        assertEquals(2, state.selectedFrameIndex());
        assertEquals(-1, state.selectedFlowStageIndex());
        assertSame(missingCommand, state.selectedCommand());
    }

    @Test
    void currentCommandRestoresTheActualStopAfterHistoricalFlowBrowsingAndOnANewPause() {
        CommandSnippet current = CommandSnippet.plain("execute at @s run say current");
        PauseSnapshot first = snapshot(current, 44, List.of(frame(0, current, 500, 17)), List.of(
            flow(400, stage(2, "completed history", true)),
            flow(500, stage(17, "actual stop", false))));
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(first);

        state.selectExecutionFlow(0);
        assertFalse(state.isViewingCurrentCommand());
        state.selectCurrentCommand();
        assertEquals(0, state.selectedFrameIndex());
        assertEquals(500, state.selectedExecutionFlow().invocationId());
        assertEquals(17, state.selectedExecutionFlowStage().index());
        assertTrue(state.isViewingCurrentCommand());

        CommandSnippet next = CommandSnippet.plain("execute positioned ~ ~ ~ run say next");
        state.applyPause(snapshot(next, 45, List.of(frame(0, next, 600, 91)), List.of(
            flow(400, stage(2, "old history", true)),
            flow(600, stage(91, "new stop", false)))));
        assertEquals(600, state.selectedExecutionFlow().invocationId());
        assertEquals(91, state.selectedExecutionFlowStage().index());
        assertSame(state.selectedExecutionFlowStage().command(), state.selectedCommand());
    }

    @Test
    void livePauseSourceRequiresTheCurrentOccurrenceButAllowsItsImmediateOutput() {
        PauseSource live = source("live", 1);
        ExecutionFlowContext oldOccurrence = new ExecutionFlowContext(100, live);
        ExecutionFlowContext currentOccurrence = new ExecutionFlowContext(200, live);
        ExecutionFlowStage oldest = stage(5, CommandSnippet.plain("execute as @s"),
            List.of(), List.of(oldOccurrence), true);
        ExecutionFlowStage earlier = stage(7, CommandSnippet.plain("positioned as @s"),
            List.of(oldOccurrence), List.of(currentOccurrence), true);
        ExecutionFlowStage pending = stage(9, CommandSnippet.plain("if entity @s"),
            List.of(currentOccurrence), List.of(), false);
        PauseSnapshot pause = new PauseSnapshot(LOCATION, pending.command(), 0,
            List.of(frame(0, pending.command(), 700, 9), frame(1, CommandSnippet.plain("function upper"), 800, 1)),
            List.of(live), List.of(
                flow(800, stage(1, CommandSnippet.plain("old invocation"), List.of(new ExecutionFlowContext(300, live)), List.of(), false)),
                new ExecutionFlowTrace(700, LOCATION, List.of(oldest, earlier, pending), false)), PauseReason.STEP, 46);
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(pause);

        assertEquals(0, state.selectedPauseSourceIndex());
        assertEquals(live.entity(), state.nbt().executor());
        state.selectExecutionFlow(1);
        state.selectExecutionFlowStage(0);
        state.selectSource(0);
        assertEquals(-1, state.selectedPauseSourceIndex(), "a same-valued earlier occurrence is not the live pause input");
        assertNull(state.nbt().executor());

        state.selectCurrentCommand();
        assertEquals(0, state.selectedPauseSourceIndex());
        assertEquals(live.entity(), state.nbt().executor());
        state.selectWorldSource(0);
        assertEquals(0, state.selectedPauseSourceIndex(), "the pending stage may expose its immediately preceding output");

        state.selectExecutionFlow(0);
        assertEquals(-1, state.selectedPauseSourceIndex(), "the same source value in another invocation is historical");
        assertNull(state.nbt().executor());
        state.applyResume();
        state.selectExecutionFlow(0);
        assertEquals(-1, state.selectedPauseSourceIndex());
        assertNull(state.nbt().executor(), "flow-only browsing after resume cannot reactivate Watch or NBT requests");
    }

    @Test
    void truncatedCurrentContextRecordingKeepsAllAuthoritativePauseSourcesSelectable() {
        List<PauseSource> sources = java.util.stream.IntStream.range(0, 150)
            .mapToObj(i -> source("source-" + i, i)).toList();
        List<ExecutionFlowContext> retained = java.util.stream.IntStream.range(0, 128)
            .mapToObj(i -> new ExecutionFlowContext(i + 1, sources.get(i))).toList();
        CommandSnippet command = CommandSnippet.plain("say many sources");
        ExecutionFlowStage partial = new ExecutionFlowStage(5, command, retained, List.of(), List.of(), List.of(),
            150, 0, 0, false, 0, 0, false, true, false);
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(new PauseSnapshot(LOCATION, command, 0, List.of(frame(0, command, 991, 5)), sources,
            List.of(new ExecutionFlowTrace(991, LOCATION, List.of(partial), true)), PauseReason.STEP, 83));

        assertNull(state.worldSourceStage());
        assertEquals(sources, state.displayedSources());
        assertEquals(sources, state.worldSources());
        state.selectSource(149);
        assertEquals(149, state.selectedPauseSourceIndex());
        assertEquals(sources.get(149).entity(), state.nbt().executor());
        assertTrue(state.isViewingCurrentCommand());
    }

    @Test
    void executionCompletePauseUsesFreshSourcesInsteadOfPreCommandRecordedValues() {
        PauseSource recorded = source("before", 1);
        PauseSource fresh = source("after", 2);
        CommandSnippet command = CommandSnippet.plain("tp @s 2 64 0");
        ExecutionFlowStage old = stage(0, command, List.of(new ExecutionFlowContext(1, recorded)), List.of(), true);
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(new PauseSnapshot(LOCATION, command, 0, List.of(frame(0, command, 992, 0)), List.of(fresh),
            List.of(flow(992, old)), PauseReason.EXECUTION_COMPLETE, 84));

        assertEquals(List.of(fresh), state.worldSources());
        assertEquals(0, state.selectedPauseSourceIndex());
        assertEquals(fresh.entity(), state.nbt().executor());
    }

    private static PauseSnapshot snapshot(CommandSnippet command, long pauseId, List<CallFrame> frames,
                                          List<ExecutionFlowTrace> flows) {
        return new PauseSnapshot(LOCATION, command, 0, frames, List.of(), flows, PauseReason.STEP, pauseId);
    }

    private static CallFrame frame(int depth, CommandSnippet command, long invocationId, int stageIndex) {
        return new CallFrame(depth, LOCATION, command, invocationId, stageIndex);
    }

    private static ExecutionFlowTrace flow(long invocationId, ExecutionFlowStage... stages) {
        return new ExecutionFlowTrace(invocationId, LOCATION, List.of(stages), false);
    }

    private static ExecutionFlowStage stage(int index, String command, boolean complete) {
        return stage(index, CommandSnippet.plain(command), List.of(), List.of(), complete);
    }

    private static ExecutionFlowStage stage(int index, CommandSnippet command, List<ExecutionFlowContext> inputs,
                                            List<ExecutionFlowContext> outputs, boolean complete) {
        return new ExecutionFlowStage(index, command, inputs, outputs, List.of(), List.of(), inputs.size(),
            outputs.size(), 0, false, 0, 0, complete, true, false);
    }

    private static PauseSource source(String name, int x) {
        return new PauseSource(new Vec3d(x, 64, 0), 0, 0,
            new EntityRef(UUID.nameUUIDFromBytes(name.getBytes()), name), "overworld");
    }
}
