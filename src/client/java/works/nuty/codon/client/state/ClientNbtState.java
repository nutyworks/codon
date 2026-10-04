package works.nuty.codon.client.state;

import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.EntityRef;
import works.nuty.codon.core.model.NbtPage;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.WatchResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Lazy, bounded NBT pages for every current execution entity, isolated by UUID and pause ID. */
public final class ClientNbtState {
    public enum Kind { NODE, STATUS, EMPTY, PLACEHOLDER }
    public record Row(Kind kind, String path, int depth, NbtPage.@Nullable Node node,
                      WatchResult.@Nullable Status status, int targetOffset, boolean expanded) {}
    public record Query(long pauseId, long requestId, int sourceIndex, UUID executor, String path, int offset) {}
    public record EntitySource(int index, EntityRef executor) {}
    private static final int MAX_EXPANDED = 64;
    static final int MAX_MATERIALIZED_ROWS = 8192;
    static final int MAX_TRAVERSAL_STEPS = 16_384;
    private static final int MAX_DORMANT_UI_STATES = 256;
    private static final long TIMEOUT_NANOS = 5_000_000_000L;
    private final LongSupplier clock;
    private final Map<UUID, Tree> trees = new LinkedHashMap<>(16, .75f, true);
    /** UUID-keyed presentation state outlives source indices and pause lifetimes. */
    private final Map<UUID, Boolean> sourceExpanded = new LinkedHashMap<>(16, .75f, true);
    private List<PauseSource> sources = List.of();
    private List<EntitySource> entitySources = List.of();
    private long pauseId;
    private long nextRequest;
    private int sourceIndex = -1;
    private boolean enabled = true;
    private Consumer<Boolean> enabledListener = ignored -> { };

    public ClientNbtState(LongSupplier clock) { this.clock = java.util.Objects.requireNonNull(clock); }
    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        enabledListener.accept(enabled);
    }
    public void setEnabledListener(Consumer<Boolean> listener) {
        enabledListener = java.util.Objects.requireNonNull(listener);
    }

    public List<EntitySource> entitySources() { return pauseId > 0 ? entitySources : List.of(); }
    public boolean contains(UUID executor) {
        return pauseId > 0 && entitySources.stream().anyMatch(source -> source.executor().uuid().equals(executor));
    }
    public boolean sourceExpanded(UUID executor) { return sourceExpanded.getOrDefault(executor, true); }
    public boolean toggleSource(UUID executor) {
        if (!contains(executor)) return false;
        sourceExpanded.put(executor, !sourceExpanded(executor));
        return true;
    }

    public @Nullable EntityRef executor() {
        return pauseId > 0 && sourceIndex >= 0 && sourceIndex < sources.size() ? sources.get(sourceIndex).entity() : null;
    }

    public void paused(long id, List<PauseSource> sources, int selectedIndex) {
        List<EntitySource> entities = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).entity() != null) entities.add(new EntitySource(i, sources.get(i).entity()));
        }
        // A changed stop/source arrangement never reuses pages or in-flight request IDs, but retains UI state.
        if (pauseId != id || !entitySources.equals(entities)) trees.values().forEach(this::invalidatePages);
        this.pauseId = id;
        this.sources = List.copyOf(sources);
        entitySources = List.copyOf(entities);
        for (EntitySource source : entitySources) sourceExpanded.putIfAbsent(source.executor().uuid(), true);
        trimDormantStates();
        selectSource(selectedIndex);
    }

    public void selectSource(int index) { sourceIndex = index; }

    public void stepping() {
        pauseId = 0;
        sourceIndex = -1;
        sources = List.of();
        entitySources = List.of();
        trees.values().forEach(this::invalidatePages);
    }

    /** Resume invalidates live pages but preserves UUID-keyed expansion preferences. */
    public void resumed() {
        stepping();
        trees.values().forEach(tree -> tree.pendingPages.clear());
        trimDormantStates();
    }
    /** Disconnect drops per-entity UI state; enabled is an independent persisted preference. */
    public void reset() { stepping(); trees.clear(); sourceExpanded.clear(); }
    public void refresh() { if (executor() != null) refresh(executor().uuid()); }
    public void refresh(UUID executor) { Tree tree = tree(executor); if (tree != null) invalidatePages(tree); }

    /** Retained failure labels must not permit cancelling an already pending retry. */
    public boolean canRefresh(UUID executor, String path, int offset) {
        expire();
        Tree tree = tree(executor);
        Branch branch = tree == null ? null : tree.branches.get(path);
        PageRequest request = branch == null ? null : branch.pages.get(offset);
        return request != null && request.page != null && request.page.status() != WatchResult.Status.VALUE;
    }

    private void invalidatePages(Tree tree) {
        // Keep only loaded pages, never materialize sparse placeholder ranges or restart an unanswered hold.
        tree.pendingPages.values().forEach(pages -> pages.values().removeIf(display -> display.resolve(null) == null));
        tree.pendingPages.values().removeIf(Map::isEmpty);
        tree.branches.forEach((path, branch) -> branch.pages.forEach((offset, request) -> {
            if (request.page != null) tree.pendingPages.computeIfAbsent(path, ignored -> new java.util.TreeMap<>())
                .computeIfAbsent(offset, ignored -> new PendingDisplay<>(clock)).retain(request.page);
        }));
        tree.clearPages();
    }

    public boolean toggle(String path) { return executor() != null && toggle(executor().uuid(), path); }
    public boolean toggle(UUID executor, String path) {
        Tree tree = tree(executor);
        if (tree == null || path.isEmpty()) return false;
        if (tree.expanded.remove(path)) return true;
        List<Row> visible = rows(executor);
        boolean expandable = loadedIndices(visible).stream().map(visible::get).anyMatch(row -> row.kind() == Kind.NODE
            && row.path().equals(path) && row.node().expandable());
        if (!expandable || tree.expanded.size() >= MAX_EXPANDED) return false;
        tree.expanded.add(path);
        tree.branch(path);
        return true;
    }

    public boolean page(String path, int offset) { return executor() != null && page(executor().uuid(), path, offset); }
    public boolean page(UUID executor, String path, int offset) {
        Tree tree = tree(executor);
        if (tree == null || offset < 0) return false;
        Branch branch = tree.branches.get(path);
        if (branch == null || branch.total < 0 || offset >= branch.total) return false;
        int aligned = offset / NbtPage.PAGE_SIZE * NbtPage.PAGE_SIZE;
        if (branch.pages.containsKey(aligned)) return false;
        branch.pages.put(aligned, new PageRequest(aligned));
        return true;
    }

    /** Queue only pages intersecting the viewport; unloaded slots still contribute to scroll extent. */
    public void requestVisible(UUID executor, int first, int count) {
        rows(executor); // Discover only branches justified by current server data.
        Tree tree = tree(executor);
        if (tree == null) return;
        List<Row> rows = displayedRows(executor);
        if (!(rows instanceof VirtualRows virtual)) return;
        int end = (int) Math.min(rows.size(), (long) Math.max(0, first) + Math.max(0, count));
        for (int i = Math.max(0, first); i < end; i++) {
            Row row = rows.get(i);
            VirtualRows.Run run = virtual.runs.floorEntry(i).getValue();
            String path = run.pagePath();
            int offset = row.kind() == Kind.PLACEHOLDER ? row.targetOffset() : run.pageOffset();
            if (tree.visibleBranches.contains(path)) page(executor, path, offset);
        }
    }

    public List<Row> rows() { return executor() == null ? List.of() : rows(executor().uuid()); }
    public List<Row> rows(UUID executor) {
        expire();
        // The inspector always exposes the current source. Retain enabled/sourceExpanded for older
        // persisted settings and callers, but never let them hide current NBT or suppress its data.
        Tree tree = tree(executor);
        if (tree == null) return List.of();
        tree.visibleBranches.clear();
        VirtualRows rows = new VirtualRows();
        append(tree, "", 0, rows, false);
        return rows;
    }

    /** Retained pages are display-only and remain isolated by executor, branch path, and page offset. */
    public List<Row> displayedRows(UUID executor) {
        expire();
        Tree tree = tree(executor);
        if (tree == null) return List.of();
        VirtualRows rows = new VirtualRows();
        append(tree, "", 0, rows, true);
        return rows;
    }

    /** Materialized rows only, for navigation and anchors without walking potentially huge gaps. */
    public static List<Integer> loadedIndices(List<Row> rows) {
        if (rows instanceof VirtualRows virtual) return virtual.runs.entrySet().stream()
            .filter(entry -> entry.getValue().row().kind() != Kind.PLACEHOLDER).map(Map.Entry::getKey).toList();
        return java.util.stream.IntStream.range(0, rows.size()).boxed().toList();
    }

    /** Number of consecutive unloaded slots from this row, without enumerating a large gap. */
    public static int hiddenCount(List<Row> rows, int index) {
        if (!(rows instanceof VirtualRows virtual) || rows.get(index).kind() != Kind.PLACEHOLDER) return 0;
        Integer end = virtual.runs.higherKey(index);
        return (end == null ? virtual.size() : end) - index;
    }

    public List<Query> drainQueries() {
        if (pauseId <= 0) return List.of();
        List<Query> queries = new ArrayList<>();
        for (EntitySource source : entitySources) {
            UUID uuid = source.executor().uuid();
            Tree tree = tree(uuid);
            rows(uuid); // Discover expanded branches from loaded nodes, never enumerate unloaded slots.
            for (var entry : tree.branches.entrySet()) {
                if (!tree.visibleBranches.contains(entry.getKey())) continue;
                for (PageRequest pending : entry.getValue().pages.values()) {
                    if (pending.requestId != 0 || pending.page != null) continue;
                    pending.requestId = ++nextRequest;
                    pending.requestedAt = clock.getAsLong();
                    queries.add(new Query(pauseId, pending.requestId, source.index(), uuid, entry.getKey(), pending.offset));
                    if (queries.size() == 4) return List.copyOf(queries);
                }
            }
        }
        return List.copyOf(queries);
    }

    public void accept(long pause, long request, NbtPage page) {
        if (pauseId <= 0 || pause != pauseId || request <= 0) return;
        for (Tree tree : trees.values()) for (var entry : tree.branches.entrySet()) {
            Branch branch = entry.getValue();
            for (PageRequest pending : branch.pages.values()) {
                if (pending.requestId != request || pending.page != null) continue;
                if (page.status() == WatchResult.Status.VALUE && page.offset() != pending.offset) return;
                if (page.status() == WatchResult.Status.VALUE && !validChildren(entry.getKey(), branch, page)) {
                    pending.page = NbtPage.absent(WatchResult.Status.INVALID_PATH);
                    return;
                }
                pending.page = page;
                if (page.status() == WatchResult.Status.VALUE) branch.total = page.totalChildren();
                return;
            }
        }
    }

    private static boolean validChildren(String path, Branch branch, NbtPage page) {
        if (branch.total >= 0 && branch.total != page.totalChildren()) return false;
        Set<String> identities = new java.util.HashSet<>();
        for (PageRequest request : branch.pages.values()) {
            if (request.page == null || request.page.status() != WatchResult.Status.VALUE) continue;
            for (NbtPage.Node node : request.page.children()) {
                if (!node.path().isEmpty()) identities.add(NbtPage.childIdentity(path, node.path()));
            }
        }
        for (NbtPage.Node node : page.children()) {
            // Multiple inaccessible values are legitimate leaves, never navigation targets.
            if (node.path().isEmpty() && !node.expandable()) continue;
            String identity = NbtPage.childIdentity(path, node.path());
            if (identity == null || !identities.add(identity)) return false;
        }
        return true;
    }

    private void expire() {
        long now = clock.getAsLong();
        for (Tree tree : trees.values()) for (Branch branch : tree.branches.values()) {
            for (PageRequest pending : branch.pages.values()) {
                if (pending.requestId != 0 && pending.page == null && now - pending.requestedAt >= TIMEOUT_NANOS)
                    pending.page = NbtPage.absent(WatchResult.Status.UNAVAILABLE);
            }
        }
    }

    private @Nullable Tree tree(UUID executor) {
        if (!contains(executor)) return null;
        return trees.computeIfAbsent(executor, ignored -> new Tree());
    }

    /** Current sources are never capped; only absent UUID preferences use the dormant LRU budget. */
    private void trimDormantStates() {
        Set<UUID> active = entitySources.stream().map(source -> source.executor().uuid()).collect(java.util.stream.Collectors.toSet());
        int dormant = (int) sourceExpanded.keySet().stream().filter(uuid -> !active.contains(uuid)).count();
        var iterator = sourceExpanded.keySet().iterator();
        while (dormant > MAX_DORMANT_UI_STATES && iterator.hasNext()) {
            if (!active.contains(iterator.next())) {
                iterator.remove();
                dormant--;
            }
        }
        // Trees retain path expansion too, so evict their matching dormant history at the same boundary.
        int dormantTrees = (int) trees.keySet().stream().filter(uuid -> !active.contains(uuid)).count();
        var treeIterator = trees.keySet().iterator();
        while (dormantTrees > MAX_DORMANT_UI_STATES && treeIterator.hasNext()) {
            if (!active.contains(treeIterator.next())) {
                treeIterator.remove();
                dormantTrees--;
            }
        }
    }

    private void append(Tree tree, String path, int depth, VirtualRows rows, boolean display) {
        if (!rows.step(path, depth)) return;
        if (!rows.visited.add(path)) {
            rows.add(new Row(Kind.STATUS, path, depth, null, WatchResult.Status.INVALID_PATH, 0, false));
            return;
        }
        if (depth > MAX_EXPANDED) {
            rows.add(new Row(Kind.STATUS, path, depth, null, WatchResult.Status.TOO_LARGE, 0, false));
            return;
        }
        if (!display) tree.visibleBranches.add(path);
        Branch branch = display ? tree.branches.get(path) : tree.branch(path);
        NbtPage initial = page(tree, branch, path, 0, display);
        int total = branch == null ? -1 : branch.total;
        if (display && total < 0 && initial != null && initial.status() == WatchResult.Status.VALUE) total = initial.totalChildren();
        if (total < 0) {
            rows.add(new Row(Kind.STATUS, path, depth, null, initial == null ? null : initial.status(), 0, false));
            return;
        }
        if (total == 0) {
            rows.add(new Row(Kind.EMPTY, path, depth, null, null, 0, false));
            return;
        }
        int cursor = 0;
        Set<Integer> offsets = new java.util.TreeSet<>();
        if (branch != null) for (int offset : branch.pages.keySet()) {
            if (!rows.step(path, depth)) return;
            offsets.add(offset);
        }
        if (display && tree.pendingPages.containsKey(path)) for (int offset : tree.pendingPages.get(path).keySet()) {
            if (!rows.step(path, depth)) return;
            offsets.add(offset);
        }
        for (int offset : offsets) {
            if (!rows.step(path, depth)) return;
            if (offset >= total) break;
            if (offset > cursor) rows.gap(path, depth, cursor, offset - cursor, null);
            int end = (int) Math.min(total, (long) offset + NbtPage.PAGE_SIZE);
            NbtPage page = page(tree, branch, path, offset, display);
            if (page == null || page.status() != WatchResult.Status.VALUE) {
                rows.gap(path, depth, offset, end - offset, page == null ? null : page.status());
            } else {
                int index = offset;
                for (NbtPage.Node node : page.children()) {
                    if (!rows.step(path, depth)) return;
                    if (index++ >= end) break;
                    boolean expanded = node.expandable() && tree.expanded.contains(node.path());
                    rows.add(new Row(Kind.NODE, node.path(), depth, node, null, 0, expanded), path, offset);
                    if (expanded) append(tree, node.path(), depth + 1, rows, display);
                }
                if (index < end) rows.gap(path, depth, index, end - index, WatchResult.Status.UNAVAILABLE);
            }
            cursor = end;
        }
        if (cursor < total) rows.gap(path, depth, cursor, total - cursor, null);
    }

    private @Nullable NbtPage page(Tree tree, @Nullable Branch branch, String path, int offset, boolean display) {
        PageRequest request = branch == null ? null : branch.pages.get(offset);
        NbtPage current = request == null ? null : request.page;
        var pending = tree.pendingPages.get(path);
        if (!display || pending == null || !pending.containsKey(offset)) return current;
        NbtPage result = pending.get(offset).resolve(current);
        if (current != null || result == null) pending.remove(offset);
        return result;
    }

    /** Sparse row runs keep huge lists cheap even when the scrollbar jumps directly to their end. */
    private static final class VirtualRows extends java.util.AbstractList<Row> {
        private record Run(Row row, int firstChild, String pagePath, int pageOffset) {}
        private final java.util.NavigableMap<Integer, Run> runs = new java.util.TreeMap<>();
        private final Set<String> visited = new java.util.HashSet<>();
        private boolean exhausted;
        private int steps;
        private int size;
        @Override public int size() { return size; }
        @Override public Row get(int index) {
            java.util.Objects.checkIndex(index, size);
            var entry = runs.floorEntry(index);
            Run run = entry.getValue();
            Row row = run.row();
            if (row.kind() != Kind.PLACEHOLDER) return row;
            int child = run.firstChild() + index - entry.getKey();
            return new Row(Kind.PLACEHOLDER, row.path(), row.depth(), null, row.status(),
                child / NbtPage.PAGE_SIZE * NbtPage.PAGE_SIZE, false);
        }
        @Override public boolean add(Row row) {
            return add(row, row.path(), row.targetOffset());
        }
        boolean add(Row row, String pagePath, int pageOffset) {
            if (!reserve(row.path(), row.depth())) return false;
            runs.put(size++, new Run(row, 0, pagePath, pageOffset));
            return true;
        }
        private boolean reserve(String path, int depth) {
            if (exhausted) return false;
            if (runs.size() >= MAX_MATERIALIZED_ROWS || size == Integer.MAX_VALUE) {
                failBudget(path, depth);
                return false;
            }
            return true;
        }
        private boolean step(String path, int depth) {
            if (exhausted) return false;
            if (steps++ >= MAX_TRAVERSAL_STEPS) {
                failBudget(path, depth);
                return false;
            }
            return true;
        }
        private void failBudget(String path, int depth) {
            exhausted = true;
            // Keep a visible terminal failure even when the logical sparse extent is full.
            int at = size == Integer.MAX_VALUE ? size - 1 : size++;
            runs.put(at, new Run(new Row(Kind.STATUS, path, depth, null,
                WatchResult.Status.TOO_LARGE, 0, false), 0, path, 0));
        }
        void gap(String path, int depth, int first, int length, WatchResult.@Nullable Status status) {
            if (!reserve(path, depth)) return;
            int bounded = Math.min(length, Integer.MAX_VALUE - size);
            if (bounded <= 0) return;
            var previous = runs.lastEntry();
            Row row = new Row(Kind.PLACEHOLDER, path, depth, null, status, 0, false);
            // Pending pages and unrequested slots form one visible +N marker.
            if (previous == null || !previous.getValue().row().equals(row)
                || (long) previous.getValue().firstChild() + size - previous.getKey() != first)
                runs.put(size, new Run(row, first, path, 0));
            size += bounded;
        }
    }

    private static final class Tree {
        final Set<String> expanded = new LinkedHashSet<>();
        final Set<String> visibleBranches = new LinkedHashSet<>();
        final Map<String, Branch> branches = new LinkedHashMap<>();
        final Map<String, java.util.NavigableMap<Integer, PendingDisplay<NbtPage>>> pendingPages = new LinkedHashMap<>();
        Tree() { clearPages(); }
        void clearPages() { branches.clear(); branches.put("", new Branch()); }
        Branch branch(String path) {
            Branch branch = branches.get(path);
            if (branch != null) return branch;
            if (branches.size() >= MAX_EXPANDED + 1) {
                String discard = branches.keySet().stream().filter(key -> !key.isEmpty() && !expanded.contains(key)).findFirst().orElse(null);
                if (discard != null) branches.remove(discard);
            }
            branch = new Branch();
            branches.put(path, branch);
            return branch;
        }
    }

    private static final class Branch {
        int total = -1;
        final java.util.NavigableMap<Integer, PageRequest> pages = new java.util.TreeMap<>();
        Branch() { pages.put(0, new PageRequest(0)); }
    }

    private static final class PageRequest {
        final int offset;
        long requestId;
        long requestedAt;
        @Nullable NbtPage page;
        PageRequest(int offset) { this.offset = offset; }
    }
}
