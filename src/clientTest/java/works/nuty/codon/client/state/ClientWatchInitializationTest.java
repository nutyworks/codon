package works.nuty.codon.client.state;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;
import static org.junit.jupiter.api.Assertions.*;

/** Delayed first owner sync must not erase successful session-local edits. */
class ClientWatchInitializationTest {
    private static final WatchSpec LOCAL = new WatchSpec(WatchSpec.Kind.SCORE, "local", "");
    private static final WatchSpec REMOTE = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health");
    private static final WatchSpec OTHER = new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:data", "value");

    @Test void delayedEmptyAndNonemptyRestoresPreserveLocalSlotsAndSaveTheUnionOnce() {
        for (List<WatchSpec> remote : List.of(List.<WatchSpec>of(), List.of(REMOTE))) {
            var state = new ClientWatchState(() -> 0);
            state.add(LOCAL);
            long localId = state.findId(LOCAL);
            state.paused(7, 0);
            var query = state.drainQueries().getFirst();
            var result = new WatchResult(WatchResult.Status.VALUE, "7", "holder");
            state.accept(7, query.requestId(), result);
            List<List<WatchSpec>> saves = new ArrayList<>();

            assertTrue(state.initializeDefinitions(remote, ignored -> true, saves::add));

            List<WatchSpec> expected = new ArrayList<>(List.of(LOCAL));
            expected.addAll(remote);
            assertEquals(expected, state.definitions());
            assertEquals(List.of(expected), saves);
            assertEquals(localId, state.findId(LOCAL));
            assertEquals(result, state.entries().getFirst().result());
            assertEquals(remote.size(), state.drainQueries().size(), "only new server watches need a query");
            assertFalse(state.initializeDefinitions(List.of(OTHER), ignored -> true, saves::add));
            assertEquals(List.of(expected), saves);
            assertEquals(expected, state.definitions());
        }
    }

    @Test void mergesOnlyTheLocalDefinitionsStillPresentAfterEditAndRemove() {
        var state = new ClientWatchState(() -> 0);
        state.add(LOCAL);
        long localId = state.findId(LOCAL);
        assertTrue(state.update(localId, OTHER));
        state.add(REMOTE);
        state.remove(state.findId(REMOTE));
        List<List<WatchSpec>> saves = new ArrayList<>();
        assertTrue(state.initializeDefinitions(List.of(), ignored -> true, saves::add));
        assertEquals(List.of(OTHER), state.definitions());
        assertEquals(localId, state.findId(OTHER));
        assertEquals(List.of(List.of(OTHER)), saves);
        assertEquals(-1, state.findId(LOCAL));
        assertEquals(-1, state.findId(REMOTE));
    }

    @Test void removedLocalRowsDoNotCauseAnEmptyStartupSave() {
        var state = new ClientWatchState(() -> 0);
        state.add(LOCAL);
        state.remove(state.findId(LOCAL));
        List<List<WatchSpec>> saves = new ArrayList<>();
        assertTrue(state.initializeDefinitions(List.of(REMOTE), ignored -> true, saves::add));
        assertEquals(List.of(REMOTE), state.definitions());
        assertTrue(saves.isEmpty());
    }

    @Test void canonicalAliasesPreserveTheLocalExpressionWithoutEchoingEquivalentServerData() {
        var state = new ClientWatchState(() -> 0);
        state.add(REMOTE);
        var quoted = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "\"Health\"");
        List<List<WatchSpec>> saves = new ArrayList<>();
        assertTrue(state.initializeDefinitions(List.of(quoted, OTHER), ignored -> true, saves::add));
        assertEquals(List.of(REMOTE, OTHER), state.definitions());
        assertEquals(state.findId(REMOTE), state.findId(quoted));
        assertTrue(saves.isEmpty());
    }

    @Test void noEditRestoreNeverEchoesStartupStateButLaterEditsPersistNormally() {
        for (List<WatchSpec> remote : List.of(List.<WatchSpec>of(), List.of(REMOTE))) {
            var state = new ClientWatchState(() -> 0);
            List<List<WatchSpec>> saves = new ArrayList<>();
            assertTrue(state.initializeDefinitions(remote, ignored -> true, saves::add));
            assertEquals(remote, state.definitions());
            assertTrue(saves.isEmpty());
            state.add(LOCAL);
            assertEquals(List.of(state.definitions()), saves);
        }
    }

    @Test void repeatRestorationCannotReplacePostInitializationEdits() {
        var state = new ClientWatchState(() -> 0);
        List<List<WatchSpec>> saves = new ArrayList<>();
        state.initializeDefinitions(List.of(REMOTE), ignored -> true, saves::add);
        state.update(state.findId(REMOTE), OTHER);
        state.add(LOCAL);
        int calls = saves.size();
        assertFalse(state.initializeDefinitions(List.of(REMOTE), ignored -> true, saves::add));
        assertEquals(List.of(OTHER, LOCAL), state.definitions());
        assertEquals(calls, saves.size());
    }

    @Test void inSessionResetAfterInitializationIsSilentAndPreservesSaving() {
        var state = new ClientWatchState(() -> 0);
        List<List<WatchSpec>> saves = new ArrayList<>();
        assertTrue(state.initializeDefinitions(List.of(REMOTE), ignored -> true, saves::add));
        state.reset(); // The unchanged native persistence fixture resets after its owner handshake.
        assertTrue(state.definitions().isEmpty());
        assertTrue(saves.isEmpty(), "reset itself must not erase the persisted list");
        assertTrue(state.addAll(List.of(LOCAL, OTHER)));
        assertEquals(List.of(List.of(LOCAL, OTHER)), saves);
        assertTrue(state.initialDefinitionsReceived(), "the same connection must not initialize again");
        assertFalse(state.initializeDefinitions(List.of(REMOTE), ignored -> true, saves::add));
        assertEquals(List.of(LOCAL, OTHER), state.definitions());
    }

    @Test void inSessionResetDuringConflictRetainsTheServerSnapshotForTheNextEdit() {
        var state = new ClientWatchState(() -> 0);
        state.addAll(List.of(LOCAL, OTHER));
        List<List<WatchSpec>> saves = new ArrayList<>();
        assertFalse(state.initializeDefinitions(List.of(REMOTE), values -> values.size() <= 2, saves::add));
        state.reset();
        assertTrue(state.definitions().isEmpty());
        assertTrue(saves.isEmpty());
        assertEquals(ClientWatchState.SaveStatus.FAILED, state.saveStatus());
        state.add(LOCAL);
        assertEquals(List.of(LOCAL, REMOTE), state.definitions());
        assertEquals(List.of(List.of(LOCAL, REMOTE)), saves);
    }

    @Test void conflictingMergeRetainsLocalDataAndOnlySavesAfterTheWholeUnionFits() {
        var state = new ClientWatchState(() -> 0);
        state.addAll(List.of(LOCAL, OTHER));
        long localId = state.findId(LOCAL);
        List<List<WatchSpec>> saves = new ArrayList<>();
        assertFalse(state.initializeDefinitions(List.of(REMOTE), values -> values.size() <= 2, saves::add));
        assertTrue(state.initialDefinitionsReceived());
        assertEquals(ClientWatchState.SaveStatus.FAILED, state.saveStatus());
        assertEquals(List.of(LOCAL, OTHER), state.definitions());
        assertTrue(saves.isEmpty());
        state.retrySave();
        assertTrue(saves.isEmpty());
        state.remove(state.findId(OTHER));
        assertEquals(List.of(LOCAL, REMOTE), state.definitions());
        assertEquals(localId, state.findId(LOCAL));
        assertEquals(List.of(List.of(LOCAL, REMOTE)), saves);
    }

    @Test void clearingLocalConflictRestoresTheServerListWithoutSavingAnEmptySubset() {
        var state = new ClientWatchState(() -> 0);
        state.addAll(List.of(LOCAL, OTHER));
        List<List<WatchSpec>> saves = new ArrayList<>();
        state.initializeDefinitions(List.of(REMOTE), values -> values.size() <= 1, saves::add);
        state.clearDefinitions();
        assertEquals(List.of(REMOTE), state.definitions());
        assertTrue(saves.isEmpty());
    }

    @Test void resetBeforeFirstSyncAndDisconnectAfterConflictCannotRetainOldCallbacks() {
        var state = new ClientWatchState(() -> 0);
        state.add(OTHER);
        state.reset(); // Existing native promotion fixture resets immediately before adding watches.
        state.add(LOCAL);
        List<List<WatchSpec>> oldSaves = new ArrayList<>();
        assertFalse(state.initializeDefinitions(List.of(REMOTE), values -> values.size() <= 1, oldSaves::add));
        state.endConnection();
        state.reset();
        assertFalse(state.initialDefinitionsReceived());
        state.add(OTHER);
        state.retrySave();
        assertTrue(oldSaves.isEmpty());
        assertEquals(List.of(OTHER), state.definitions());
        List<List<WatchSpec>> newSaves = new ArrayList<>();
        assertTrue(state.initializeDefinitions(List.of(), ignored -> true, newSaves::add));
        assertEquals(List.of(List.of(OTHER)), newSaves);
        state.endConnection();
        state.reset();
        state.add(LOCAL);
        assertEquals(1, newSaves.size(), "a completed connection's callback must also be dropped");
    }

    @Test void absentServerCapabilityLeavesSessionLocalEditingUnrestricted() {
        var state = new ClientWatchState(() -> 0);
        var local = IntStream.range(0, 9000).mapToObj(i -> new WatchSpec(WatchSpec.Kind.SCORE, "local" + i, "")).toList();
        assertTrue(state.addAll(local));
        assertFalse(state.initialDefinitionsReceived());
        assertEquals(local, state.definitions());
        assertTrue(state.update(state.findId(local.getFirst()), OTHER));
        var removed = state.removeForUndo(state.findId(local.getLast()));
        assertTrue(state.restore(removed));
        assertEquals(9000, state.definitions().size());
        assertEquals(ClientWatchState.SaveStatus.IDLE, state.saveStatus());
    }

    @Test void rejectedRemoteSnapshotPreservesLocalObservationsAndBlocksPartialSavesUntilReconnect() {
        var state = new ClientWatchState(() -> 0);
        state.add(LOCAL);
        long id = state.findId(LOCAL);
        state.paused(7, 0);
        var query = state.drainQueries().getFirst();
        var observed = new WatchResult(WatchResult.Status.VALUE, "7", "holder");
        state.accept(7, query.requestId(), observed);
        List<List<WatchSpec>> saves = new ArrayList<>();
        state.rejectInitialDefinitions();
        assertFalse(state.initialDefinitionsReceived(), "Failure is not a completed empty restore");
        assertTrue(state.initialRestoreFailed());
        assertEquals(ClientWatchState.SaveStatus.RESTORE_FAILED, state.saveStatus());
        assertEquals(id, state.findId(LOCAL));
        assertEquals(observed, state.entries().getFirst().result());
        assertTrue(state.add(OTHER));
        state.retrySave();
        assertFalse(state.initializeDefinitions(List.of(REMOTE), ignored -> true, saves::add));
        assertEquals(List.of(LOCAL, OTHER), state.definitions());
        assertTrue(saves.isEmpty());
        state.reset();
        assertEquals(ClientWatchState.SaveStatus.RESTORE_FAILED, state.saveStatus());
        state.add(LOCAL);
        state.retrySave();
        assertTrue(saves.isEmpty());
        state.endConnection();
        state.reset();
        assertFalse(state.initialRestoreFailed());
        assertTrue(state.initializeDefinitions(List.of(REMOTE), ignored -> true, saves::add));
        state.rejectInitialDefinitions(); // A late unsolicited failure cannot revoke a valid restore.
        state.add(LOCAL);
        assertEquals(List.of(List.of(REMOTE, LOCAL)), saves);
    }

    @Test void invalidInitialDefinitionsDoNotInstallCallbacksOrConsumeInitialization() {
        var state = new ClientWatchState(() -> 0);
        state.add(LOCAL);
        List<List<WatchSpec>> saves = new ArrayList<>();
        assertThrows(IllegalArgumentException.class,
            () -> state.initializeDefinitions(List.of(REMOTE, REMOTE), ignored -> true, saves::add));
        assertFalse(state.initialDefinitionsReceived());
        assertEquals(List.of(LOCAL), state.definitions());
        assertTrue(saves.isEmpty());
    }
}
