package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.EntityRef;
import works.nuty.codon.core.model.NbtPage;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.Vec3d;
import works.nuty.codon.core.model.WatchResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientNbtStateTest {
    @Test
    void queriesAllEntityRootsRegardlessOfSelectedSourceAndReusesThePauseCache() {
        EntityRef a = entity("a");
        EntityRef b = entity("b");
        ClientNbtState state = new ClientNbtState(() -> 0);
        state.paused(10, List.of(source(a), source(b), source(null)), -1);
        assertNull(state.executor(), "a non-entity selection has no selected NBT tree");
        assertEquals(List.of(new ClientNbtState.EntitySource(0, a), new ClientNbtState.EntitySource(1, b)),
            state.entitySources());
        List<ClientNbtState.Query> roots = state.drainQueries();
        assertEquals(2, roots.size());
        ClientNbtState.Query rootA = roots.stream().filter(query -> query.executor().equals(a.uuid())).findFirst().orElseThrow();
        ClientNbtState.Query rootB = roots.stream().filter(query -> query.executor().equals(b.uuid())).findFirst().orElseThrow();
        assertEquals("", rootA.path());
        state.accept(10, rootA.requestId(), page("a", "\"a\"", false));
        state.accept(10, rootB.requestId(), page("b", "\"b\"", false));

        state.selectSource(0);
        assertTrue(state.drainQueries().isEmpty(), "the same pause reuses A's root page");
        assertEquals(a, state.executor());

        state.selectSource(2);
        assertNull(state.executor());
        assertTrue(state.rows().isEmpty());
        assertTrue(state.drainQueries().isEmpty());

        state.paused(11, List.of(source(a), source(null)), 1);
        assertNull(state.executor(), "a selected position source cannot suppress its entity peer's root query");
        assertEquals(a.uuid(), onlyQuery(state).executor());
    }

    @Test
    void moreThanEightCurrentEntitiesAreLoadedInFourQueryBatchesWithoutEviction() {
        ClientNbtState state = new ClientNbtState(() -> 0);
        List<PauseSource> sources = new ArrayList<>();
        for (int i = 0; i < 9; i++) sources.add(source(entity("many-" + i)));
        state.paused(20, sources, -1);

        Set<UUID> queried = new HashSet<>();
        for (int batch = 0; batch < 3; batch++) {
            List<ClientNbtState.Query> queries = state.drainQueries();
            assertEquals(batch < 2 ? 4 : 1, queries.size(), "each drain is capped at four roots");
            for (ClientNbtState.Query query : queries) {
                assertTrue(queried.add(query.executor()), "each UUID receives one pending root request");
                state.accept(20, query.requestId(), page(query.executor().toString(), "\"root\"", false));
            }
        }

        assertEquals(9, queried.size());
        assertTrue(state.drainQueries().isEmpty());
        for (PauseSource source : sources) {
            assertFalse(state.rows(source.entity().uuid()).isEmpty(), "all current entities retain their loaded root page");
        }
    }

    @Test
    void repeatedEntityUuidKeepsEverySourceEntryButQueriesItsRootOnlyOnce() {
        EntityRef a = entity("same");
        ClientNbtState state = new ClientNbtState(() -> 0);
        state.paused(30, List.of(source(a), source(a)), -1);

        assertEquals(List.of(new ClientNbtState.EntitySource(0, a), new ClientNbtState.EntitySource(1, a)),
            state.entitySources());
        List<ClientNbtState.Query> queries = state.drainQueries();
        assertEquals(1, queries.size());
        assertEquals(0, queries.getFirst().sourceIndex(), "the first visible source owns the deduplicated root request");
    }

    @Test
    void uuidOperationsWorkForAnUnselectedTreeWithoutChangingOtherEntityTrees() {
        EntityRef a = entity("a");
        EntityRef b = entity("b");
        ClientNbtState state = new ClientNbtState(() -> 0);
        state.paused(40, List.of(source(a), source(b)), 1);
        List<ClientNbtState.Query> roots = state.drainQueries();
        ClientNbtState.Query rootA = roots.stream().filter(query -> query.executor().equals(a.uuid())).findFirst().orElseThrow();
        ClientNbtState.Query rootB = roots.stream().filter(query -> query.executor().equals(b.uuid())).findFirst().orElseThrow();
        NbtPage.Node branch = new NbtPage.Node("nested", "\"nested\"", "{}", true);
        state.accept(40, rootA.requestId(), new NbtPage(WatchResult.Status.VALUE, List.of(branch), 0, 1));
        state.accept(40, rootB.requestId(), manyNodes(0, 33));

        assertEquals(b, state.executor());
        assertTrue(state.toggle(a.uuid(), branch.path()));
        ClientNbtState.Query expandedA = onlyQuery(state);
        assertEquals(a.uuid(), expandedA.executor());
        assertEquals(branch.path(), expandedA.path());
        state.accept(40, expandedA.requestId(), page("leaf", branch.path() + ".\"leaf\"", false));
        ClientNbtState.Row bNext = state.rows(b.uuid()).stream().filter(row -> row.kind() == ClientNbtState.Kind.NEXT).findFirst().orElseThrow();
        assertTrue(state.page(b.uuid(), "", bNext.targetOffset()));
        ClientNbtState.Query pageB = onlyQuery(state);
        assertEquals(b.uuid(), pageB.executor());
        state.refresh(a.uuid());
        ClientNbtState.Query refreshedA = onlyQuery(state);
        assertEquals(a.uuid(), refreshedA.executor());
        assertEquals("", refreshedA.path());
        assertFalse(state.rows(b.uuid()).isEmpty(), "refreshing A must not discard B's loaded tree");
    }

    @Test
    void replyForAnEntityRemovedFromTheCurrentSourcesIsIgnored() {
        EntityRef a = entity("removed");
        EntityRef b = entity("current");
        ClientNbtState state = new ClientNbtState(() -> 0);
        state.paused(50, List.of(source(a)), 0);
        ClientNbtState.Query removedRequest = onlyQuery(state);
        state.paused(50, List.of(source(b)), 0);
        ClientNbtState.Query currentRequest = onlyQuery(state);

        state.accept(50, removedRequest.requestId(), page("stale", "\"stale\"", false));

        assertTrue(state.rows(b.uuid()).stream().anyMatch(row -> row.kind() == ClientNbtState.Kind.STATUS && row.status() == null));
        state.accept(50, currentRequest.requestId(), page("current", "\"current\"", false));
        assertTrue(state.rows(b.uuid()).stream().anyMatch(row -> row.path().equals("\"current\"")));
    }

    @Test
    void expandsOnlyVisibleBranchesAndReloadsExpandedPathsLazilyAtTheNextPause() {
        EntityRef a = entity("a");
        ClientNbtState state = new ClientNbtState(() -> 0);
        state.paused(1, List.of(source(a)), 0);
        ClientNbtState.Query root = onlyQuery(state);
        NbtPage.Node branch = new NbtPage.Node("nested", "\"nested\"", "{}", true);
        state.accept(1, root.requestId(), new NbtPage(WatchResult.Status.VALUE, List.of(branch), 0, 1));
        assertTrue(state.toggle(branch.path()));
        ClientNbtState.Query expanded = onlyQuery(state);
        assertEquals(branch.path(), expanded.path());
        state.accept(1, expanded.requestId(), page("leaf", branch.path() + ".\"leaf\"", false));
        assertTrue(state.rows().stream().anyMatch(row -> row.kind() == ClientNbtState.Kind.NODE && row.path().equals(branch.path()) && row.expanded()));

        state.stepping();
        state.paused(2, List.of(source(a)), 0);
        ClientNbtState.Query nextRoot = onlyQuery(state);
        state.accept(2, nextRoot.requestId(), new NbtPage(WatchResult.Status.VALUE, List.of(branch), 0, 1));
        ClientNbtState.Query lazilyReloadedBranch = onlyQuery(state);
        assertEquals(branch.path(), lazilyReloadedBranch.path(), "expanded branch waits for its parent page before reloading");

        state.resumed();
        assertTrue(state.rows().isEmpty());
        state.paused(3, List.of(source(a)), 0);
        ClientNbtState.Query afterResume = onlyQuery(state);
        state.accept(3, afterResume.requestId(), new NbtPage(WatchResult.Status.VALUE, List.of(branch), 0, 1));
        assertEquals(branch.path(), onlyQuery(state).path(), "resume preserves expansion but never reuses stale page data");
    }

    @Test
    void pagesKnownBranchesWithExplicitNextAndPreviousOffsets() {
        EntityRef a = entity("a");
        ClientNbtState state = new ClientNbtState(() -> 0);
        state.paused(1, List.of(source(a)), 0);
        ClientNbtState.Query root = onlyQuery(state);
        state.accept(1, root.requestId(), manyNodes(0, 33));
        ClientNbtState.Row next = state.rows().stream().filter(row -> row.kind() == ClientNbtState.Kind.NEXT).findFirst().orElseThrow();
        assertEquals(32, next.targetOffset());
        assertTrue(state.page("", next.targetOffset()));
        ClientNbtState.Query secondPage = onlyQuery(state);
        assertEquals(32, secondPage.offset());
        state.accept(1, secondPage.requestId(), manyNodes(32, 33));
        assertTrue(state.rows().stream().anyMatch(row -> row.kind() == ClientNbtState.Kind.PREVIOUS && row.targetOffset() == 0));
        assertFalse(state.page("\"unknown\"", 0), "only branches visible in the current tree can be paged");
    }

    @Test
    void rejectsLateRepliesAndMakesTimeoutRetryAnExplicitRefresh() {
        AtomicLong now = new AtomicLong();
        EntityRef a = entity("a");
        ClientNbtState state = new ClientNbtState(now::get);
        state.paused(1, List.of(source(a)), 0);
        ClientNbtState.Query stale = onlyQuery(state);
        state.stepping();
        state.accept(1, stale.requestId(), page("late", "\"late\"", false));
        state.paused(2, List.of(source(a)), 0);
        ClientNbtState.Query current = onlyQuery(state);
        state.accept(1, current.requestId(), page("wrong-pause", "\"wrong\"", false));
        assertTrue(state.rows().stream().anyMatch(row -> row.kind() == ClientNbtState.Kind.STATUS && row.status() == null), "old reply cannot complete the current request");

        now.addAndGet(5_000_000_000L);
        assertTrue(state.rows().stream().anyMatch(row -> row.kind() == ClientNbtState.Kind.STATUS
            && row.status() == WatchResult.Status.UNAVAILABLE), "timeout is shown as unavailable");
        assertTrue(state.drainQueries().isEmpty(), "timeout does not retry while the server is paused");
        state.refresh();
        ClientNbtState.Query refreshed = onlyQuery(state);
        assertTrue(refreshed.requestId() != current.requestId());

        state.reset();
        state.accept(2, refreshed.requestId(), page("reset", "\"reset\"", false));
        assertTrue(state.rows().isEmpty());
    }

    @Test
    void enabledPreferenceSurvivesResumeAndResetWithoutHidingCurrentNbt() {
        EntityRef a = entity("a");
        ClientNbtState state = new ClientNbtState(() -> 0);
        AtomicInteger notifications = new AtomicInteger();
        state.setEnabledListener(ignored -> notifications.incrementAndGet());
        state.setEnabled(false);
        state.setEnabled(false);
        assertEquals(1, notifications.get());
        state.paused(1, List.of(source(a)), 0);
        ClientNbtState.Query pending = onlyQuery(state);
        state.setEnabled(true);
        assertTrue(state.enabled());
        state.accept(1, pending.requestId(), page("root", "\"root\"", false));
        state.resumed();
        assertTrue(state.enabled());
        state.setEnabled(false);
        state.reset();
        assertFalse(state.enabled());
        assertEquals(3, notifications.get(), "reset does not mutate the persisted enabled preference");
    }

    @Test
    void uuidExpansionAndCollapseSurviveReorderNoEntityAndContinueButResetClearsThem() {
        EntityRef a = entity("a");
        EntityRef b = entity("b");
        ClientNbtState state = new ClientNbtState(() -> 0);
        NbtPage.Node branch = new NbtPage.Node("nested", "\"nested\"", "{}", true);
        state.paused(1, List.of(source(a), source(b)), 0);
        ClientNbtState.Query rootA = state.drainQueries().stream().filter(query -> query.executor().equals(a.uuid())).findFirst().orElseThrow();
        state.accept(1, rootA.requestId(), new NbtPage(WatchResult.Status.VALUE, List.of(branch), 0, 1));
        assertTrue(state.toggle(a.uuid(), branch.path()));
        assertTrue(state.toggleSource(a.uuid()));
        assertFalse(state.sourceExpanded(a.uuid()));
        assertFalse(state.rows(a.uuid()).isEmpty(), "legacy source collapse cannot hide active NBT rows");

        state.stepping();
        state.paused(2, List.of(source(null), source(b), source(a)), 0);
        assertFalse(state.sourceExpanded(a.uuid()), "collapse belongs to UUID, not the reordered source index");
        assertTrue(state.sourceExpanded(b.uuid()));
        assertTrue(state.toggleSource(a.uuid()));
        ClientNbtState.Query rootAfterContinue = state.drainQueries().stream()
            .filter(query -> query.executor().equals(a.uuid()) && query.path().isEmpty()).findFirst().orElseThrow();
        state.accept(2, rootAfterContinue.requestId(), new NbtPage(WatchResult.Status.VALUE, List.of(branch), 0, 1));
        assertEquals(branch.path(), state.drainQueries().stream().filter(query -> query.executor().equals(a.uuid())).findFirst().orElseThrow().path());

        state.paused(3, List.of(source(null)), 0);
        assertTrue(state.sourceExpanded(a.uuid()), "a temporary no-entity stop does not discard dormant UUID state");
        state.paused(4, List.of(source(a)), 0);
        assertTrue(state.sourceExpanded(a.uuid()));
        state.reset();
        assertTrue(state.sourceExpanded(a.uuid()), "evicted reset state defaults to expanded");
        state.paused(5, List.of(source(a)), 0);
        ClientNbtState.Query rootAfterReset = onlyQuery(state);
        state.accept(5, rootAfterReset.requestId(), new NbtPage(WatchResult.Status.VALUE, List.of(branch), 0, 1));
        assertTrue(state.drainQueries().isEmpty(), "reset clears retained expanded paths");
    }

    @Test
    void dormantUuidPresentationStateIsBoundedWithoutLimitingCurrentSources() {
        ClientNbtState state = new ClientNbtState(() -> 0);
        EntityRef first = entity("dormant-0");
        EntityRef retained = null;
        for (int index = 0; index < 258; index++) {
            EntityRef entity = index == 0 ? first : entity("dormant-" + index);
            retained = entity;
            state.paused(index + 1L, List.of(source(entity)), 0);
            assertTrue(state.toggleSource(entity.uuid()));
        }
        assertTrue(state.sourceExpanded(first.uuid()), "oldest dormant UUID is evicted and returns to the default expanded state");
        assertFalse(state.sourceExpanded(retained.uuid()), "current UUID is never evicted by the dormant bound");
    }

    private static ClientNbtState.Query onlyQuery(ClientNbtState state) {
        return state.drainQueries().getFirst();
    }

    private static EntityRef entity(String name) {
        return new EntityRef(UUID.nameUUIDFromBytes(name.getBytes()), name);
    }

    private static PauseSource source(EntityRef entity) {
        return new PauseSource(new Vec3d(0, 64, 0), 0, 0, entity, "overworld");
    }

    private static NbtPage page(String name, String path, boolean expandable) {
        return new NbtPage(WatchResult.Status.VALUE, List.of(new NbtPage.Node(name, path, "value", expandable)), 0, 1);
    }

    private static NbtPage manyNodes(int offset, int total) {
        List<NbtPage.Node> nodes = new ArrayList<>();
        for (int i = offset; i < Math.min(total, offset + NbtPage.PAGE_SIZE); i++) {
            nodes.add(new NbtPage.Node("node" + i, "\"node" + i + "\"", "value", false));
        }
        return new NbtPage(WatchResult.Status.VALUE, nodes, offset, total);
    }
}
