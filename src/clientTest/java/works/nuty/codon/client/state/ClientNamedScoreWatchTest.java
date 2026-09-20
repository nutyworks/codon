package works.nuty.codon.client.state;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.EntityRef;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.Vec3d;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientNamedScoreWatchTest {
    private static final WatchSpec FOLLOWING = new WatchSpec(WatchSpec.Kind.SCORE, "points", "");
    private static final WatchSpec ALICE_POINTS = WatchSpec.scoreHolder("points", "Alice");
    private static final String ALICE_KEY = "score-holder:Alice";

    @Test
    void namedHolderIsFixedWithoutDisplayingOrFollowingTheSelectedEntity() {
        UUID selected = UUID.randomUUID();
        ClientWatchState state = new ClientWatchState(() -> 0);
        assertTrue(ALICE_POINTS.isPinned());
        assertNull(ALICE_POINTS.executor());
        assertTrue(state.add(ALICE_POINTS));
        state.paused(1, 0);
        state.rememberExecutors(List.of(source(selected, "Selected")));

        ClientWatchState.Query query = onlyQuery(state);
        assertEquals(-1, query.sourceIndex());
        assertNull(query.capturedEntity());
        assertEquals(ALICE_POINTS, query.spec());
        state.accept(1, query.requestId(), value("7", ALICE_KEY));

        ClientWatchState.Entry entry = onlyEntry(state);
        assertEquals("7", entry.result().value());
        assertNull(entry.displayedExecutor());
        assertEquals("", entry.executorName());
        state.selectSource(1);
        assertEquals("7", onlyEntry(state).result().value());
        assertTrue(state.drainQueries().isEmpty(), "selection cannot invalidate a fixed holder read");
    }

    @Test
    void namedHolderStepsWithoutASourceAndTracksItsOwnAvailabilityHistory() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.add(ALICE_POINTS);
        state.paused(1, -1);
        ClientWatchState.Query initial = onlyQuery(state);
        assertFixedHolderQuery(initial);
        state.accept(1, initial.requestId(), value("0", ALICE_KEY));

        state.stepping();
        state.paused(2, -1);
        ClientWatchState.Query changed = onlyQuery(state);
        assertFixedHolderQuery(changed);
        state.accept(2, changed.requestId(), value("3", ALICE_KEY));
        assertEquals(ClientWatchState.Change.VALUE_CHANGED, onlyEntry(state).change());
        assertEquals("0", onlyEntry(state).previousValue());

        state.stepping();
        state.paused(3, -1);
        ClientWatchState.Query missing = onlyQuery(state);
        assertFixedHolderQuery(missing);
        state.accept(3, missing.requestId(), WatchResult.absent(WatchResult.Status.VALUE_MISSING, ALICE_KEY));
        assertEquals(ClientWatchState.Change.VALUE_DISAPPEARED, onlyEntry(state).change());
        assertEquals("3", onlyEntry(state).previousValue());

        state.stepping();
        state.paused(4, -1);
        ClientWatchState.Query restored = onlyQuery(state);
        assertFixedHolderQuery(restored);
        state.accept(4, restored.requestId(), value("5", ALICE_KEY));
        assertEquals(ClientWatchState.Change.VALUE_APPEARED, onlyEntry(state).change());
        assertEquals("", onlyEntry(state).previousValue());
    }

    @Test
    void distinctHoldersCoexistWithAFollowingWatchAndUnpinRejectsTheFixedRequestReply() {
        WatchSpec bob = WatchSpec.scoreHolder("points", "Bob");
        ClientWatchState state = new ClientWatchState(() -> 0);
        assertTrue(state.add(ALICE_POINTS));
        assertTrue(state.add(bob));
        assertTrue(state.add(FOLLOWING));
        state.paused(1, 0);
        long aliceId = entry(state, ALICE_POINTS).id();
        List<ClientWatchState.Query> initial = state.drainQueries();
        assertEquals(3, initial.size());
        ClientWatchState.Query stale = initial.stream().filter(query -> query.spec().equals(ALICE_POINTS)).findFirst().orElseThrow();
        ClientWatchState.Query current = initial.stream().filter(query -> query.spec().equals(FOLLOWING)).findFirst().orElseThrow();

        assertTrue(state.unpin(aliceId));
        assertEquals(List.of(bob, FOLLOWING), state.definitions(), "unpinning a named holder merges into its following score watch");
        assertEquals(0, current.sourceIndex());
        assertTrue(state.drainQueries().isEmpty(), "the existing following row and its in-flight request survive the merge");
        state.accept(1, stale.requestId(), value("99", ALICE_KEY));
        assertNull(entry(state, FOLLOWING).result(), "a removed fixed-target request cannot fill the following row");
        state.accept(1, current.requestId(), value("4", "entity:" + UUID.randomUUID()));
        assertEquals("4", entry(state, FOLLOWING).result().value());
    }

    @Test
    void groupingKeepsNamedHoldersSeparateByContextButJoinsScoresByObjectivePath() {
        UUID entity = UUID.randomUUID();
        WatchSpec aliceMoney = WatchSpec.scoreHolder("money", "Alice");
        WatchSpec bobPoints = WatchSpec.scoreHolder("points", "Bob");
        List<ClientWatchState.Entry> entries = List.of(
            entry(1, ALICE_POINTS, null),
            entry(2, aliceMoney, null),
            entry(3, bobPoints, null),
            entry(4, FOLLOWING, entity),
            entry(5, new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health"), entity));

        List<WatchGrouping.Group> context = WatchGrouping.groups(entries, WatchGrouping.Mode.CONTEXT, entity);
        assertEquals(new WatchGrouping.Key("score-holder", "Alice"), context.getFirst().key());
        assertEquals(List.of(1L, 2L), ids(context.getFirst()));
        assertEquals(new WatchGrouping.Key("score-holder", "Bob"), context.get(1).key());
        assertEquals(List.of(3L), ids(context.get(1)));
        assertEquals(List.of(4L, 5L), ids(context.get(2)), "real entity watches remain outside named-holder contexts");

        List<WatchGrouping.Group> path = WatchGrouping.groups(entries, WatchGrouping.Mode.PATH, entity);
        assertEquals(List.of(1L, 3L, 4L), ids(path.getFirst()),
            "all score forms for one objective share the same path group");
        assertEquals(List.of(2L), ids(path.get(1)));
    }

    private static void assertFixedHolderQuery(ClientWatchState.Query query) {
        assertEquals(-1, query.sourceIndex());
        assertNull(query.capturedEntity(), "a fixed score holder never triggers an outgoing entity completion read");
    }

    private static ClientWatchState.Query onlyQuery(ClientWatchState state) {
        List<ClientWatchState.Query> queries = state.drainQueries();
        assertEquals(1, queries.size());
        return queries.getFirst();
    }

    private static ClientWatchState.Entry onlyEntry(ClientWatchState state) {
        return state.entries().getFirst();
    }

    private static ClientWatchState.Entry entry(ClientWatchState state, WatchSpec spec) {
        return state.entries().stream().filter(entry -> entry.spec().equals(spec)).findFirst().orElseThrow();
    }

    private static ClientWatchState.Entry entry(long id, WatchSpec spec, UUID displayedEntity) {
        return new ClientWatchState.Entry(id, spec, null, ClientWatchState.Change.UNCHANGED, "", null,
            displayedEntity, displayedEntity == null ? "" : "Entity");
    }

    private static List<Long> ids(WatchGrouping.Group group) {
        return group.entries().stream().map(ClientWatchState.Entry::id).toList();
    }

    private static PauseSource source(UUID uuid, String name) {
        return new PauseSource(new Vec3d(0, 64, 0), 0, 0, new EntityRef(uuid, name), "overworld");
    }

    private static WatchResult value(String value, String key) {
        return new WatchResult(WatchResult.Status.VALUE, value, key);
    }
}
