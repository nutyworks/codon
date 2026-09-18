package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientWatchPersistenceStateTest {
    private static final WatchSpec SCORE = new WatchSpec(WatchSpec.Kind.SCORE, "points", "");
    private static final WatchSpec ENTITY = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health");
    private static final WatchSpec STORAGE = new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "example:data", "value");

    @Test
    void notifiesOnlySuccessfulUserDefinitionEdits() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        List<List<WatchSpec>> notifications = new ArrayList<>();
        state.setChangeListener(definitions -> notifications.add(List.copyOf(definitions)));

        assertTrue(state.add(SCORE));
        assertFalse(state.add(SCORE));
        long id = state.entries().getFirst().id();
        state.remove(id);
        state.remove(id);

        assertEquals(List.of(List.of(SCORE), List.of()), notifications);
    }

    @Test
    void restoreAndResetAreSilentAndReplaceDefinitionsWithoutValues() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        List<List<WatchSpec>> notifications = new ArrayList<>();
        state.setChangeListener(definitions -> notifications.add(List.copyOf(definitions)));
        assertTrue(state.add(SCORE));
        state.paused(1, 0);
        ClientWatchState.Query request = state.drainQueries().getFirst();
        state.accept(1, request.requestId(), new WatchResult(WatchResult.Status.VALUE, "7", "entity:00000000-0000-0000-0000-000000000001"));

        state.restoreDefinitions(List.of(ENTITY, STORAGE));
        assertEquals(List.of(ENTITY, STORAGE), state.definitions());
        assertTrue(state.entries().stream().allMatch(entry -> entry.result() == null));
        assertEquals(List.of(List.of(SCORE)), notifications);

        state.reset();
        assertTrue(state.definitions().isEmpty());
        assertEquals(List.of(List.of(SCORE)), notifications);
    }

    @Test
    void invalidRestoreIsAtomicAndSilent() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        List<List<WatchSpec>> notifications = new ArrayList<>();
        state.setChangeListener(definitions -> notifications.add(List.copyOf(definitions)));
        assertTrue(state.add(SCORE));

        assertThrows(IllegalArgumentException.class, () -> state.restoreDefinitions(List.of(SCORE, SCORE)));

        assertEquals(List.of(SCORE), state.definitions());
        assertEquals(List.of(List.of(SCORE)), notifications);
    }

    @Test
    void restoresAndExtendsLargeDefinitionSetsWithoutReintroducingDuplicates() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        List<WatchSpec> restored = java.util.stream.IntStream.range(0, 16)
            .mapToObj(index -> new WatchSpec(WatchSpec.Kind.SCORE, "restored-" + index, "")).toList();
        state.restoreDefinitions(restored);
        List<List<WatchSpec>> notifications = new ArrayList<>();
        state.setChangeListener(definitions -> notifications.add(List.copyOf(definitions)));
        List<WatchSpec> additions = List.of(restored.get(2), restored.get(12),
            new WatchSpec(WatchSpec.Kind.SCORE, "added-0", ""),
            new WatchSpec(WatchSpec.Kind.SCORE, "added-1", ""),
            new WatchSpec(WatchSpec.Kind.SCORE, "added-0", ""));

        assertTrue(state.addAll(additions));
        List<WatchSpec> expected = new ArrayList<>(restored);
        expected.add(new WatchSpec(WatchSpec.Kind.SCORE, "added-0", ""));
        expected.add(new WatchSpec(WatchSpec.Kind.SCORE, "added-1", ""));
        assertEquals(expected, state.definitions());
        assertEquals(List.of(expected), notifications, "restored definitions are silent and the large extension persists once");
    }
}
