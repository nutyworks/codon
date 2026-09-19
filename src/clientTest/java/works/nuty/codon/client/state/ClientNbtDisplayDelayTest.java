package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ClientNbtDisplayDelayTest {
    private static final long GRACE = 250_000_000L;
    private static final EntityRef ENTITY = new EntityRef(UUID.randomUUID(), "Pig");

    @Test
    void rootDisplayExpiresFromTheFirstInvalidationWithoutSatisfyingTheNewRequest() {
        AtomicLong now = new AtomicLong();
        ClientNbtState state = state(now);
        reply(state, 1, page("", 0, 1, "old"));
        state.stepping();
        now.set(100_000_000L);
        state.paused(2, List.of(source(ENTITY)), 0);
        ClientNbtState.Query abandoned = query(state);
        now.set(150_000_000L);
        state.refresh();
        ClientNbtState.Query fresh = query(state);
        state.accept(2, abandoned.requestId(), page("", 0, 1, "stale"));

        now.set(GRACE - 1);
        assertPending(state.rows());
        assertEquals("old", shown(state).getFirst().node().preview());
        assertTrue(state.drainQueries().isEmpty());
        now.set(GRACE);
        assertPending(shown(state));
        state.accept(2, fresh.requestId(), page("", 0, 1, "0"));
        assertEquals("0", shown(state).getFirst().node().preview());
    }

    @Test
    void freshRootAndChildValuesReplaceIndependentlyDuringTheGracePeriod() {
        AtomicLong now = new AtomicLong();
        ClientNbtState state = state(now);
        NbtPage.Node branch = new NbtPage.Node("nested", "nested", "old root", true);
        reply(state, 1, new NbtPage(WatchResult.Status.VALUE, List.of(branch), 0, 1));
        assertTrue(state.toggle("nested"));
        reply(state, 1, page("nested", 0, 1, "old leaf"));
        state.stepping();
        state.paused(2, List.of(source(ENTITY)), 0);
        now.set(100_000_000L);
        reply(state, 2, new NbtPage(WatchResult.Status.VALUE,
            List.of(new NbtPage.Node("nested", "nested", "new root", true)), 0, 1));

        assertEquals("new root", shown(state).getFirst().node().preview());
        assertEquals("old leaf", shown(state).get(1).node().preview());
        assertNull(state.rows().get(1).status());
        assertEquals(ClientNbtState.Kind.STATUS, state.rows().get(1).kind());
        ClientNbtState.Query leaf = query(state);
        assertEquals("nested", leaf.path());
        now.set(GRACE - 1);
        state.accept(2, leaf.requestId(), page("nested", 0, 1, "0"));
        assertEquals("0", shown(state).get(1).node().preview());
        now.set(GRACE);
        assertEquals("0", shown(state).get(1).node().preview());
    }

    @Test
    void retainedFarPageRemainsSparseAndStillRequestsItsFreshReplacement() {
        AtomicLong now = new AtomicLong();
        ClientNbtState state = state(now);
        int total = 1_000_000;
        reply(state, 1, page("", 0, total, "old"));
        state.requestVisible(ENTITY.uuid(), total - 1, 1);
        ClientNbtState.Query last = query(state);
        reply(state, 1, last, page("", last.offset(), total, "old last"));
        state.stepping();
        state.paused(2, List.of(source(ENTITY)), 0);
        reply(state, 2, page("", 0, total, "new"));

        assertEquals(total, shown(state).size());
        assertEquals("old last", shown(state).get(total - 1).node().preview());
        assertEquals(ClientNbtState.Kind.PLACEHOLDER, state.rows().get(total - 1).kind());
        assertTrue(ClientNbtState.loadedIndices(shown(state)).size() <= 64);
        state.requestVisible(ENTITY.uuid(), total - 1, 1);
        ClientNbtState.Query replacement = query(state);
        assertEquals(last.offset(), replacement.offset());
        assertTrue(state.drainQueries().isEmpty(), "held nodes must not suppress or duplicate their new page query");
        now.set(GRACE - 1);
        reply(state, 2, replacement, page("", replacement.offset(), total, "new last"));
        assertEquals("new last", shown(state).get(total - 1).node().preview());
        now.set(GRACE);
        assertEquals("new last", shown(state).get(total - 1).node().preview());
    }

    @Test
    void firstReadWaitsImmediatelyAndFreshEmptyOrErrorPagesReplaceOldNodesImmediately() {
        ClientNbtState state = state(new AtomicLong());
        assertPending(shown(state));
        reply(state, 1, page("", 0, 1, "old"));
        state.refresh();
        reply(state, 1, new NbtPage(WatchResult.Status.VALUE, List.of(), 0, 0));
        assertEquals(ClientNbtState.Kind.EMPTY, shown(state).getFirst().kind());
        state.refresh();
        reply(state, 1, NbtPage.absent(WatchResult.Status.TARGET_MISSING));
        assertEquals(WatchResult.Status.TARGET_MISSING, shown(state).getFirst().status());
        assertTrue(state.canRefresh(ENTITY.uuid(), "", 0));
        state.refresh();
        assertEquals(WatchResult.Status.TARGET_MISSING, shown(state).getFirst().status());
        assertFalse(state.canRefresh(ENTITY.uuid(), "", 0), "a retained error must not enable another retry");
        query(state);
        assertTrue(state.drainQueries().isEmpty());
    }

    @Test
    void removedBranchesDoNotRemainVisibleOrGenerateQueriesAfterFreshRootData() {
        ClientNbtState state = state(new AtomicLong());
        reply(state, 1, new NbtPage(WatchResult.Status.VALUE,
            List.of(new NbtPage.Node("nested", "nested", "{}", true)), 0, 1));
        assertTrue(state.toggle("nested"));
        reply(state, 1, page("nested", 0, 1, "old leaf"));
        state.refresh();
        reply(state, 1, page("", 0, 1, "new"));
        assertEquals(1, shown(state).size());
        assertEquals("new", shown(state).getFirst().node().preview());
        state.requestVisible(ENTITY.uuid(), 0, 32);
        assertTrue(state.drainQueries().isEmpty());
    }

    @Test
    void retainedPagesNeverCrossExecutorsOrSurviveResumeAndReset() {
        ClientNbtState state = state(new AtomicLong());
        reply(state, 1, page("", 0, 1, "old"));
        EntityRef other = new EntityRef(UUID.randomUUID(), "Cow");
        state.stepping();
        state.paused(2, List.of(source(other)), 0);
        assertTrue(state.displayedRows(ENTITY.uuid()).isEmpty());
        assertPending(state.displayedRows(other.uuid()));

        state.resumed();
        state.paused(3, List.of(source(ENTITY)), 0);
        assertPending(shown(state));
        reply(state, 3, page("", 0, 1, "new"));
        state.refresh();
        state.reset();
        state.paused(4, List.of(source(ENTITY)), 0);
        assertPending(shown(state));
    }

    private static ClientNbtState state(AtomicLong now) {
        ClientNbtState state = new ClientNbtState(now::get);
        state.paused(1, List.of(source(ENTITY)), 0);
        return state;
    }

    private static List<ClientNbtState.Row> shown(ClientNbtState state) { return state.displayedRows(ENTITY.uuid()); }

    private static void assertPending(List<ClientNbtState.Row> rows) {
        assertEquals(1, rows.size());
        assertEquals(ClientNbtState.Kind.STATUS, rows.getFirst().kind());
        assertNull(rows.getFirst().status());
    }

    private static ClientNbtState.Query query(ClientNbtState state) {
        List<ClientNbtState.Query> queries = state.drainQueries();
        assertEquals(1, queries.size());
        return queries.getFirst();
    }

    private static void reply(ClientNbtState state, long pause, NbtPage page) { reply(state, pause, query(state), page); }
    private static void reply(ClientNbtState state, long pause, ClientNbtState.Query query, NbtPage page) {
        state.accept(pause, query.requestId(), page);
    }

    private static PauseSource source(EntityRef entity) {
        return new PauseSource(new Vec3d(0, 64, 0), 0, 0, entity, "overworld");
    }

    private static NbtPage page(String parent, int offset, int total, String value) {
        return new NbtPage(WatchResult.Status.VALUE, IntStream.range(offset, Math.min(total, offset + NbtPage.PAGE_SIZE))
            .mapToObj(i -> new NbtPage.Node("node" + i, parent + (parent.isEmpty() ? "" : ".") + "node" + i, value, false))
            .toList(), offset, total);
    }
}
