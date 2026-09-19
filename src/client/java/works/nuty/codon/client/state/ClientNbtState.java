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
        if (pauseId != id || !entitySources.equals(entities)) trees.values().forEach(Tree::clearPages);
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
        trees.values().forEach(Tree::clearPages);
    }

    /** Resume invalidates live pages but preserves UUID-keyed expansion preferences. */
    public void resumed() { stepping(); trimDormantStates(); }
    /** Disconnect drops per-entity UI state; enabled is an independent persisted preference. */
    public void reset() { stepping(); trees.clear(); sourceExpanded.clear(); }
    public void refresh() { if (executor() != null) refresh(executor().uuid()); }
    public void refresh(UUID executor) { Tree tree = tree(executor); if (tree != null) tree.clearPages(); }

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
        List<Row> rows = rows(executor);
        int end = (int) Math.min(rows.size(), (long) Math.max(0, first) + Math.max(0, count));
        for (int i = Math.max(0, first); i < end; i++) {
            Row row = rows.get(i);
            if (row.kind() == Kind.PLACEHOLDER) page(executor, row.path(), row.targetOffset());
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
        append(tree, "", 0, rows);
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
        for (Tree tree : trees.values()) for (Branch branch : tree.branches.values()) {
            for (PageRequest pending : branch.pages.values()) {
                if (pending.requestId != request || pending.page != null) continue;
                if (page.status() == WatchResult.Status.VALUE && page.offset() != pending.offset) return;
                pending.page = page;
                if (page.status() == WatchResult.Status.VALUE) branch.total = page.totalChildren();
                return;
            }
        }
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

    private void append(Tree tree, String path, int depth, VirtualRows rows) {
        if (depth > MAX_EXPANDED) {
            rows.add(new Row(Kind.STATUS, path, depth, null, WatchResult.Status.TOO_LARGE, 0, false));
            return;
        }
        tree.visibleBranches.add(path);
        Branch branch = tree.branch(path);
        if (branch.total < 0) {
            NbtPage initial = branch.pages.get(0).page;
            rows.add(new Row(Kind.STATUS, path, depth, null, initial == null ? null : initial.status(), 0, false));
            return;
        }
        if (branch.total == 0) {
            rows.add(new Row(Kind.EMPTY, path, depth, null, null, 0, false));
            return;
        }
        int cursor = 0;
        for (PageRequest pending : branch.pages.values()) {
            if (pending.offset >= branch.total) break;
            if (pending.offset > cursor) rows.gap(path, depth, cursor, pending.offset - cursor, null);
            int end = (int) Math.min(branch.total, (long) pending.offset + NbtPage.PAGE_SIZE);
            NbtPage page = pending.page;
            if (page == null || page.status() != WatchResult.Status.VALUE) {
                rows.gap(path, depth, pending.offset, end - pending.offset, page == null ? null : page.status());
            } else {
                int index = pending.offset;
                for (NbtPage.Node node : page.children()) {
                    if (index++ >= end) break;
                    boolean expanded = node.expandable() && tree.expanded.contains(node.path());
                    rows.add(new Row(Kind.NODE, node.path(), depth, node, null, 0, expanded));
                    if (expanded) append(tree, node.path(), depth + 1, rows);
                }
                if (index < end) rows.gap(path, depth, index, end - index, WatchResult.Status.UNAVAILABLE);
            }
            cursor = end;
        }
        if (cursor < branch.total) rows.gap(path, depth, cursor, branch.total - cursor, null);
    }

    /** Sparse row runs keep huge lists cheap even when the scrollbar jumps directly to their end. */
    private static final class VirtualRows extends java.util.AbstractList<Row> {
        private record Run(Row row, int firstChild) {}
        private final java.util.NavigableMap<Integer, Run> runs = new java.util.TreeMap<>();
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
            if (size == Integer.MAX_VALUE) return false;
            runs.put(size++, new Run(row, 0));
            return true;
        }
        void gap(String path, int depth, int first, int length, WatchResult.@Nullable Status status) {
            int bounded = Math.min(length, Integer.MAX_VALUE - size);
            if (bounded <= 0) return;
            var previous = runs.lastEntry();
            Row row = new Row(Kind.PLACEHOLDER, path, depth, null, status, 0, false);
            // Pending pages and unrequested slots form one visible +N marker.
            if (previous == null || !previous.getValue().row().equals(row)
                || (long) previous.getValue().firstChild() + size - previous.getKey() != first)
                runs.put(size, new Run(row, first));
            size += bounded;
        }
    }

    private static final class Tree {
        final Set<String> expanded = new LinkedHashSet<>();
        final Set<String> visibleBranches = new LinkedHashSet<>();
        final Map<String, Branch> branches = new LinkedHashMap<>();
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
