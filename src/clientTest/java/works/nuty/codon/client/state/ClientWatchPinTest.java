package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.EntityRef;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.Vec3d;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientWatchPinTest {
    private static final WatchSpec SCORE = new WatchSpec(WatchSpec.Kind.SCORE, "points", "");
    private static final WatchSpec STORAGE = new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "example:data", "value");

    @Test
    void unpinMergesIntoExistingFollowingRowAndIgnoresRemovedPinsLateReply() {
        for (WatchSpec following : List.of(SCORE, new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health"))) {
            UUID executor = UUID.randomUUID();
            var state = new ClientWatchState(() -> 0);
            long followingId = state.addOrFind(following);
            WatchSpec pinned = following.kind() == WatchSpec.Kind.ENTITY_NBT
                ? new WatchSpec(following.kind(), "", "\"Health\"", executor) : following.withExecutor(executor);
            long pinnedId = state.addOrFind(pinned);
            state.paused(1, 0);
            var requests = state.drainQueries();
            var live = requests.stream().filter(query -> query.spec().equals(following)).findFirst().orElseThrow();
            var stale = requests.stream().filter(query -> query.spec().equals(pinned)).findFirst().orElseThrow();
            state.accept(1, live.requestId(), value("7", executor));
            var before = entry(state, following);
            List<List<WatchSpec>> saved = new ArrayList<>();
            state.setChangeListener(definitions -> saved.add(List.copyOf(definitions)));

            assertTrue(state.unpin(pinnedId));
            assertEquals(List.of(following), state.definitions());
            assertEquals(before, entry(state, following), "existing id, value and observation survive the merge");
            assertEquals(followingId, state.revealId());
            assertEquals(List.of(List.of(following)), saved, "one atomic persisted update");
            state.accept(1, stale.requestId(), value("99", executor));
            assertEquals(before, entry(state, following), "removed pin cannot overwrite the following row");
            assertFalse(state.unpin(pinnedId));
        }
    }

    @Test
    void allowsTheSameExpressionForTwoPinnedExecutorsAndOneFloatingWatch() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ClientWatchState state = new ClientWatchState(() -> 0);
        assertTrue(state.add(SCORE));
        state.paused(1, 0);
        long firstId = state.entries().getFirst().id();
        assertTrue(state.pin(firstId, entity(first, "first")));

        assertTrue(state.add(SCORE), "a pinned expression does not block a floating expression");
        long secondId = entryWithExecutor(state, null).id();
        assertTrue(state.pin(secondId, entity(second, "second")));
        assertTrue(state.add(SCORE), "two different executor bindings still permit one floating expression");
        assertFalse(state.add(SCORE), "only an identical floating definition is a duplicate");
        assertFalse(state.add(SCORE.withExecutor(first)), "an identical expression and UUID is a duplicate");

        List<ClientWatchState.Query> queries = state.drainQueries();
        assertEquals(3, queries.size());
        assertPinnedQuery(queries, first);
        assertPinnedQuery(queries, second);
        ClientWatchState.Query floating = queries.stream().filter(query -> query.capturedEntity() == null).findFirst().orElseThrow();
        assertEquals(0, floating.sourceIndex());
    }

    @Test
    void sourceSelectionLeavesPinnedReadAndRequestIntact() {
        UUID executor = UUID.randomUUID();
        ClientWatchState state = new ClientWatchState(() -> 0);
        assertTrue(state.add(SCORE.withExecutor(executor)));
        state.paused(1, 0);
        ClientWatchState.Query request = onlyQuery(state);
        assertEquals(executor, request.capturedEntity());
        assertEquals(-1, request.sourceIndex());
        state.accept(1, request.requestId(), value("4", executor));

        state.selectSource(5);
        assertEquals("4", onlyEntry(state).result().value());
        assertTrue(state.drainQueries().isEmpty(), "changing selected source does not invalidate a pin");
    }

    @Test
    void pinnedStepRereadsTheBoundExecutorAndTracksMissingIndependently() {
        UUID executor = UUID.randomUUID();
        ClientWatchState state = new ClientWatchState(() -> 0);
        assertTrue(state.add(SCORE.withExecutor(executor)));
        state.paused(1, 0);
        ClientWatchState.Query before = onlyQuery(state);
        state.accept(1, before.requestId(), value("0", executor));
        state.stepping();

        state.paused(2, -1);
        ClientWatchState.Query changed = onlyQuery(state);
        assertEquals(executor, changed.capturedEntity());
        assertEquals(-1, changed.sourceIndex());
        state.accept(2, changed.requestId(), value("3", executor));
        assertEquals(ClientWatchState.Change.VALUE_CHANGED, onlyEntry(state).change());
        assertEquals("0", onlyEntry(state).previousValue());
        state.stepping();

        state.paused(3, -1);
        ClientWatchState.Query missing = onlyQuery(state);
        state.accept(3, missing.requestId(), WatchResult.absent(WatchResult.Status.VALUE_MISSING, entityKey(executor)));
        assertEquals(ClientWatchState.Change.VALUE_DISAPPEARED, onlyEntry(state).change());
        assertEquals("3", onlyEntry(state).previousValue());
    }

    @Test
    void bindingFlipInvalidatesOnlyThatEntryAndRejectsItsStaleReply() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ClientWatchState state = new ClientWatchState(() -> 0);
        List<List<WatchSpec>> notifications = new ArrayList<>();
        state.setChangeListener(definitions -> notifications.add(List.copyOf(definitions)));
        assertTrue(state.add(SCORE));
        state.paused(1, 0);
        long id = onlyEntry(state).id();
        assertTrue(state.pin(id, entity(first, "first")));
        ClientWatchState.Query stale = onlyQuery(state);
        assertTrue(state.pin(id, entity(second, "second")));
        ClientWatchState.Query current = onlyQuery(state);
        state.accept(1, stale.requestId(), value("old", first));
        assertNull(onlyEntry(state).result());
        state.accept(1, current.requestId(), value("new", second));
        assertEquals("new", onlyEntry(state).result().value());
        assertEquals(List.of(List.of(SCORE), List.of(SCORE.withExecutor(first)), List.of(SCORE.withExecutor(second))), notifications);
    }

    @Test
    void pinConstraintsAndSilentRestoreKeepTheBindingButDropRuntimeState() {
        UUID executor = UUID.randomUUID();
        ClientWatchState state = new ClientWatchState(() -> 0);
        List<List<WatchSpec>> notifications = new ArrayList<>();
        state.setChangeListener(definitions -> notifications.add(List.copyOf(definitions)));
        assertTrue(state.add(SCORE));
        assertTrue(state.add(STORAGE));
        long scoreId = entry(state, SCORE).id();
        long storageId = entry(state, STORAGE).id();
        assertFalse(state.pin(scoreId, entity(executor, "executor")), "pinning requires an active pause");
        state.paused(1, 0);
        assertTrue(state.pin(scoreId, entity(executor, "executor")));
        assertFalse(state.pin(storageId, entity(executor, "executor")), "storage never follows an executor");
        ClientWatchState.Query request = state.drainQueries().stream()
            .filter(query -> executor.equals(query.capturedEntity())).findFirst().orElseThrow();
        state.accept(1, request.requestId(), value("7", executor));

        state.resumed();
        assertEquals(executor, entryWithExecutor(state, executor).spec().executor());
        assertNull(entryWithExecutor(state, executor).result());
        assertTrue(state.unpin(scoreId), "unpin may be used outside a pause");
        state.restoreDefinitions(List.of(SCORE.withExecutor(executor)));
        assertEquals(executor, onlyEntry(state).spec().executor());
        assertNull(onlyEntry(state).result());
        assertEquals(List.of(List.of(SCORE), List.of(SCORE, STORAGE),
            List.of(SCORE.withExecutor(executor), STORAGE), List.of(SCORE, STORAGE)), notifications,
            "restore is silent while successful pin and unpin each notify once");
    }

    @Test
    void bulkAddDeduplicatesExistingAndIncomingDefinitionsWhileKeepingDistinctExecutorBindings() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        WatchSpec firstBinding = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "\"UUID\"[0]", first);
        WatchSpec secondBinding = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "\"UUID\"[0]", second);
        ClientWatchState state = new ClientWatchState(() -> 0);
        assertTrue(state.add(SCORE));
        List<List<WatchSpec>> notifications = new ArrayList<>();
        state.setChangeListener(definitions -> notifications.add(List.copyOf(definitions)));

        assertTrue(state.addAll(List.of(SCORE, firstBinding, firstBinding, secondBinding, SCORE)));
        assertEquals(List.of(SCORE, firstBinding, secondBinding), state.definitions());
        assertEquals(List.of(List.of(SCORE, firstBinding, secondBinding)), notifications,
            "one successful batch persists its complete resulting definition set once");

        assertTrue(state.addAll(List.of(secondBinding, SCORE, firstBinding)), "an all-existing batch is a successful no-op");
        assertEquals(1, notifications.size(), "a no-op batch does not write definitions again");
    }

    @Test
    void bulkAddSupportsMoreThanTheFormerLimitAndNotifiesPersistenceOnce() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        List<List<WatchSpec>> notifications = new ArrayList<>();
        state.setChangeListener(definitions -> notifications.add(List.copyOf(definitions)));
        List<WatchSpec> batch = java.util.stream.IntStream.range(0, 12)
            .mapToObj(index -> new WatchSpec(WatchSpec.Kind.SCORE, "bulk-" + index, "")).toList();

        assertTrue(state.addAll(batch));
        assertEquals(batch, state.definitions());
        assertEquals(List.of(batch), notifications, "the complete large batch persists once");
    }

    @Test
    void clearDefinitionsNotifiesOnceAndKeepsTheCurrentPauseReadyForNewWatches() {
        UUID executor = UUID.randomUUID();
        ClientWatchState state = new ClientWatchState(() -> 0);
        assertTrue(state.add(SCORE.withExecutor(executor)));
        state.paused(7, 3);
        ClientWatchState.Query stale = onlyQuery(state);
        List<List<WatchSpec>> notifications = new ArrayList<>();
        state.setChangeListener(definitions -> notifications.add(List.copyOf(definitions)));

        state.clearDefinitions();
        state.clearDefinitions();
        assertTrue(state.definitions().isEmpty());
        assertEquals(List.of(List.of()), notifications, "clearing a nonempty set persists once and an empty clear is silent");

        assertTrue(state.add(SCORE.withExecutor(executor)));
        ClientWatchState.Query current = onlyQuery(state);
        assertEquals(7, current.pauseId(), "the active pause remains available after a bulk clear");
        assertEquals(-1, current.sourceIndex(), "a recreated pinned watch still uses its captured executor");
        state.accept(7, stale.requestId(), value("stale", executor));
        assertNull(onlyEntry(state).result(), "a pending response from the cleared definition cannot complete the replacement");
        state.accept(7, current.requestId(), value("fresh", executor));
        assertEquals("fresh", onlyEntry(state).result().value());
        assertEquals(List.of(List.of(), List.of(SCORE.withExecutor(executor))), notifications,
            "the first add after clearing uses the preserved listener exactly once");
    }

    @Test
    void groupToggleFillsMissingBindingsThenRemovesOnlyTheGroupAndPersistsOnce() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), outside = UUID.randomUUID();
        WatchSpec field = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health");
        WatchSpec pinnedA = field.withExecutor(a), pinnedB = field.withExecutor(b);
        WatchSpec otherPath = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Pos[0]", a);
        List<WatchSpec> retained = List.of(field, field.withExecutor(outside), otherPath);
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.addAll(retained);
        state.add(pinnedA);
        state.paused(7, 0);
        List<List<WatchSpec>> notifications = new ArrayList<>();
        state.setChangeListener(definitions -> notifications.add(List.copyOf(definitions)));

        state.toggleAll(List.of(pinnedA, pinnedB, pinnedA));
        assertEquals(5, state.definitions().size(), "a partial group adds only missing UUID bindings");
        assertEquals(1, notifications.size());
        List<ClientWatchState.Query> pending = state.drainQueries();
        var staleA = pending.stream().filter(query -> query.spec().equals(pinnedA)).findFirst().orElseThrow();
        state.toggleAll(List.of(pinnedA, pinnedB, pinnedA));
        assertEquals(retained, state.definitions(), "other paths, outside executors, and floating watches remain");
        assertEquals(2, notifications.size(), "bulk removal persists exactly once");
        assertEquals(retained, notifications.getLast());

        state.toggleAll(List.of());
        assertEquals(2, notifications.size(), "empty groups are silent");
        state.toggleAll(List.of(pinnedA, pinnedB));
        assertEquals(5, state.definitions().size());
        assertEquals(3, notifications.size());
        state.accept(7, staleA.requestId(), value("stale", a));
        assertNull(entry(state, pinnedA).result(), "removed requests cannot complete newly re-added definitions");
        var fresh = state.drainQueries().stream().filter(query -> query.spec().equals(pinnedA)).findFirst().orElseThrow();
        assertEquals(7, fresh.pauseId(), "the group toggle preserves the active pause");
        state.accept(7, fresh.requestId(), value("fresh", a));
        assertEquals(ClientWatchState.Change.INITIAL, entry(state, pinnedA).change());
        assertEquals("fresh", entry(state, pinnedA).result().value());
    }

    @Test
    void pinnedMissingTargetsKeepKnownNamesAndLeaveNeverSeenTargetsUnnamed() {
        UUID known = UUID.randomUUID();
        UUID neverSeen = UUID.randomUUID();
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.add(SCORE.withExecutor(known));
        state.add(SCORE.withExecutor(neverSeen));
        state.paused(1, -1);
        state.rememberExecutors(List.of(source(known, "Pig")));
        List<ClientWatchState.Query> queries = state.drainQueries();
        ClientWatchState.Query knownQuery = queries.stream().filter(query -> known.equals(query.capturedEntity())).findFirst().orElseThrow();
        ClientWatchState.Query unknownQuery = queries.stream().filter(query -> neverSeen.equals(query.capturedEntity())).findFirst().orElseThrow();
        state.accept(1, knownQuery.requestId(), WatchResult.absent(WatchResult.Status.TARGET_MISSING, entityKey(known)));
        state.accept(1, unknownQuery.requestId(), WatchResult.absent(WatchResult.Status.TARGET_MISSING, entityKey(neverSeen)));

        ClientWatchState.Entry knownEntry = entryWithExecutor(state, known);
        ClientWatchState.Entry unknownEntry = entryWithExecutor(state, neverSeen);
        assertEquals(known, knownEntry.displayedExecutor());
        assertEquals("Pig", knownEntry.executorName());
        assertEquals(neverSeen, unknownEntry.displayedExecutor());
        assertEquals("", unknownEntry.executorName());
    }

    @Test
    void storageResultsNeverDisplayAnEntityLabel() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.add(STORAGE);
        state.paused(1, 0);
        state.rememberExecutors(List.of(source(UUID.randomUUID(), "Pig")));
        ClientWatchState.Query query = onlyQuery(state);
        state.accept(1, query.requestId(), new WatchResult(WatchResult.Status.VALUE, "7", "storage:example:data"));

        assertNull(onlyEntry(state).displayedExecutor());
        assertEquals("", onlyEntry(state).executorName());
    }

    private static void assertPinnedQuery(List<ClientWatchState.Query> queries, UUID executor) {
        ClientWatchState.Query query = queries.stream().filter(candidate -> executor.equals(candidate.capturedEntity())).findFirst().orElseThrow();
        assertEquals(-1, query.sourceIndex());
    }

    private static ClientWatchState.Entry onlyEntry(ClientWatchState state) {
        return state.entries().getFirst();
    }

    private static ClientWatchState.Entry entry(ClientWatchState state, WatchSpec spec) {
        return state.entries().stream().filter(entry -> entry.spec().equals(spec)).findFirst().orElseThrow();
    }

    private static ClientWatchState.Entry entryWithExecutor(ClientWatchState state, UUID executor) {
        return state.entries().stream().filter(entry -> java.util.Objects.equals(entry.spec().executor(), executor)).findFirst().orElseThrow();
    }

    private static ClientWatchState.Query onlyQuery(ClientWatchState state) {
        return state.drainQueries().getFirst();
    }

    private static EntityRef entity(UUID uuid, String name) {
        return new EntityRef(uuid, name);
    }

    private static PauseSource source(UUID uuid, String name) {
        return new PauseSource(new Vec3d(0, 64, 0), 0, 0, entity(uuid, name), "overworld");
    }

    private static WatchResult value(String value, UUID executor) {
        return new WatchResult(WatchResult.Status.VALUE, value, entityKey(executor));
    }

    private static String entityKey(UUID executor) {
        return "entity:" + executor;
    }
}
