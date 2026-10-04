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
    void rejectsDuplicateChildPathsBeforeTraversal() {
        ClientNbtState state = new ClientNbtState(() -> 0);
        state.paused(1, List.of(source(entity("hostile"))), 0);
        var query = onlyQuery(state);
        var node = new NbtPage.Node("x", "x", "{}", true);
        state.accept(1, query.requestId(), new NbtPage(WatchResult.Status.VALUE, List.of(node, node), 0, 2));
        assertEquals(1, state.rows().size());
        assertEquals(WatchResult.Status.INVALID_PATH, state.rows().getFirst().status());
        assertTrue(state.drainQueries().isEmpty());
    }

    @Test
    void rejectsSelfAncestorSiblingAndSkippedDescendantPathsInBothRowViews() {
        for (String child : List.of("x", "other", "xy.child", "x.child.grandchild", "x[0][1]", "x.[0]")) {
            ClientNbtState state = new ClientNbtState(() -> 0);
            EntityRef entity = entity("hostile");
            state.paused(1, List.of(source(entity)), 0);
            state.accept(1, onlyQuery(state).requestId(), page("x", "x", true));
            assertTrue(state.toggle("x"));
            var request = onlyQuery(state);
            var node = new NbtPage.Node("hostile", child, "{}", true);
            state.accept(1, request.requestId(), new NbtPage(WatchResult.Status.VALUE, List.of(node, node), 0, 2));
            assertEquals(2, state.rows().size(), child);
            assertEquals(WatchResult.Status.INVALID_PATH, state.rows().getLast().status(), child);
            assertEquals(state.rows(), state.displayedRows(entity.uuid()));
            assertTrue(state.drainQueries().isEmpty());
        }
    }

    @Test
    void rejectsCrossPageAliasesAndInconsistentTotalsWithoutReplacingLoadedChildren() {
        ClientNbtState state = new ClientNbtState(() -> 0);
        state.paused(1, List.of(source(entity("paging"))), 0);
        state.accept(1, onlyQuery(state).requestId(), manyNodes(0, 64));
        assertTrue(state.page("", 32));
        state.accept(1, onlyQuery(state).requestId(), new NbtPage(WatchResult.Status.VALUE,
            List.of(new NbtPage.Node("alias", "node0", "{}", true)), 32, 64));
        assertEquals(WatchResult.Status.INVALID_PATH, state.rows().get(32).status());
        assertEquals("\"node0\"", state.rows().getFirst().path());
        state.refresh();
        state.accept(1, onlyQuery(state).requestId(), manyNodes(0, 64));
        state.page("", 32);
        state.accept(1, onlyQuery(state).requestId(), manyNodes(32, 65));
        assertEquals(64, state.rows().size());
        assertEquals(WatchResult.Status.INVALID_PATH, state.rows().get(32).status());
    }

    @Test
    void acceptsQuotedUnicodeEscapesEmptyKeysAndInaccessibleLeaves() {
        ClientNbtState state = new ClientNbtState(() -> 0);
        state.paused(1, List.of(source(entity("quoted"))), 0);
        var nodes = List.of(new NbtPage.Node("unicode", "\"한.😀\"", "{}", true),
            new NbtPage.Node("quote", "\"a\\\"b\\\\c\"", "{}", false),
            new NbtPage.Node("empty", "\"\"", "{}", false),
            new NbtPage.Node("inaccessible1", "", "{}", false),
            new NbtPage.Node("inaccessible2", "", "{}", false));
        state.accept(1, onlyQuery(state).requestId(), new NbtPage(WatchResult.Status.VALUE, nodes, 0, nodes.size()));
        assertEquals(nodes, state.rows().stream().map(ClientNbtState.Row::node).toList());
        assertTrue(state.toggle(nodes.getFirst().path()));
        state.accept(1, onlyQuery(state).requestId(), page("index", nodes.getFirst().path() + "[0]", false));
        assertEquals(nodes.size() + 1, state.rows().size());
    }

    @Test
    void capsMaterializedTraversalWhileKeepingSparsePageJumpsCheap() {
        ClientNbtState state = new ClientNbtState(() -> 0);
        state.paused(1, List.of(source(entity("budget"))), 0);
        int total = ClientNbtState.MAX_MATERIALIZED_ROWS + NbtPage.PAGE_SIZE;
        state.accept(1, onlyQuery(state).requestId(), manyNodes(0, total));
        for (int offset = NbtPage.PAGE_SIZE; offset < total; offset += NbtPage.PAGE_SIZE) {
            assertTrue(state.page("", offset));
            state.accept(1, onlyQuery(state).requestId(), manyNodes(offset, total));
        }
        var rows = state.rows();
        assertEquals(ClientNbtState.MAX_MATERIALIZED_ROWS + 1, ClientNbtState.loadedIndices(rows).size());
        assertEquals(WatchResult.Status.TOO_LARGE, rows.getLast().status());
        assertTrue(state.drainQueries().isEmpty());
    }

    @Test
    void countsSparsePageTraversalEvenWhenPlaceholdersCollapseIntoOneRun() {
        ClientNbtState state = new ClientNbtState(() -> 0);
        state.paused(1, List.of(source(entity("sparse-budget"))), 0);
        state.accept(1, onlyQuery(state).requestId(), manyNodes(0, 1_000_000));
        // Public viewport paging can leave many pending sparse pages, all displayed as one gap.
        for (int i = 1; i <= ClientNbtState.MAX_TRAVERSAL_STEPS; i++)
            assertTrue(state.page("", i * NbtPage.PAGE_SIZE));
        var rows = state.rows();
        assertEquals(WatchResult.Status.TOO_LARGE, rows.getLast().status());
        assertTrue(ClientNbtState.loadedIndices(rows).size() <= ClientNbtState.MAX_MATERIALIZED_ROWS + 1);
    }

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
        assertTrue(state.page(b.uuid(), "", NbtPage.PAGE_SIZE));
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
    void preservesVirtualRowCountAndAutomaticallyLoadsOnlyTheVisibleRootPages() {
        EntityRef a = entity("a");
        ClientNbtState state = new ClientNbtState(() -> 0);
        state.paused(1, List.of(source(a)), 0);
        ClientNbtState.Query root = onlyQuery(state);
        state.accept(1, root.requestId(), manyNodes(0, 65));

        List<ClientNbtState.Row> initial = state.rows();
        assertEquals(65, initial.size(), "the scrollbar receives one stable logical row per root child");
        assertEquals(32, initial.stream().filter(row -> row.kind() == ClientNbtState.Kind.NODE).count());
        assertEquals(33, initial.stream().filter(row -> row.kind() == ClientNbtState.Kind.PLACEHOLDER).count());
        assertEquals(33, ClientNbtState.hiddenCount(initial, 32));
        assertEquals(17, ClientNbtState.hiddenCount(initial, 48));
        assertTrue(initial.stream().filter(row -> row.kind() == ClientNbtState.Kind.PLACEHOLDER)
            .allMatch(row -> row.path().isEmpty() && (row.targetOffset() == 32 || row.targetOffset() == 64)));
        assertTrue(initial.stream().allMatch(row -> row.kind() == ClientNbtState.Kind.NODE || row.kind() == ClientNbtState.Kind.PLACEHOLDER),
            "virtual children replace pagination-control rows");

        state.requestVisible(a.uuid(), 48, 8);
        assertEquals(33, ClientNbtState.hiddenCount(state.rows(), 32), "queueing a page must not split the +N marker");
        ClientNbtState.Query middle = onlyQuery(state);
        assertEquals(NbtPage.PAGE_SIZE, middle.offset(), "a viewport in the second page never requests the preceding page again");
        state.accept(1, middle.requestId(), manyNodes(NbtPage.PAGE_SIZE, 65));
        assertEquals(65, state.rows().size(), "loading a page must not move the scrollbar thumb");

        state.requestVisible(a.uuid(), 64, 1);
        ClientNbtState.Query last = onlyQuery(state);
        assertEquals(NbtPage.PAGE_SIZE * 2, last.offset(), "a far jump requests its aligned page without intermediate pages");
        state.accept(1, last.requestId(), manyNodes(NbtPage.PAGE_SIZE * 2, 65));
        assertEquals(65, state.rows().size());

        state.requestVisible(a.uuid(), 48, 8);
        assertTrue(state.drainQueries().isEmpty(), "revisiting a cached viewport does not refetch it");
    }

    @Test
    void queuesAlignedVisiblePagesOnceAndReservesNestedExpandedTotals() {
        EntityRef a = entity("a");
        ClientNbtState state = new ClientNbtState(() -> 0);
        state.paused(1, List.of(source(a)), 0);
        ClientNbtState.Query root = onlyQuery(state);
        NbtPage.Node nested = new NbtPage.Node("nested", "\"nested\"", "{}", true);
        state.accept(1, root.requestId(), new NbtPage(WatchResult.Status.VALUE, List.of(nested), 0, 1));
        assertTrue(state.toggle(nested.path()));
        ClientNbtState.Query nestedRoot = onlyQuery(state);
        state.accept(1, nestedRoot.requestId(), manyNodes(nested.path(), 0, 65));

        List<ClientNbtState.Row> initial = state.rows();
        assertEquals(66, initial.size(), "the expanded node plus every nested child occupies a fixed logical row");
        assertEquals(nested.path(), initial.get(0).path());
        assertEquals(33, initial.stream().filter(row -> row.kind() == ClientNbtState.Kind.PLACEHOLDER).count());
        assertTrue(initial.stream().filter(row -> row.kind() == ClientNbtState.Kind.PLACEHOLDER)
            .allMatch(row -> row.path().equals(nested.path())));

        state.requestVisible(a.uuid(), 49, 5);
        assertFalse(state.page(a.uuid(), nested.path(), 33), "a visible viewport has already queued the aligned page");
        assertFalse(state.page(a.uuid(), nested.path(), 32), "the same pending page is deduplicated");
        ClientNbtState.Query second = onlyQuery(state);
        assertEquals(nested.path(), second.path());
        assertEquals(NbtPage.PAGE_SIZE, second.offset());
        state.accept(1, second.requestId(), manyNodes(nested.path(), NbtPage.PAGE_SIZE, 65));
        assertFalse(state.page(a.uuid(), nested.path(), 32), "a cached page is never queued twice");
        assertEquals(66, state.rows().size(), "nested page loading also preserves flattened scroll length");
    }

    @Test
    void farJumpQueuesOnlyTheAlignedLastPageOfAMillionChildBranch() {
        EntityRef a = entity("a");
        ClientNbtState state = new ClientNbtState(() -> 0);
        state.paused(1, List.of(source(a)), 0);
        ClientNbtState.Query root = onlyQuery(state);
        state.accept(1, root.requestId(), manyNodes(0, 1_000_000));

        assertEquals(1_000_000, state.rows().size());
        state.requestVisible(a.uuid(), 999_999, 1);
        ClientNbtState.Query last = onlyQuery(state);
        assertEquals(999_968, last.offset(), "a far viewport jump requests only its containing 32-child page");
        assertTrue(state.drainQueries().isEmpty(), "no intermediate pages are queued by the far jump");
        state.accept(1, last.requestId(), manyNodes(last.offset(), 1_000_000));

        assertEquals(1_000_000, state.rows().size(), "loading the final page preserves the million-row scroll extent");
        assertFalse(state.page(a.uuid(), "", 0), "the initially loaded first page remains cached");
    }

    @Test
    void timedOutLaterPageKeepsItsVirtualSlotsAndRejectsLateReplyUntilRefresh() {
        AtomicLong now = new AtomicLong();
        EntityRef a = entity("a");
        ClientNbtState state = new ClientNbtState(now::get);
        state.paused(1, List.of(source(a)), 0);
        ClientNbtState.Query root = onlyQuery(state);
        state.accept(1, root.requestId(), manyNodes(0, 65));
        state.requestVisible(a.uuid(), NbtPage.PAGE_SIZE, 1);
        ClientNbtState.Query later = onlyQuery(state);
        assertEquals(NbtPage.PAGE_SIZE, later.offset());

        now.addAndGet(5_000_000_000L);
        List<ClientNbtState.Row> timedOut = state.rows();
        assertEquals(65, timedOut.size(), "timing out a later page must not change the scroll extent");
        assertTrue(timedOut.subList(NbtPage.PAGE_SIZE, NbtPage.PAGE_SIZE * 2).stream()
            .allMatch(row -> row.kind() == ClientNbtState.Kind.PLACEHOLDER && row.status() == WatchResult.Status.UNAVAILABLE));
        state.accept(1, later.requestId(), manyNodes(NbtPage.PAGE_SIZE, 65));
        assertTrue(state.rows().subList(NbtPage.PAGE_SIZE, NbtPage.PAGE_SIZE * 2).stream()
            .allMatch(row -> row.kind() == ClientNbtState.Kind.PLACEHOLDER && row.status() == WatchResult.Status.UNAVAILABLE),
            "a reply arriving after timeout cannot replace the timed-out slots");

        state.refresh(a.uuid());
        ClientNbtState.Query refreshedRoot = onlyQuery(state);
        state.accept(1, refreshedRoot.requestId(), manyNodes(0, 65));
        state.requestVisible(a.uuid(), NbtPage.PAGE_SIZE, 1);
        ClientNbtState.Query retry = onlyQuery(state);
        assertEquals(NbtPage.PAGE_SIZE, retry.offset(), "refresh is the only path that permits a later-page retry");
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

    private static NbtPage manyNodes(String parentPath, int offset, int total) {
        List<NbtPage.Node> nodes = new ArrayList<>();
        for (int i = offset; i < Math.min(total, offset + NbtPage.PAGE_SIZE); i++) {
            nodes.add(new NbtPage.Node("node" + i, parentPath + ".\"node" + i + "\"", "value", false));
        }
        return new NbtPage(WatchResult.Status.VALUE, nodes, offset, total);
    }
}
