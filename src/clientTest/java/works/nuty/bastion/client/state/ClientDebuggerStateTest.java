package works.nuty.bastion.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.CallFrame;
import works.nuty.bastion.core.model.CommandSnippet;
import works.nuty.bastion.core.model.EntityRef;
import works.nuty.bastion.core.model.ExecutionFlowContext;
import works.nuty.bastion.core.model.ExecutionFlowEdge;
import works.nuty.bastion.core.model.ExecutionFlowStage;
import works.nuty.bastion.core.model.ExecutionFlowTrace;
import works.nuty.bastion.core.model.PauseReason;
import works.nuty.bastion.core.model.PauseSnapshot;
import works.nuty.bastion.core.model.PauseSource;
import works.nuty.bastion.core.model.SourceLocation;
import works.nuty.bastion.core.model.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientDebuggerStateTest {
    @Test
    void repeatedStepsClearStaleSnapshotsButRetainSelectionForTheNextPause() {
        ClientDebuggerState state = new ClientDebuggerState();
        PauseSource selected = source("selected", 2);
        List<PauseSource> sources = List.of(source("other", 1), selected);
        state.applyPause(snapshot(sources, 2));
        state.selectSource(1);
        for (int i = 0; i < 6; i++) {
            state.selectFrame(1);
            assertTrue(state.beginControlRequest());
            state.applyStep();
            assertTrue(state.isStepping());
            assertFalse(state.isPaused());
            assertNull(state.snapshot());
            assertFalse(state.controlPending());
            assertFalse(state.beginControlRequest(), "cannot issue another step before the next pause");
            assertNull(state.selectedSource());
            assertEquals(0, state.selectedFrameIndex());

            state.applyPause(snapshot(sources, 2));
            assertFalse(state.isStepping());
            assertTrue(state.isPaused());
            assertSame(selected, state.selectedSource());
        }
    }

    @Test
    void terminalResumeAndDisconnectClearAnInFlightStep() {
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(snapshot(List.of(source("one", 1)), 1));
        state.applyStep();
        state.applyResume();
        assertFalse(state.isStepping());
        assertFalse(state.isPaused());
        assertNull(state.snapshot());

        state.applyPause(snapshot(List.of(source("two", 2)), 1));
        state.applyStep();
        state.reset();
        assertFalse(state.isStepping());
        assertFalse(state.isPaused());
        assertNull(state.snapshot());
    }

    @Test
    void acceptsOnlyValidSelectionsAndResetsFrameForEveryAuthoritativePause() {
        ClientDebuggerState state = new ClientDebuggerState();
        PauseSnapshot first = snapshot(List.of(source("one", 1)), 3);
        state.applyPause(first);

        state.selectSource(9);
        state.selectFrame(9);
        assertEquals(0, state.selectedSourceIndex());
        assertEquals(0, state.selectedFrameIndex());

        state.selectFrame(2);
        state.selectSource(0);
        state.applyPause(snapshot(List.of(source("two", 2)), 4));

        assertEquals(0, state.selectedSourceIndex());
        assertEquals(0, state.selectedFrameIndex());
    }

    @Test
    void preservesExactSelectedSourceAcrossSnapshotReordering() {
        PauseSource first = source("first", 1);
        PauseSource second = source("second", 2);
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(snapshot(List.of(first, second), 1));
        state.selectSource(1);

        state.applyPause(snapshot(List.of(second, first), 1));

        assertEquals(0, state.selectedSourceIndex());
        assertSame(second, state.selectedSource());
    }

    @Test
    void resumeThenPauseDoesNotInferSelectionFromEntityIdentity() {
        UUID id = UUID.randomUUID();
        PauseSource old = source(id, "overworld", 1);
        PauseSource replacement = new PauseSource(new Vec3d(40, 70, -5), 30, 45,
                new EntityRef(id, "changed-name"), "overworld");
        PauseSource other = source("other", 2);
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(snapshot(List.of(other, old), 1));
        state.selectSource(1);
        state.applyResume();

        state.applyPause(snapshot(List.of(other, replacement), 1));

        assertEquals(0, state.selectedSourceIndex());
        assertSame(other, state.selectedSource());
    }

    @Test
    void ambiguousEntityMatchDoesNotRestoreAnArbitrarySource() {
        UUID id = UUID.randomUUID();
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(snapshot(List.of(source("other", 1), source(id, "overworld", 2)), 1));
        state.selectSource(1);
        state.applyResume();

        state.applyPause(snapshot(List.of(
                source(id, "overworld", 3), source(id, "overworld", 4)), 1));

        assertEquals(0, state.selectedSourceIndex());
    }

    @Test
    void emptyPauseAndResetClearPresentationStateAndCopyBreakpoints() {
        ClientDebuggerState state = new ClientDebuggerState();
        List<BlockLocation> sent = new ArrayList<>(List.of(new BlockLocation(1, 2, 3, "overworld")));
        state.applyBreakpoints(sent);
        sent.clear();
        state.applyPause(snapshot(List.of(), 1));

        assertEquals(-1, state.selectedSourceIndex());
        assertNull(state.selectedSource());
        assertEquals(1, state.blockBreakpoints().size());

        state.reset();

        assertFalse(state.isPaused());
        assertNull(state.snapshot());
        assertEquals(-1, state.selectedSourceIndex());
        assertEquals(0, state.selectedFrameIndex());
        assertTrue(state.blockBreakpoints().isEmpty());
        assertFalse(state.controlPending());
    }

    @Test
    void resetPreservesClientPreferences() {
        DebuggerPreferences preferences = new DebuggerPreferences();
        preferences.setGizmoMode(ClientDebuggerState.GizmoMode.FOCUS);
        preferences.setInspectorVisible(true);
        preferences.setInspectorTab(DebuggerPreferences.InspectorTab.STACK);
        ClientDebuggerState state = new ClientDebuggerState(preferences);

        state.reset();

        assertSame(preferences, state.preferences());
        assertEquals(ClientDebuggerState.GizmoMode.FOCUS, state.gizmoMode());
        assertEquals(Boolean.TRUE, preferences.inspectorVisible());
        assertEquals(DebuggerPreferences.InspectorTab.STACK, preferences.inspectorTab());
    }

    @Test
    void preferencesNotifyOnlyWhenTheirValuesChange() {
        DebuggerPreferences preferences = new DebuggerPreferences();
        AtomicInteger changes = new AtomicInteger();
        preferences.setChangeListener(changes::incrementAndGet);

        preferences.setGizmoMode(ClientDebuggerState.GizmoMode.GROUPED);
        preferences.setInspectorVisible(null);
        preferences.setInspectorTab(DebuggerPreferences.InspectorTab.SOURCES);
        assertEquals(0, changes.get());

        preferences.setGizmoMode(ClientDebuggerState.GizmoMode.LABELS);
        preferences.setInspectorVisible(false);
        preferences.setInspectorTab(DebuggerPreferences.InspectorTab.DETAILS);
        assertEquals(3, changes.get());

        preferences.setGizmoMode(ClientDebuggerState.GizmoMode.LABELS);
        preferences.setInspectorVisible(false);
        preferences.setInspectorTab(DebuggerPreferences.InspectorTab.DETAILS);
        assertEquals(3, changes.get());
    }

    @Test
    void controlRequestRequiresPauseAndClearsOnlyOnAcknowledgementOrTimeout() {
        AtomicLong now = new AtomicLong();
        ClientDebuggerState state = new ClientDebuggerState(now::get);
        assertFalse(state.beginControlRequest());

        PauseSnapshot before = snapshot(List.of(source("one", 1)), 1);
        state.applyPause(before);
        assertTrue(state.beginControlRequest());
        assertFalse(state.beginControlRequest());
        assertTrue(state.controlPending());

        now.addAndGet(1_999_999_999L);
        assertTrue(state.controlPending());
        now.incrementAndGet();
        assertFalse(state.controlPending());
        assertTrue(state.isPaused());
        assertSame(before, state.snapshot());

        assertTrue(state.beginControlRequest());
        state.applyResume();
        assertFalse(state.controlPending());
        assertFalse(state.isPaused());
        assertNull(state.snapshot());
    }

    @Test
    void restoresFlowSelectionByOccurrenceIdEvenWhenSameEntityContextsReorder() {
        UUID entity = UUID.randomUUID();
        PauseSource one = source(entity, "overworld", 1);
        PauseSource two = source(entity, "overworld", 2);
        ExecutionFlowContext first = new ExecutionFlowContext(11, one);
        ExecutionFlowContext second = new ExecutionFlowContext(12, two);
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(flowSnapshot(77, List.of(first, second), List.of()));
        state.selectSource(1);
        state.applyStep();

        state.applyPause(flowSnapshot(77, List.of(second, first), List.of()));

        assertEquals(0, state.selectedSourceIndex());
        assertEquals(12, state.selectedFlowContext().id());
        assertSame(two, state.selectedSource());
    }

    @Test
    void flowAndCallStackSelectionsDoNotPresentMismatchedCommandsAndContexts() {
        PauseSource flowSource = source("flow", 3);
        ExecutionFlowContext context = new ExecutionFlowContext(31, flowSource);
        SourceLocation location = new SourceLocation.Block(new BlockLocation(0, 64, 0, "overworld"));
        ExecutionFlowStage stage = new ExecutionFlowStage(0, CommandSnippet.plain("execute at @s run say hi"),
            List.of(context), List.of(context), List.of(), List.of(), 1, 1, 0, true,
            0, 0, true, true, false);
        PauseSnapshot snapshot = new PauseSnapshot(location, CommandSnippet.plain("say current"), 0,
            List.of(new CallFrame(0, location, CommandSnippet.plain("say current"))), List.of(flowSource),
            List.of(new ExecutionFlowTrace(101, location, List.of(stage), false)), PauseReason.BREAKPOINT);
        ClientDebuggerState state = new ClientDebuggerState();

        state.applyPause(snapshot);
        assertEquals(-1, state.selectedFrameIndex(), "a recorded flow opens on its selected stage");
        assertEquals(0, state.selectedFlowStageIndex());

        state.selectFrame(0);
        assertEquals(0, state.selectedFrameIndex());
        assertEquals(-1, state.selectedFlowStageIndex(), "returning to the call stack also restores pause sources");
        assertSame(flowSource, state.selectedSource());

        state.selectExecutionFlowStage(0);
        assertEquals(-1, state.selectedFrameIndex());
        assertEquals(0, state.selectedFlowStageIndex());
    }

    @Test
    void stageSelectionDrivesInspectorSourcesParentsAndExplicitDrops() {
        PauseSource rootSource = source("root", 0);
        PauseSource passedSource = source("passed", 4);
        PauseSource droppedSource = source("dropped", 8);
        ExecutionFlowContext root = new ExecutionFlowContext(1, rootSource);
        ExecutionFlowContext passedInput = new ExecutionFlowContext(2, passedSource);
        ExecutionFlowContext droppedInput = new ExecutionFlowContext(3, droppedSource);
        ExecutionFlowContext passedOutput = new ExecutionFlowContext(4, passedSource);
        ExecutionFlowStage branch = new ExecutionFlowStage(0, CommandSnippet.plain("execute as @e"),
            List.of(root), List.of(passedInput, droppedInput),
            List.of(new ExecutionFlowEdge(1, 2), new ExecutionFlowEdge(1, 3)), List.of(),
            1, 2, 0, false, 0, 0, true, true, false);
        ExecutionFlowStage condition = new ExecutionFlowStage(1, CommandSnippet.plain("if entity @s"),
            List.of(passedInput, droppedInput), List.of(passedOutput),
            List.of(new ExecutionFlowEdge(2, 4)), List.of(3L),
            2, 1, 1, false, 0, 0, true, true, false);
        PauseSnapshot snapshot = snapshotWithFlows(List.of(new ExecutionFlowTrace(88,
            new SourceLocation.Block(new BlockLocation(0, 64, 0, "overworld")),
            List.of(branch, condition), false)));
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(snapshot);

        assertEquals(2, state.displayedSources().size());
        state.selectSource(1);
        assertTrue(state.selectedSourceDropped());
        assertSame(droppedSource, state.selectedSource());

        state.selectSource(0);
        assertFalse(state.selectedSourceDropped());
        assertEquals(2, state.selectedFlowParent().id());
        state.selectExecutionFlowStage(0);
        assertEquals(-1, state.selectedFrameIndex());
        assertEquals(2, state.displayedSources().size());
        assertFalse(state.isDisplayedSourceDropped(1));
    }

    @Test
    void completedFlowRemainsReadOnlyAndInspectableAfterTerminalResume() {
        PauseSource source = source("completed", 6);
        ExecutionFlowContext context = new ExecutionFlowContext(51, source);
        SourceLocation location = new SourceLocation.Block(new BlockLocation(0, 64, 0, "overworld"));
        ExecutionFlowStage beforeRun = new ExecutionFlowStage(0, CommandSnippet.plain("say done"),
            List.of(context), List.of(context), List.of(), List.of(), 1, 1, 0, true,
            0, 0, true, true, false);
        PauseSnapshot terminalPause = new PauseSnapshot(location, beforeRun.command(), 0, List.of(), List.of(source),
            List.of(new ExecutionFlowTrace(901, location, List.of(beforeRun), false)), PauseReason.STEP);
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(terminalPause);
        state.applyStep();
        state.applyResume();

        ExecutionFlowStage completed = new ExecutionFlowStage(0, beforeRun.command(),
            List.of(context), List.of(context), List.of(), List.of(), 1, 1, 0, true,
            1, 1, true, true, false);
        state.applyCompletedExecutionFlows(
            List.of(new ExecutionFlowTrace(901, location, List.of(completed), false)));

        assertFalse(state.isPaused());
        assertFalse(state.isStepping());
        assertNull(state.snapshot(), "completed inspection data is not an active pause");
        assertNotNull(state.inspectionSnapshot());
        assertEquals(1, state.selectedExecutionFlow().executionCount());
        assertEquals(1, state.selectedExecutionFlow().successCount());
        assertFalse(state.beginControlRequest(), "completed flow must not reactivate debugger controls");

        state.reset();
        assertNull(state.inspectionSnapshot(), "disconnect/reset removes the retained result");
    }

    private static PauseSnapshot snapshot(List<PauseSource> sources, int frameCount) {
        SourceLocation location = new SourceLocation.Block(new BlockLocation(0, 64, 0, "overworld"));
        List<CallFrame> frames = new ArrayList<>();
        for (int i = 0; i < frameCount; i++) {
            frames.add(new CallFrame(i, location, CommandSnippet.plain("say " + i)));
        }
        return new PauseSnapshot(location, CommandSnippet.plain("say test"), 0, frames, sources, PauseReason.BREAKPOINT);
    }

    private static PauseSnapshot flowSnapshot(long invocationId, List<ExecutionFlowContext> outputs,
                                              List<Long> dropped) {
        ExecutionFlowStage stage = new ExecutionFlowStage(0, CommandSnippet.plain("execute as @e run say hi"),
            List.of(), outputs, List.of(), dropped, 1, outputs.size(), dropped.size(), false,
            0, 0, true, true, false);
        SourceLocation location = new SourceLocation.Block(new BlockLocation(0, 64, 0, "overworld"));
        return new PauseSnapshot(location, CommandSnippet.plain("say hi"), 0, List.of(),
            outputs.stream().map(ExecutionFlowContext::source).toList(),
            List.of(new ExecutionFlowTrace(invocationId, location, List.of(stage), false)), PauseReason.BREAKPOINT);
    }

    private static PauseSnapshot snapshotWithFlows(List<ExecutionFlowTrace> flows) {
        SourceLocation location = new SourceLocation.Block(new BlockLocation(0, 64, 0, "overworld"));
        return new PauseSnapshot(location, CommandSnippet.plain("say hi"), 0, List.of(), List.of(), flows,
            PauseReason.BREAKPOINT);
    }

    private static PauseSource source(String name, int x) {
        return new PauseSource(new Vec3d(x, 64, 0), 0, 0,
                new EntityRef(UUID.nameUUIDFromBytes(name.getBytes()), name), "overworld");
    }

    private static PauseSource source(UUID uuid, String dimension, int x) {
        return new PauseSource(new Vec3d(x, 64, 0), 0, 0, new EntityRef(uuid, "entity"), dimension);
    }
}
