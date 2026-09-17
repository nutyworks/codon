package works.nuty.bastion.client.state;

import org.jspecify.annotations.Nullable;
import works.nuty.bastion.core.model.EntityRef;
import works.nuty.bastion.core.model.NbtPage;
import works.nuty.bastion.core.model.PauseSource;
import works.nuty.bastion.core.model.WatchResult;

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
    public enum Kind { NODE, STATUS, EMPTY, PREVIOUS, NEXT }
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
        boolean expandable = rows(executor).stream().anyMatch(row -> row.kind() == Kind.NODE
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
        if (branch == null || branch.page == null || branch.page.status() != WatchResult.Status.VALUE
            || offset >= branch.page.totalChildren() || offset == branch.offset) return false;
        tree.branches.put(path, new Branch(offset));
        return true;
    }

    public List<Row> rows() { return executor() == null ? List.of() : rows(executor().uuid()); }
    public List<Row> rows(UUID executor) {
        expire();
        Tree tree = enabled ? tree(executor) : null;
        if (tree == null) return List.of();
        List<Row> rows = new ArrayList<>();
        append(tree, "", 0, rows);
        return List.copyOf(rows);
    }

    public List<Query> drainQueries() {
        if (!enabled || pauseId <= 0) return List.of();
        List<Query> queries = new ArrayList<>();
        for (EntitySource source : entitySources) {
            UUID uuid = source.executor().uuid();
            if (!sourceExpanded(uuid)) continue;
            Tree tree = tree(uuid);
            for (Row row : rows(uuid)) {
                if (row.kind() != Kind.STATUS || row.status() != null) continue;
                Branch branch = tree.branches.get(row.path());
                if (branch.requestId != 0) continue;
                branch.requestId = ++nextRequest;
                branch.requestedAt = clock.getAsLong();
                queries.add(new Query(pauseId, branch.requestId, source.index(), uuid, row.path(), branch.offset));
                if (queries.size() == 4) return List.copyOf(queries);
            }
        }
        return List.copyOf(queries);
    }

    public void accept(long pause, long request, NbtPage page) {
        if (pauseId <= 0 || pause != pauseId || request <= 0) return;
        for (Tree tree : trees.values()) for (Branch branch : tree.branches.values()) {
            if (branch.requestId != request || branch.page != null) continue;
            if (page.status() == WatchResult.Status.VALUE && page.offset() != branch.offset) return;
            branch.page = page;
            return;
        }
    }

    private void expire() {
        long now = clock.getAsLong();
        for (Tree tree : trees.values()) for (Branch branch : tree.branches.values()) {
            if (branch.requestId != 0 && branch.page == null && now - branch.requestedAt >= TIMEOUT_NANOS)
                branch.page = NbtPage.absent(WatchResult.Status.UNAVAILABLE);
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

    private void append(Tree tree, String path, int depth, List<Row> rows) {
        if (depth > MAX_EXPANDED) {
            rows.add(new Row(Kind.STATUS, path, depth, null, WatchResult.Status.TOO_LARGE, 0, false));
            return;
        }
        Branch branch = tree.branch(path);
        NbtPage page = branch.page;
        if (page == null || page.status() != WatchResult.Status.VALUE) {
            rows.add(new Row(Kind.STATUS, path, depth, null, page == null ? null : page.status(), 0, false));
            return;
        }
        if (page.children().isEmpty()) {
            rows.add(new Row(Kind.EMPTY, path, depth, null, null, 0, false));
            return;
        }
        if (page.offset() > 0) rows.add(new Row(Kind.PREVIOUS, path, depth, null, null,
            Math.max(0, page.offset() - NbtPage.PAGE_SIZE), false));
        for (NbtPage.Node node : page.children()) {
            boolean expanded = node.expandable() && tree.expanded.contains(node.path());
            rows.add(new Row(Kind.NODE, node.path(), depth, node, null, 0, expanded));
            if (expanded) append(tree, node.path(), depth + 1, rows);
        }
        if (page.hasMore()) rows.add(new Row(Kind.NEXT, path, depth, null, null,
            page.offset() + page.children().size(), false));
    }

    private static final class Tree {
        final Set<String> expanded = new LinkedHashSet<>();
        final Map<String, Branch> branches = new LinkedHashMap<>();
        Tree() { clearPages(); }
        void clearPages() { branches.clear(); branches.put("", new Branch(0)); }
        Branch branch(String path) {
            Branch branch = branches.get(path);
            if (branch != null) return branch;
            if (branches.size() >= MAX_EXPANDED + 1) {
                String discard = branches.keySet().stream().filter(key -> !key.isEmpty() && !expanded.contains(key)).findFirst().orElse(null);
                if (discard != null) branches.remove(discard);
            }
            branch = new Branch(0);
            branches.put(path, branch);
            return branch;
        }
    }

    private static final class Branch {
        final int offset;
        long requestId;
        long requestedAt;
        @Nullable NbtPage page;
        Branch(int offset) { this.offset = offset; }
    }
}
