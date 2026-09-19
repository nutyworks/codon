package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ClientWatchChangesTest {
    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID();

    @Test
    void displaysEveryUnregisteredChangeAfterPinsWithoutPersistingThem() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        WatchSpec floating = new WatchSpec(WatchSpec.Kind.SCORE, "unchanged", "");
        WatchSpec pinned = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health", A);
        state.add(floating);
        state.add(pinned);
        List<List<WatchSpec>> saves = new ArrayList<>();
        state.setChangeListener(saves::add);
        state.paused(1, 0);
        state.rememberExecutors(List.of(source(A)));
        List<WatchChange> changes = IntStream.range(0, 75).mapToObj(i -> score("score" + i, A, "1", "2")).toList();
        state.acceptChanges(1, 0, false, changes.subList(0, 32));
        state.acceptChanges(1, 32, false, changes.subList(32, 64));
        state.acceptChanges(1, 64, true, changes.subList(64, 75));

        var rows = state.displayedEntries();
        assertEquals(77, rows.size());
        assertEquals(pinned, rows.getFirst().spec());
        assertEquals(floating, rows.get(1).spec(), "saved watches also stay above temporary rows");
        assertEquals(75, rows.stream().filter(ClientWatchState.Entry::automatic).count());
        assertEquals(List.of(floating, pinned), state.definitions());
        assertTrue(saves.isEmpty());
    }

    @Test
    void changesSurviveSourceSelectionButNotAnotherPauseOrResume() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.paused(1, 0);
        state.acceptChanges(1, 0, true, List.of(score("points", A, "3", "4")));
        state.selectSource(9);
        assertEquals(A, state.displayedEntries().getFirst().displayedExecutor());
        state.paused(1, -1);
        assertEquals(1, state.displayedEntries().size());
        state.stepping();
        assertTrue(state.displayedEntries().isEmpty());
        state.paused(2, 0);
        state.acceptChanges(1, 0, true, List.of(score("stale", A, "3", "4")));
        assertTrue(state.displayedEntries().isEmpty());
        state.acceptChanges(2, 0, true, List.of(score("fresh", A, "4", "5")));
        state.resumed();
        assertTrue(state.displayedEntries().isEmpty());
    }

    @Test
    void ignoresOutOfOrderAndDuplicatePages() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.paused(1, 0);
        state.acceptChanges(1, 1, true, List.of(score("late", A, "1", "2")));
        assertTrue(state.displayedEntries().isEmpty());
        state.acceptChanges(1, 0, false, List.of(score("first", A, "1", "2")));
        state.acceptChanges(1, 0, false, List.of(score("duplicate", A, "1", "2")));
        state.acceptChanges(1, 1, true, List.of(score("last", A, "1", "2")));
        state.acceptChanges(1, 2, true, List.of(score("extra", A, "1", "2")));
        assertEquals(List.of("first", "last"), state.displayedEntries().stream().map(row -> row.spec().target()).toList());
    }

    @Test
    void mergesSavedFieldsAndPinsAnAutomaticRowToItsOriginalExecutor() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        WatchSpec health = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health", A);
        state.add(health);
        state.paused(1, 0);
        state.rememberExecutors(List.of(source(B)));
        WatchSpec quoted = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "\"Health\"", A);
        state.acceptChanges(1, 0, true, List.of(new WatchChange(quoted, value("20f", A), value("19f", A)), score("points", A, "3", "4")));
        var rows = state.displayedEntries();
        assertEquals(2, rows.size(), "saved Health and automatic quoted Health are one row");
        assertFalse(rows.getFirst().automatic());
        assertEquals("20f", rows.getFirst().displayedPreviousValue());
        assertEquals("19f", rows.getFirst().displayedResult().value());
        assertTrue(state.pinChange(rows.getLast().id()));
        rows = state.displayedEntries();
        assertEquals(2, rows.size());
        assertTrue(rows.stream().noneMatch(ClientWatchState.Entry::automatic));
        assertEquals(A, rows.getLast().spec().executor(), "pin uses the changed executor, not the selected executor B");
        assertEquals("3", rows.getLast().displayedPreviousValue());
    }

    @Test
    void separateExecutorsAndCreationRemovalRemainDistinct() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.paused(1, -1);
        WatchChange changed = score("points", A, "3", "4");
        WatchChange other = score("points", B, "5", "6");
        WatchSpec appeared = new WatchSpec(WatchSpec.Kind.SCORE, "new", "", A);
        state.acceptChanges(1, 0, true, List.of(changed, other,
            new WatchChange(appeared, WatchResult.absent(WatchResult.Status.VALUE_MISSING, "entity:" + A), value("0", A)),
            new WatchChange(new WatchSpec(WatchSpec.Kind.SCORE, "gone", "", A), value("7", A),
                WatchResult.absent(WatchResult.Status.VALUE_MISSING, "entity:" + A))));
        var rows = state.displayedEntries();
        assertEquals(4, rows.size());
        assertNotEquals(rows.get(0).displayedExecutor(), rows.get(1).displayedExecutor());
        assertEquals(ClientWatchState.Change.VALUE_APPEARED, rows.get(2).displayedChange());
        assertEquals(ClientWatchState.Change.VALUE_DISAPPEARED, rows.get(3).displayedChange());
        assertEquals("7", rows.get(3).displayedPreviousValue());
    }

    private static WatchChange score(String objective, UUID executor, String before, String after) {
        return new WatchChange(new WatchSpec(WatchSpec.Kind.SCORE, objective, "", executor), value(before, executor), value(after, executor));
    }

    private static WatchResult value(String value, UUID executor) {
        return new WatchResult(WatchResult.Status.VALUE, value, "entity:" + executor, "Pig");
    }

    private static PauseSource source(UUID executor) {
        return new PauseSource(new Vec3d(0, 64, 0), 0, 0, new EntityRef(executor, "Pig"), "minecraft:overworld");
    }
}
