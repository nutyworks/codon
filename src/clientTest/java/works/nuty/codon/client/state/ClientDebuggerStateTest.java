package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.CallFrame;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.EntityRef;
import works.nuty.codon.core.model.ExecutionFlowContext;
import works.nuty.codon.core.model.ExecutionFlowEdge;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.PauseReason;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.Vec3d;

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
        preferences.setGizmoMode(ClientDebuggerState.GizmoMode.LABELS);
        preferences.setInspectorVisible(true);
        preferences.setInspectorTab(DebuggerPreferences.InspectorTab.STACK);
        ClientDebuggerState state = new ClientDebuggerState(preferences);

        state.reset();

        assertSame(preferences, state.preferences());
        assertEquals(ClientDebuggerState.GizmoMode.LABELS, state.gizmoMode());
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
    void createdSourcesRequireARecordedParentAndSemanticSourceChange() {
        UUID entity = UUID.randomUUID();
        PauseSource parentSource = source(entity, "before", "overworld", 4, 64, 0, 10, 20);
        PauseSource renamed = source(entity, "after", "overworld", 4, 64, 0, 10, 20);
        PauseSource moved = source(entity, "before", "overworld", 5, 64, 0, 10, 20);
        PauseSource rotated = source(entity, "before", "overworld", 4, 64, 0, 10, 21);
        PauseSource otherDimension = source(entity, "before", "the_nether", 4, 64, 0, 10, 20);
        PauseSource newEntity = source(UUID.randomUUID(), "new", "overworld", 4, 64, 0, 10, 20);
        ExecutionFlowContext input = new ExecutionFlowContext(10, parentSource);
        ExecutionFlowContext sameValue = new ExecutionFlowContext(11, parentSource);
        ExecutionFlowContext repeatedSameValue = new ExecutionFlowContext(12, parentSource);
        ExecutionFlowContext movedOutput = new ExecutionFlowContext(13, moved);
        ExecutionFlowContext rotatedOutput = new ExecutionFlowContext(14, rotated);
        ExecutionFlowContext otherDimensionOutput = new ExecutionFlowContext(15, otherDimension);
        ExecutionFlowContext newEntityOutput = new ExecutionFlowContext(16, newEntity);
        ExecutionFlowContext renamedOutput = new ExecutionFlowContext(17, renamed);
        ExecutionFlowStage stage = new ExecutionFlowStage(0, CommandSnippet.plain("execute as @s"),
            List.of(input), List.of(sameValue, repeatedSameValue, movedOutput, rotatedOutput, otherDimensionOutput,
                newEntityOutput, renamedOutput, input),
            List.of(new ExecutionFlowEdge(10, 11), new ExecutionFlowEdge(10, 12), new ExecutionFlowEdge(10, 13),
                new ExecutionFlowEdge(10, 14), new ExecutionFlowEdge(10, 15), new ExecutionFlowEdge(10, 16),
                new ExecutionFlowEdge(10, 17),
                new ExecutionFlowEdge(10, 10)),
            List.of(), 1, 8, 0, false, 0, 0, true, true, false);
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(snapshotWithFlows(List.of(new ExecutionFlowTrace(92,
            new SourceLocation.Block(new BlockLocation(0, 64, 0, "overworld")), List.of(stage), false))));

        assertFalse(state.selectedSourceCreated(), "a fresh occurrence alone is not a source change");
        assertFalse(state.isDisplayedSourceCreated(0));
        assertFalse(state.isDisplayedSourceCreated(1), "repeated identical branches remain unchanged");
        assertTrue(state.isDisplayedSourceCreated(2), "same entity with a moved anchor is changed");
        assertTrue(state.isDisplayedSourceCreated(3), "pitch or yaw changes are semantic changes");
        assertTrue(state.isDisplayedSourceCreated(4), "dimension changes are semantic changes");
        assertTrue(state.isDisplayedSourceCreated(5), "a different executor is changed");
        assertFalse(state.isDisplayedSourceCreated(6), "entity display-name changes are ignored");
        assertFalse(state.isDisplayedSourceCreated(7), "an input occurrence cannot be created");

        state.selectSource(2);
        assertTrue(state.selectedSourceCreated());
        state.selectSource(6);
        assertFalse(state.selectedSourceCreated());
    }

    @Test
    void createdStatusRequiresCompleteKnownNonterminalLineageAndValidOccurrence() {
        PauseSource source = source("context", 5);
        ExecutionFlowContext input = new ExecutionFlowContext(20, source);
        ExecutionFlowContext produced = new ExecutionFlowContext(21, source("moved", 6));
        ExecutionFlowStage incomplete = new ExecutionFlowStage(0, CommandSnippet.plain("execute"),
            List.of(input), List.of(produced), List.of(new ExecutionFlowEdge(20, 21)), List.of(), 1, 1, 0, false,
            0, 0, false, true, false);
        ExecutionFlowStage unknownLineage = new ExecutionFlowStage(1, CommandSnippet.plain("execute"),
            List.of(input), List.of(produced), List.of(new ExecutionFlowEdge(20, 21)), List.of(), 1, 1, 0, false,
            0, 0, true, false, false);
        ExecutionFlowStage terminal = new ExecutionFlowStage(2, CommandSnippet.plain("run say hi"),
            List.of(input), List.of(produced), List.of(new ExecutionFlowEdge(20, 21)), List.of(), 1, 1, 0, true,
            0, 0, true, true, false);
        ExecutionFlowStage missingParent = new ExecutionFlowStage(3, CommandSnippet.plain("execute"),
            List.of(input), List.of(produced), List.of(), List.of(), 1, 1, 0, false,
            0, 0, true, true, false);
        ExecutionFlowStage retainedOccurrence = new ExecutionFlowStage(4, CommandSnippet.plain("execute"),
            List.of(input), List.of(input), List.of(new ExecutionFlowEdge(20, 20)), List.of(), 1, 1, 0, false,
            0, 0, true, true, false);
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(snapshotWithFlows(List.of(new ExecutionFlowTrace(93,
            new SourceLocation.Block(new BlockLocation(0, 64, 0, "overworld")),
            List.of(incomplete, unknownLineage, terminal, missingParent, retainedOccurrence), false))));

        for (int index = 0; index < 5; index++) {
            state.selectExecutionFlowStage(index);
            assertFalse(state.selectedSourceCreated());
            assertFalse(state.isDisplayedSourceCreated(0));
        }
        assertFalse(state.isDisplayedSourceCreated(-1));
        assertFalse(state.isDisplayedSourceCreated(1));
    }

    @Test
    void anySemanticallyIdenticalMappedParentKeepsAnOutputUnchanged() {
        UUID entity = UUID.randomUUID();
        PauseSource original = source(entity, "original", "overworld", 1, 64, 0, 0, 0);
        PauseSource otherParent = source(entity, "original", "overworld", 3, 64, 0, 0, 0);
        ExecutionFlowContext firstParent = new ExecutionFlowContext(40, original);
        ExecutionFlowContext secondParent = new ExecutionFlowContext(41, otherParent);
        ExecutionFlowContext retainedByOneParent = new ExecutionFlowContext(42, original);
        ExecutionFlowContext changedFromEveryParent = new ExecutionFlowContext(43,
            source(entity, "original", "overworld", 2, 64, 0, 0, 0));
        ExecutionFlowStage stage = new ExecutionFlowStage(0, CommandSnippet.plain("execute"),
            List.of(firstParent, secondParent), List.of(retainedByOneParent, changedFromEveryParent),
            List.of(new ExecutionFlowEdge(40, 42), new ExecutionFlowEdge(41, 42),
                new ExecutionFlowEdge(40, 43), new ExecutionFlowEdge(41, 43)),
            List.of(), 2, 2, 0, false, 0, 0, true, true, false);
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(snapshotWithFlows(List.of(new ExecutionFlowTrace(95,
            new SourceLocation.Block(new BlockLocation(0, 64, 0, "overworld")), List.of(stage), false))));

        assertFalse(state.isDisplayedSourceCreated(0));
        assertTrue(state.isDisplayedSourceCreated(1));
    }

    @Test
    void changingStagesUpdatesCreatedStatusForSelectedAndDisplayedSources() {
        PauseSource source = source("transition", 6);
        ExecutionFlowContext input = new ExecutionFlowContext(30, source);
        ExecutionFlowContext created = new ExecutionFlowContext(31, source("moved-transition", 7));
        ExecutionFlowStage produced = new ExecutionFlowStage(0, CommandSnippet.plain("execute"),
            List.of(input), List.of(created), List.of(new ExecutionFlowEdge(30, 31)), List.of(), 1, 1, 0, false,
            0, 0, true, true, false);
        ExecutionFlowStage retained = new ExecutionFlowStage(1, CommandSnippet.plain("run say hi"),
            List.of(created), List.of(created), List.of(new ExecutionFlowEdge(31, 31)), List.of(), 1, 1, 0, false,
            0, 0, true, true, false);
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(snapshotWithFlows(List.of(new ExecutionFlowTrace(94,
            new SourceLocation.Block(new BlockLocation(0, 64, 0, "overworld")),
            List.of(produced, retained), false))));

        assertFalse(state.selectedSourceCreated());
        assertFalse(state.isDisplayedSourceCreated(0));

        state.selectExecutionFlowStage(0);
        assertTrue(state.selectedSourceCreated());
        assertTrue(state.isDisplayedSourceCreated(0));
    }

    @Test
    void worldViewportUsesTheJustFinishedStageWhileTheNextStageIsIncomplete() {
        PauseSource rootSource = source("world-root", 0);
        PauseSource passedSource = source("world-passed", 4);
        PauseSource droppedSource = source("world-dropped", 8);
        ExecutionFlowContext root = new ExecutionFlowContext(100, rootSource);
        ExecutionFlowContext passed = new ExecutionFlowContext(101, passedSource);
        ExecutionFlowContext dropped = new ExecutionFlowContext(102, droppedSource);
        ExecutionFlowContext finalOutput = new ExecutionFlowContext(103, passedSource);
        ExecutionFlowStage as = new ExecutionFlowStage(0, CommandSnippet.plain("execute as @e"),
            List.of(root), List.of(passed, dropped), List.of(new ExecutionFlowEdge(100, 101),
            new ExecutionFlowEdge(100, 102)), List.of(), 1, 2, 0, false, 0, 0, true, true, false);
        ExecutionFlowStage at = new ExecutionFlowStage(1, CommandSnippet.plain("execute at @s"),
            List.of(passed, dropped), List.of(finalOutput), List.of(new ExecutionFlowEdge(101, 103)), List.of(102L),
            2, 1, 1, false, 0, 0, true, true, false);
        ExecutionFlowStage next = new ExecutionFlowStage(2, CommandSnippet.plain("if entity @s"),
            List.of(finalOutput), List.of(), List.of(), List.of(), 1, 0, 0, false, 0, 0, false, true, false);
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(snapshotWithFlows(List.of(new ExecutionFlowTrace(96,
            new SourceLocation.Block(new BlockLocation(0, 64, 0, "overworld")), List.of(as, at, next), false))));

        assertSame(at, state.selectedExecutionFlowStage(), "automatically select the last completed stage");
        assertEquals(state.worldSources(), state.displayedSources());
        assertTrue(state.isDisplayedSourceDropped(1));
        assertSame(at, state.worldSourceStage());
        assertEquals(List.of(passedSource, droppedSource), state.worldSources());
        assertEquals(0, state.selectedWorldSourceIndex());
        assertFalse(state.isWorldSourceCreated(0));
        assertTrue(state.isWorldSourceDropped(1));

        state.selectWorldSource(1);
        assertSame(at, state.selectedExecutionFlowStage(), "clicking a prior dropped source opens its stage");
        assertEquals(1, state.selectedSourceIndex());
        assertEquals(1, state.selectedWorldSourceIndex());
        assertSame(droppedSource, state.selectedSource());
    }

    @Test
    void worldViewportShowsCreatedBranchesBeforeTheNextStageRuns() {
        PauseSource rootSource = source("branch-root", 0);
        PauseSource firstSource = source("branch-first", 4);
        PauseSource secondSource = source("branch-second", 8);
        ExecutionFlowContext root = new ExecutionFlowContext(104, rootSource);
        ExecutionFlowContext first = new ExecutionFlowContext(105, firstSource);
        ExecutionFlowContext second = new ExecutionFlowContext(106, secondSource);
        ExecutionFlowStage as = new ExecutionFlowStage(0, CommandSnippet.plain("execute as @e"),
            List.of(root), List.of(first, second), List.of(new ExecutionFlowEdge(104, 105),
            new ExecutionFlowEdge(104, 106)), List.of(), 1, 2, 0, false, 0, 0, true, true, false);
        ExecutionFlowStage at = new ExecutionFlowStage(1, CommandSnippet.plain("execute at @s"),
            List.of(first, second), List.of(), List.of(), List.of(), 2, 0, 0, false, 0, 0, false, true, false);
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(snapshotWithFlows(List.of(new ExecutionFlowTrace(105,
            new SourceLocation.Block(new BlockLocation(0, 64, 0, "overworld")), List.of(as, at), false))));

        assertSame(as, state.selectedExecutionFlowStage());
        assertEquals(state.worldSources(), state.displayedSources());
        assertTrue(state.isDisplayedSourceCreated(0));
        assertSame(as, state.worldSourceStage());
        assertEquals(List.of(firstSource, secondSource), state.worldSources());
        assertTrue(state.isWorldSourceCreated(0));
        assertTrue(state.isWorldSourceCreated(1));
        assertFalse(state.isWorldSourceDropped(0));
    }

    @Test
    void worldViewportUsesACompleteSelectedStageOrItsImmediateTerminalPredecessor() {
        PauseSource rootSource = source("terminal-root", 0);
        PauseSource createdSource = source("terminal-created", 4);
        ExecutionFlowContext root = new ExecutionFlowContext(110, rootSource);
        ExecutionFlowContext created = new ExecutionFlowContext(111, createdSource);
        ExecutionFlowStage complete = new ExecutionFlowStage(0, CommandSnippet.plain("execute as @e"),
            List.of(root), List.of(created), List.of(new ExecutionFlowEdge(110, 111)), List.of(), 1, 1, 0, false,
            0, 0, true, true, false);
        ExecutionFlowStage terminal = new ExecutionFlowStage(1, CommandSnippet.plain("run say hi"),
            List.of(created), List.of(created), List.of(new ExecutionFlowEdge(111, 111)), List.of(), 1, 1, 0, true,
            0, 0, true, true, false);
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(snapshotWithFlows(List.of(new ExecutionFlowTrace(97,
            new SourceLocation.Block(new BlockLocation(0, 64, 0, "overworld")), List.of(complete, terminal), false))));

        assertSame(complete, state.worldSourceStage());
        assertEquals(List.of(createdSource), state.worldSources());
        assertTrue(state.isWorldSourceCreated(0));

        state.selectExecutionFlowStage(0);
        assertSame(complete, state.worldSourceStage(), "a complete selected stage uses itself");
        assertTrue(state.isWorldSourceCreated(0));
    }

    @Test
    void worldViewportDoesNotAliasAcrossBrokenOrUnknownStageChains() {
        PauseSource rootSource = source("guard-root", 0);
        PauseSource outputSource = source("guard-output", 4);
        PauseSource otherSource = source("guard-other", 8);
        ExecutionFlowContext root = new ExecutionFlowContext(120, rootSource);
        ExecutionFlowContext output = new ExecutionFlowContext(121, outputSource);
        ExecutionFlowContext other = new ExecutionFlowContext(122, otherSource);
        ExecutionFlowStage complete = new ExecutionFlowStage(0, CommandSnippet.plain("execute"),
            List.of(root), List.of(output), List.of(new ExecutionFlowEdge(120, 121)), List.of(), 1, 1, 0, false,
            0, 0, true, true, false);
        ExecutionFlowStage mismatchedInputs = new ExecutionFlowStage(1, CommandSnippet.plain("at"),
            List.of(other), List.of(), List.of(), List.of(), 1, 0, 0, false, 0, 0, false, true, false);
        ExecutionFlowStage countMismatch = new ExecutionFlowStage(1, CommandSnippet.plain("at"),
            List.of(output), List.of(), List.of(), List.of(), 1, 0, 0, false, 0, 0, false, true, false);
        ExecutionFlowStage reportedTwoOutputs = new ExecutionFlowStage(0, CommandSnippet.plain("execute"),
            List.of(root), List.of(output), List.of(new ExecutionFlowEdge(120, 121)), List.of(), 1, 2, 0, false,
            0, 0, true, true, false);
        ExecutionFlowStage unknown = new ExecutionFlowStage(0, CommandSnippet.plain("execute"),
            List.of(root), List.of(output), List.of(new ExecutionFlowEdge(120, 121)), List.of(), 1, 1, 0, false,
            0, 0, true, false, false);
        ExecutionFlowStage terminal = new ExecutionFlowStage(0, CommandSnippet.plain("run say hi"),
            List.of(root), List.of(output), List.of(new ExecutionFlowEdge(120, 121)), List.of(), 1, 1, 0, true,
            0, 0, true, true, false);
        List<ExecutionFlowTrace> flows = List.of(
            new ExecutionFlowTrace(98, new SourceLocation.Block(new BlockLocation(0, 64, 0, "overworld")),
                List.of(complete, mismatchedInputs), false),
            new ExecutionFlowTrace(99, new SourceLocation.Block(new BlockLocation(0, 64, 0, "overworld")),
                List.of(reportedTwoOutputs, countMismatch), false),
            new ExecutionFlowTrace(100, new SourceLocation.Block(new BlockLocation(0, 64, 0, "overworld")),
                List.of(unknown, countMismatch), false),
            new ExecutionFlowTrace(101, new SourceLocation.Block(new BlockLocation(0, 64, 0, "overworld")),
                List.of(terminal, countMismatch), false));
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(snapshotWithFlows(flows));

        for (int flowIndex = 0; flowIndex < flows.size(); flowIndex++) {
            state.selectExecutionFlow(flowIndex);
            assertSame(flows.get(flowIndex).stages().getFirst(), state.selectedExecutionFlowStage());
            state.selectExecutionFlowStage(1);
            ExecutionFlowStage current = flows.get(flowIndex).stages().get(1);
            assertSame(current, state.worldSourceStage());
            assertEquals(current.displayContexts().stream().map(ExecutionFlowContext::source).toList(), state.worldSources());
            assertFalse(state.isWorldSourceCreated(0));
            assertFalse(state.isWorldSourceDropped(0));
        }
    }

    @Test
    void worldSourcesUseLegacyPauseSourcesAndClearWhenUnpausedOrReset() {
        PauseSource first = source("legacy-first", 1);
        PauseSource second = source("legacy-second", 2);
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(snapshot(List.of(first, second), 1));

        assertNull(state.worldSourceStage());
        assertEquals(List.of(first, second), state.worldSources());
        assertEquals(0, state.selectedWorldSourceIndex());
        assertFalse(state.isWorldSourceCreated(0));
        assertFalse(state.isWorldSourceDropped(1));
        state.selectWorldSource(1);
        assertEquals(1, state.selectedWorldSourceIndex());
        assertSame(second, state.selectedSource());
        state.selectWorldSource(-1);
        state.selectWorldSource(2);
        assertEquals(1, state.selectedWorldSourceIndex(), "invalid world-source indices do not change selection");
        assertFalse(state.isWorldSourceCreated(-1));
        assertFalse(state.isWorldSourceDropped(2));

        state.applyResume();
        assertNull(state.worldSourceStage());
        assertTrue(state.worldSources().isEmpty());
        assertEquals(-1, state.selectedWorldSourceIndex());

        state.applyPause(snapshot(List.of(first), 1));
        state.reset();
        assertNull(state.worldSourceStage());
        assertTrue(state.worldSources().isEmpty());
        assertEquals(-1, state.selectedWorldSourceIndex());
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

    @Test
    void flowSelectionMapsLiveQueriesBySourceRatherThanHistoricalListIndex() {
        PauseSource live = source("live", 1);
        PauseSource historical = source("old", 2);
        PauseSnapshot fixture = flowSnapshot(999,
            List.of(new ExecutionFlowContext(1, historical), new ExecutionFlowContext(2, live)), List.of());
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(new PauseSnapshot(fixture.location(), fixture.command(), 0, List.of(),
            List.of(live), fixture.executionFlows(), PauseReason.STEP, 73));
        assertEquals(-1, state.selectedPauseSourceIndex());
        assertNull(state.nbt().executor(), "historical context must not select live source index zero");
        state.selectSource(1);
        assertEquals(0, state.selectedPauseSourceIndex());
        assertEquals(live.entity(), state.nbt().executor());
        state.selectSource(0);
        assertEquals(-1, state.selectedPauseSourceIndex());
        assertNull(state.nbt().executor());
    }

    @Test
    void newPauseSelectsLatestInvocationInsteadOfRetainedHistory() {
        PauseSnapshot old = flowSnapshot(1001, List.of(new ExecutionFlowContext(1, source("old", 1))), List.of());
        PauseSnapshot next = flowSnapshot(1002, List.of(new ExecutionFlowContext(1, source("new", 2))), List.of());
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(old);
        state.applyStep();
        state.applyPause(new PauseSnapshot(next.location(), next.command(), 0, List.of(), next.pauseSources(),
            List.of(old.executionFlows().getFirst(), next.executionFlows().getFirst()), PauseReason.STEP, 74));
        assertEquals(1002, state.selectedExecutionFlow().invocationId());
        assertEquals(next.pauseSources().getFirst(), state.selectedSource());
        assertEquals(0, state.selectedPauseSourceIndex());
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

    private static PauseSource source(UUID uuid, String name, String dimension, double x, double y, double z,
                                      float pitch, float yaw) {
        return new PauseSource(new Vec3d(x, y, z), pitch, yaw, new EntityRef(uuid, name), dimension);
    }
}
