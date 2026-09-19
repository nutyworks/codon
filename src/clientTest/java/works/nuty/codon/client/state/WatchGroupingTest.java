package works.nuty.codon.client.state;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;
import static org.junit.jupiter.api.Assertions.*;

class WatchGroupingTest {
    @Test void pathGroupsStorageByNamespaceLikeContextRegardlessOfPath() {
        var entries = List.of(
            entry(1, new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:state", "count"), null),
            entry(2, new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:other", "count"), null),
            entry(3, new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:state", "nested.value"), null));
        var groups = WatchGrouping.groups(entries, WatchGrouping.Mode.PATH, null);
        assertEquals(WatchGrouping.groups(entries, WatchGrouping.Mode.CONTEXT, null), groups);
        assertEquals(2, groups.size());
        assertEquals(new WatchGrouping.Key("storage", "demo:state"), groups.getFirst().key());
        assertEquals(List.of(entries.get(0), entries.get(2)), groups.getFirst().entries());
    }

    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2);
    private static ClientWatchState.Entry entry(long id, WatchSpec spec, UUID displayed) {
        return new ClientWatchState.Entry(id, spec, null, ClientWatchState.Change.UNCHANGED, "", null, displayed, "Same name");
    }
    private static ClientWatchState.Entry observed(long id, WatchSpec spec, WatchResult result,
                                                   ClientWatchState.Change change, UUID displayed) {
        return new ClientWatchState.Entry(id, spec, result, change, "", null, displayed, "Same name");
    }
    private static List<Long> ids(List<WatchGrouping.Group> groups) {
        return groups.stream().flatMap(group -> group.entries().stream()).map(ClientWatchState.Entry::id).toList();
    }
    @Test void contextUsesDisplayedIdentityAndStorageNamespaceNotNames() {
        var entries = List.of(
            entry(1, new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health"), A),
            entry(2, new WatchSpec(WatchSpec.Kind.SCORE, "points", "", A), null),
            entry(-1, new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health", B), B),
            entry(3, new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "a:state", "count"), null),
            entry(4, new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "b:state", "count"), null));
        var groups = WatchGrouping.groups(entries, WatchGrouping.Mode.CONTEXT, B);
        assertEquals(4, groups.size());
        assertEquals(List.of(1L, 2L), groups.getFirst().entries().stream().map(ClientWatchState.Entry::id).toList());
        assertEquals(A.toString(), groups.getFirst().key().value());
        assertEquals(B.toString(), groups.get(1).key().value());
    }
    @Test void pathCombinesEquivalentNbtAcrossEntitiesButSeparatesKindsAndStorages() {
        var entries = List.of(
            entry(1, new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health", A), A),
            entry(-1, new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "\"Health\"", B), B),
            entry(2, new WatchSpec(WatchSpec.Kind.SCORE, "Health", ""), A),
            entry(3, new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "a:state", "Health"), null),
            entry(4, new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "b:state", "Health"), null));
        var groups = WatchGrouping.groups(entries, WatchGrouping.Mode.PATH, null);
        assertEquals(4, groups.size());
        assertEquals(List.of(entries.get(0), entries.get(1)), groups.getFirst().entries());
        assertEquals(entries, WatchGrouping.groups(entries, WatchGrouping.Mode.NONE, null).getFirst().entries());
    }
    @Test void emptyAndUnresolvedContextsAreExplicitAndModesDoNotMutateDefinitions() {
        var state = new ClientWatchState(() -> 0);
        state.add(new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health"));
        var original = state.definitions();
        assertEquals("", WatchGrouping.groups(state.entries(), WatchGrouping.Mode.CONTEXT, null).getFirst().key().value());
        for (var mode : WatchGrouping.Mode.values()) {
            state.grouping(mode);
            assertEquals(mode, state.grouping());
            assertEquals(original, state.definitions());
            assertTrue(WatchGrouping.groups(List.of(), mode, null).isEmpty());
        }
    }
    @Test void inactiveMissingRowsMoveToTheEndOfTheUngroupedListWithoutReorderingEitherPartition() {
        var entries = List.of(
            observed(1, new WatchSpec(WatchSpec.Kind.SCORE, "one", ""), value(), ClientWatchState.Change.UNCHANGED, A),
            observed(2, new WatchSpec(WatchSpec.Kind.SCORE, "two", ""), missing(), ClientWatchState.Change.UNCHANGED, A),
            observed(3, new WatchSpec(WatchSpec.Kind.SCORE, "three", ""), missing(), ClientWatchState.Change.VALUE_DISAPPEARED, A),
            observed(4, new WatchSpec(WatchSpec.Kind.SCORE, "four", ""), noExecutor(), ClientWatchState.Change.INITIAL, A),
            observed(5, new WatchSpec(WatchSpec.Kind.SCORE, "five", ""), noExecutor(), ClientWatchState.Change.TARGET_CHANGED, A),
            observed(6, new WatchSpec(WatchSpec.Kind.SCORE, "six", ""), missing(), ClientWatchState.Change.AVAILABILITY_CHANGED, A),
            new ClientWatchState.Entry(7, new WatchSpec(WatchSpec.Kind.SCORE, "seven", ""), null,
                ClientWatchState.Change.INITIAL, "", null, A, "Same name"));
        assertEquals(List.of(1L, 3L, 5L, 6L, 7L, 2L, 4L), ids(WatchGrouping.groups(entries, WatchGrouping.Mode.NONE, A)));
        assertTrue(WatchGrouping.isUnchangedMissing(entries.get(1)));
        assertTrue(WatchGrouping.isUnchangedMissing(entries.get(3)));
        assertFalse(WatchGrouping.isUnchangedMissing(entries.get(2)), "a real disappearance stays active");
        assertFalse(WatchGrouping.isUnchangedMissing(entries.get(4)), "a target change stays active");
        assertFalse(WatchGrouping.isUnchangedMissing(entries.get(5)), "an availability transition stays active");
        assertFalse(WatchGrouping.isUnchangedMissing(entries.get(6)), "a pending row stays active");
    }
    @Test void groupedModesMoveInactiveRowsWithinRealGroupsAndMoveInactiveSingletonGroupsLast() {
        var context = List.of(
            observed(1, new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health"), value(), ClientWatchState.Change.UNCHANGED, A),
            observed(2, new WatchSpec(WatchSpec.Kind.SCORE, "points", ""), missing(), ClientWatchState.Change.UNCHANGED, A),
            observed(3, new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Air"), value(), ClientWatchState.Change.INITIAL, A),
            observed(4, new WatchSpec(WatchSpec.Kind.SCORE, "other", ""), noExecutor(), ClientWatchState.Change.UNCHANGED, B),
            observed(5, new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:state", "count"), value(), ClientWatchState.Change.UNCHANGED, null),
            observed(6, new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Fire"), missing(), ClientWatchState.Change.INITIAL, null));
        var contextGroups = WatchGrouping.groups(context, WatchGrouping.Mode.CONTEXT, null);
        assertEquals(List.of(1L, 3L, 2L, 5L, 4L, 6L), ids(contextGroups));
        assertEquals(List.of(1L, 3L, 2L), contextGroups.getFirst().entries().stream().map(ClientWatchState.Entry::id).toList(),
            "an inactive member follows its active context siblings");

        var path = List.of(
            observed(10, new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health"), missing(), ClientWatchState.Change.UNCHANGED, A),
            observed(11, new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "\"Health\""), value(), ClientWatchState.Change.INITIAL, B),
            observed(12, new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:quiet", "Health"), noExecutor(), ClientWatchState.Change.INITIAL, null),
            observed(13, new WatchSpec(WatchSpec.Kind.SCORE, "Health", ""), value(), ClientWatchState.Change.INITIAL, A));
        assertEquals(List.of(11L, 10L, 13L, 12L), ids(WatchGrouping.groups(path, WatchGrouping.Mode.PATH, null)),
            "the active same-path member leads its real group while an earlier inactive singleton moves after later active groups");
    }
    @Test void missingClassificationUsesTheDisplayedCompletedStepRatherThanTheRawResult() {
        var completedMissing = new ClientWatchState.Entry(1, new WatchSpec(WatchSpec.Kind.SCORE, "points", ""),
            value(), ClientWatchState.Change.VALUE_CHANGED, "", new ClientWatchState.Observation(missing(), ClientWatchState.Change.UNCHANGED, ""), A, "Same name");
        var completedValue = new ClientWatchState.Entry(2, new WatchSpec(WatchSpec.Kind.SCORE, "points2", ""),
            missing(), ClientWatchState.Change.UNCHANGED, "", new ClientWatchState.Observation(value(), ClientWatchState.Change.INITIAL, ""), A, "Same name");
        assertTrue(WatchGrouping.isUnchangedMissing(completedMissing));
        assertFalse(WatchGrouping.isUnchangedMissing(completedValue));
    }
    private static WatchResult value() { return new WatchResult(WatchResult.Status.VALUE, "1", "entity:" + A); }
    private static WatchResult missing() { return WatchResult.absent(WatchResult.Status.VALUE_MISSING, "entity:" + A); }
    private static WatchResult noExecutor() { return WatchResult.absent(WatchResult.Status.NO_EXECUTOR, "no-executor"); }
}
