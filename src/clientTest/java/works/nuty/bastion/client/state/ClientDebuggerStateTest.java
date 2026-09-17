package works.nuty.bastion.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.CallFrame;
import works.nuty.bastion.core.model.CommandSnippet;
import works.nuty.bastion.core.model.EntityRef;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientDebuggerStateTest {
    @Test
    void continueClearsStaleInspectionWhileAwaitingTheNextBreakpoint() {
        ClientDebuggerState state = new ClientDebuggerState();
        PauseSource selected = source("selected", 2);
        List<PauseSource> sources = List.of(source("other", 1), selected);
        state.applyPause(snapshot(sources, 2));
        state.selectSource(1);
        assertTrue(state.beginControlRequest());
        state.applyContinue();
        assertTrue(state.isContinuing());
        assertFalse(state.isPaused());
        assertFalse(state.isStepping());
        assertNull(state.snapshot());
        assertFalse(state.controlPending());
        assertFalse(state.beginControlRequest());

        state.applyPause(snapshot(sources, 2));
        assertFalse(state.isContinuing());
        assertSame(selected, state.selectedSource());
        state.applyContinue();
        state.applyResume();
        assertFalse(state.isContinuing());
        state.applyContinue();
        state.reset();
        assertFalse(state.isContinuing(), "disconnect must clear retained presentation");
    }

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
    void resumeThenPauseRetainsOnlyUniqueSameEntityAndDimensionSource() {
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

        assertEquals(1, state.selectedSourceIndex());
        assertSame(replacement, state.selectedSource());
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

    private static PauseSnapshot snapshot(List<PauseSource> sources, int frameCount) {
        SourceLocation location = new SourceLocation.Block(new BlockLocation(0, 64, 0, "overworld"));
        List<CallFrame> frames = new ArrayList<>();
        for (int i = 0; i < frameCount; i++) {
            frames.add(new CallFrame(i, location, CommandSnippet.plain("say " + i)));
        }
        return new PauseSnapshot(location, CommandSnippet.plain("say test"), 0, frames, sources, PauseReason.BREAKPOINT);
    }

    private static PauseSource source(String name, int x) {
        return new PauseSource(new Vec3d(x, 64, 0), 0, 0,
                new EntityRef(UUID.nameUUIDFromBytes(name.getBytes()), name), "overworld");
    }

    private static PauseSource source(UUID uuid, String dimension, int x) {
        return new PauseSource(new Vec3d(x, 64, 0), 0, 0, new EntityRef(uuid, "entity"), dimension);
    }
}
