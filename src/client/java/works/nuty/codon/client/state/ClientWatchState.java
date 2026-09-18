package works.nuty.codon.client.state;

import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.core.model.EntityRef;
import works.nuty.codon.core.model.PauseSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Consumer;

/** Client-thread watch definitions and correlated replies for the current continuous stepping session. */
public final class ClientWatchState {
    public enum Change {
        INITIAL, UNCHANGED, VALUE_CHANGED, VALUE_APPEARED, VALUE_DISAPPEARED, TARGET_CHANGED, AVAILABILITY_CHANGED;

        public boolean isValueChange() {
            return this == VALUE_CHANGED || this == VALUE_APPEARED || this == VALUE_DISAPPEARED;
        }
    }
    public record Observation(WatchResult result, Change change, String previousValue) {}
    public record Entry(long id, WatchSpec spec, @Nullable WatchResult result, Change change, String previousValue,
                        @Nullable Observation completedStep, @Nullable UUID displayedExecutor, String executorName) {
        public @Nullable WatchResult displayedResult() { return completedStep == null ? result : completedStep.result(); }
        public Change displayedChange() { return completedStep == null ? change : completedStep.change(); }
        public String displayedPreviousValue() { return completedStep == null ? previousValue : completedStep.previousValue(); }
    }
    public record Query(long pauseId, long requestId, int sourceIndex, WatchSpec spec, @Nullable UUID capturedEntity) {}

    private static final long TIMEOUT_NANOS = 5_000_000_000L;
    private static final int MAX_CAPTURES_PER_WATCH = 256;
    private final LongSupplier clock;
    private final Map<Long, Slot> slots = new LinkedHashMap<>();
    /** Last selected result, retained to distinguish a transient availability failure from a later recovery. */
    private final Map<Long, WatchResult> previous = new LinkedHashMap<>();
    /** Last completed-step result for each known target, bounded independently for every watch. */
    private final Map<Long, LinkedHashMap<String, WatchResult>> targetHistory = new LinkedHashMap<>();
    /** Re-read the executor we just stepped, even if the next command has a different/no executor. */
    private final Map<Long, WatchResult> stepTargets = new LinkedHashMap<>();
    private final Map<UUID, String> executorNames = new LinkedHashMap<>();
    private List<PauseSource> currentSources = List.of();
    private long nextEntryId;
    private long nextRequestId;
    private long pauseId;
    private int sourceIndex = -1;
    private boolean continuingStep;
    private Consumer<List<WatchSpec>> changeListener = ignored -> {};

    public ClientWatchState(LongSupplier clock) { this.clock = clock; }

    public List<WatchSpec> definitions() {
        return slots.values().stream().map(slot -> slot.spec).toList();
    }

    public void setChangeListener(Consumer<List<WatchSpec>> listener) {
        changeListener = java.util.Objects.requireNonNull(listener);
    }

    /** A world restore never writes back or restores stale observations from a prior connection. */
    public void restoreDefinitions(List<WatchSpec> definitions) {
        List<WatchSpec> checked = List.copyOf(definitions);
        if (checked.stream().distinct().count() != checked.size()) {
            throw new IllegalArgumentException("Invalid watch definitions");
        }
        reset();
        checked.forEach(spec -> slots.put(++nextEntryId, new Slot(spec)));
    }

    public List<Entry> entries() {
        expire();
        return slots.entrySet().stream().map(e -> {
            Slot slot = e.getValue();
            WatchResult before = comparisonBefore(e.getKey(), slot.result);
            Change change = compare(before, slot.result);
            WatchResult steppedFrom = stepTargets.get(e.getKey());
            Observation completed = null;
            if (steppedFrom != null && slot.completionResult != null
                && (slot.result == null || !steppedFrom.targetKey().equals(slot.result.targetKey()))) {
                Change completionChange = compare(steppedFrom, slot.completionResult);
                if (completionChange.isValueChange() || completionChange == Change.AVAILABILITY_CHANGED) {
                    completed = new Observation(slot.completionResult, completionChange,
                        previousValue(steppedFrom, completionChange));
                }
            }
            WatchResult displayed = completed == null ? slot.result : completed.result();
            UUID executor = slot.spec.kind() == WatchSpec.Kind.STORAGE_NBT ? null : entityId(displayed);
            if (executor == null && completed != null) executor = entityId(steppedFrom);
            if (executor == null && completed == null && slot.spec.kind() != WatchSpec.Kind.STORAGE_NBT) {
                executor = slot.spec.executor() != null ? slot.spec.executor() : selectedExecutor();
            }
            String name = executor == null ? "" : displayed != null && !displayed.targetName().isBlank()
                ? displayed.targetName() : executorNames.getOrDefault(executor, "");
            return new Entry(e.getKey(), slot.spec, slot.result, change,
                previousValue(before, change), completed, executor, name);
        }).toList();
    }

    public boolean add(WatchSpec spec) {
        if (slots.values().stream().anyMatch(s -> s.spec.equals(spec))) return false;
        slots.put(++nextEntryId, new Slot(spec));
        changeListener.accept(definitions());
        return true;
    }

    /** Adds a deduplicated group atomically; callers can treat an already-present group as successful. */
    public boolean addAll(List<WatchSpec> specs) {
        List<WatchSpec> requested = List.copyOf(specs);
        Set<WatchSpec> known = slots.values().stream().map(slot -> slot.spec)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<WatchSpec> missing = new ArrayList<>();
        for (WatchSpec spec : requested) {
            if (known.add(spec)) missing.add(spec);
        }
        if (missing.isEmpty()) return true;
        missing.forEach(spec -> slots.put(++nextEntryId, new Slot(spec)));
        changeListener.accept(definitions());
        return true;
    }

    /** Fills a partially pinned group, or removes the complete group in one persisted edit. */
    public void toggleAll(List<WatchSpec> specs) {
        Set<WatchSpec> requested = new LinkedHashSet<>(List.copyOf(specs));
        if (requested.isEmpty()) return;
        Set<WatchSpec> known = slots.values().stream().map(slot -> slot.spec)
            .collect(java.util.stream.Collectors.toSet());
        if (!known.containsAll(requested)) {
            addAll(List.copyOf(requested));
            return;
        }
        List<Long> removed = slots.entrySet().stream().filter(entry -> requested.contains(entry.getValue().spec))
            .map(Map.Entry::getKey).toList();
        for (long id : removed) {
            slots.remove(id);
            previous.remove(id);
            targetHistory.remove(id);
            stepTargets.remove(id);
        }
        pruneExecutorNames();
        changeListener.accept(definitions());
    }

    /** Deletes all definitions without ending the current pause or replacing the persistence listener. */
    public void clearDefinitions() {
        if (slots.isEmpty()) return;
        slots.clear();
        previous.clear();
        targetHistory.clear();
        stepTargets.clear();
        pruneExecutorNames();
        changeListener.accept(List.of());
    }

    public void remove(long id) {
        if (slots.remove(id) == null) return;
        previous.remove(id);
        targetHistory.remove(id);
        stepTargets.remove(id);
        pruneExecutorNames();
        changeListener.accept(definitions());
    }

    public boolean pin(long id, EntityRef executor) {
        Slot slot = slots.get(id);
        if (pauseId <= 0 || executor == null || slot == null || slot.spec.kind() == WatchSpec.Kind.STORAGE_NBT) return false;
        if (!rebind(id, slot.spec.withExecutor(executor.uuid()))) return false;
        executorNames.put(executor.uuid(), executor.name());
        return true;
    }

    public boolean unpin(long id) {
        Slot slot = slots.get(id);
        return slot != null && slot.spec.isPinned() && rebind(id, slot.spec.withExecutor(null));
    }

    private boolean rebind(long id, WatchSpec spec) {
        if (slots.values().stream().anyMatch(slot -> slot.spec.equals(spec))) return false;
        // A new binding is an initial observation; cancel in-flight replies for the old target.
        slots.put(id, new Slot(spec));
        previous.remove(id);
        targetHistory.remove(id);
        stepTargets.remove(id);
        pruneExecutorNames();
        changeListener.accept(definitions());
        return true;
    }

    public void rememberExecutors(List<PauseSource> sources) {
        currentSources = List.copyOf(sources);
        for (PauseSource source : sources) {
            if (source.entity() != null) executorNames.put(source.entity().uuid(), source.entity().name());
        }
        pruneExecutorNames();
    }

    private @Nullable UUID selectedExecutor() {
        if (pauseId <= 0 || sourceIndex < 0 || sourceIndex >= currentSources.size()) return null;
        EntityRef selected = currentSources.get(sourceIndex).entity();
        return selected == null ? null : selected.uuid();
    }

    private void rememberExecutor(WatchResult result) {
        UUID uuid = entityId(result);
        if (uuid != null && !result.targetName().isBlank()) executorNames.put(uuid, result.targetName());
    }

    /** Names follow the same active/captured targets as the values, including the outgoing executor. */
    private void pruneExecutorNames() {
        Set<UUID> retained = new LinkedHashSet<>();
        for (PauseSource source : currentSources) if (source.entity() != null) retained.add(source.entity().uuid());
        slots.values().forEach(slot -> {
            if (slot.spec.executor() != null) retained.add(slot.spec.executor());
            retained.add(entityId(slot.result));
            retained.add(entityId(slot.completionResult));
            slot.captures.values().forEach(result -> retained.add(entityId(result)));
        });
        previous.values().forEach(result -> retained.add(entityId(result)));
        stepTargets.values().forEach(result -> retained.add(entityId(result)));
        targetHistory.values().forEach(history -> history.values().forEach(result -> retained.add(entityId(result))));
        executorNames.keySet().retainAll(retained);
    }

    public void paused(long id, int index) {
        if (pauseId == id && id > 0) { selectSource(index); return; }
        if (!continuingStep) clearHistory();
        continuingStep = false;
        pauseId = id;
        sourceIndex = index;
        currentSources = List.of();
        slots.values().forEach(Slot::clear);
    }

    public void selectSource(int index) {
        if (sourceIndex == index) return;
        sourceIndex = index;
        // Storage is independent of the selected executor; executor captures remain available on a revisit.
        slots.values().stream().filter(s -> s.spec.kind() != WatchSpec.Kind.STORAGE_NBT && !s.spec.isPinned())
            .forEach(s -> s.selectSource(index));
    }

    public void stepping() {
        stepTargets.clear();
        slots.forEach((watchId, slot) -> {
            if (slot.result != null) previous.put(watchId, slot.result);
            slot.captures.values().forEach(result -> rememberTarget(watchId, result));
            if (slot.completionResult != null) rememberTarget(watchId, slot.completionResult);
            if (slot.spec.kind() != WatchSpec.Kind.STORAGE_NBT && !slot.spec.isPinned() && entityId(slot.result) != null) {
                stepTargets.put(watchId, slot.result);
            }
            slot.clear();
        });
        pauseId = 0;
        currentSources = List.of();
        continuingStep = true;
    }

    public void resumed() {
        pauseId = 0;
        continuingStep = false;
        currentSources = List.of();
        clearHistory();
        slots.values().forEach(Slot::clear);
        pruneExecutorNames();
    }

    public void reset() {
        resumed();
        slots.clear();
        executorNames.clear();
        // Keep counters monotonic so responses from a disconnected session cannot match.
    }

    /** One read per selection plus one previous-executor read per watch/stop; no automatic retries. */
    public List<Query> drainQueries() {
        expire();
        if (pauseId <= 0) return List.of();
        List<Query> queries = new ArrayList<>();
        slots.forEach((watchId, slot) -> {
            if (slot.requestId == 0 && slot.result == null) {
                slot.requestId = ++nextRequestId;
                slot.requestedAt = clock.getAsLong();
                slot.requestSourceIndex = slot.spec.isPinned() ? -1 : sourceIndex;
                queries.add(new Query(pauseId, slot.requestId, slot.requestSourceIndex, slot.spec, slot.spec.executor()));
            }
            UUID capturedEntity = entityId(stepTargets.get(watchId));
            if (capturedEntity != null && slot.completionRequestId == 0 && slot.completionResult == null) {
                slot.completionRequestId = ++nextRequestId;
                slot.completionRequestedAt = clock.getAsLong();
                queries.add(new Query(pauseId, slot.completionRequestId, -1, slot.spec, capturedEntity));
            }
        });
        return List.copyOf(queries);
    }

    public void accept(long id, long requestId, WatchResult result) {
        if (pauseId <= 0 || id != pauseId) return;
        for (Slot slot : slots.values()) {
            if (slot.requestId == requestId && slot.result == null) {
                slot.result = result;
                rememberExecutor(result);
                slot.capture(slot.requestSourceIndex, result);
                return;
            }
            if (slot.completionRequestId == requestId && slot.completionResult == null) {
                slot.completionResult = result;
                rememberExecutor(result);
                return;
            }
        }
    }

    private void expire() {
        long now = clock.getAsLong();
        slots.values().forEach(slot -> {
            if (slot.requestId != 0 && slot.result == null && now - slot.requestedAt >= TIMEOUT_NANOS) {
                slot.result = WatchResult.absent(WatchResult.Status.UNAVAILABLE, "");
                slot.capture(slot.requestSourceIndex, slot.result);
            }
            if (slot.completionRequestId != 0 && slot.completionResult == null
                && now - slot.completionRequestedAt >= TIMEOUT_NANOS) {
                slot.completionResult = WatchResult.absent(WatchResult.Status.UNAVAILABLE, "");
            }
        });
    }

    private void clearHistory() {
        previous.clear();
        targetHistory.clear();
        stepTargets.clear();
    }

    private static @Nullable UUID entityId(@Nullable WatchResult result) {
        if (result == null || !result.targetKey().startsWith("entity:")) return null;
        try { return UUID.fromString(result.targetKey().substring("entity:".length())); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private void rememberTarget(long watchId, WatchResult result) {
        if (result.targetKey().isEmpty()) return;
        LinkedHashMap<String, WatchResult> captures = targetHistory.computeIfAbsent(watchId, ignored -> new LinkedHashMap<>());
        // Reinsert to keep frequently sampled targets from being evicted solely by their first capture.
        captures.remove(result.targetKey());
        captures.put(result.targetKey(), result);
        while (captures.size() > MAX_CAPTURES_PER_WATCH) {
            captures.remove(captures.entrySet().iterator().next().getKey());
        }
    }

    private @Nullable WatchResult comparisonBefore(long watchId, @Nullable WatchResult after) {
        WatchResult immediate = previous.get(watchId);
        if (after == null) return immediate;
        if (after.targetKey().isEmpty()) return immediate;
        // An anonymous error or timeout is an availability transition, never a target transition or capture.
        if (immediate != null && immediate.targetKey().isEmpty() && immediate.status() != after.status()) return immediate;
        LinkedHashMap<String, WatchResult> captures = targetHistory.get(watchId);
        if (captures == null) return immediate;
        WatchResult matched = captures.get(after.targetKey());
        if (matched != null) return matched;
        // Let compare report TARGET_CHANGED for a newly encountered target in an established watch session.
        return captures.isEmpty() ? immediate : captures.values().iterator().next();
    }

    private static Change compare(@Nullable WatchResult before, @Nullable WatchResult after) {
        if (before == null || after == null) return Change.INITIAL;
        if (!before.targetKey().isEmpty() && !after.targetKey().isEmpty()
            && !before.targetKey().equals(after.targetKey())) return Change.TARGET_CHANGED;
        if (!before.targetKey().isEmpty() && before.targetKey().equals(after.targetKey())) {
            if (before.status() == WatchResult.Status.VALUE_MISSING && after.status() == WatchResult.Status.VALUE) {
                return Change.VALUE_APPEARED;
            }
            if (before.status() == WatchResult.Status.VALUE && after.status() == WatchResult.Status.VALUE_MISSING) {
                return Change.VALUE_DISAPPEARED;
            }
        }
        if (before.status() != after.status()) return Change.AVAILABILITY_CHANGED;
        if (after.status() != WatchResult.Status.VALUE) return Change.UNCHANGED;
        return before.value().equals(after.value()) ? Change.UNCHANGED : Change.VALUE_CHANGED;
    }

    private static String previousValue(@Nullable WatchResult before, Change change) {
        return before != null && before.status() == WatchResult.Status.VALUE && change.isValueChange() ? before.value() : "";
    }

    private static final class Slot {
        final WatchSpec spec;
        final Map<Integer, WatchResult> captures = new LinkedHashMap<>();
        long requestId;
        long requestedAt;
        int requestSourceIndex;
        @Nullable WatchResult result;
        long completionRequestId;
        long completionRequestedAt;
        @Nullable WatchResult completionResult;
        Slot(WatchSpec spec) { this.spec = spec; }
        void clear() {
            requestId = 0;
            result = null;
            captures.clear();
            completionRequestId = 0;
            completionResult = null;
        }
        void capture(int sourceIndex, WatchResult captured) {
            captures.remove(sourceIndex);
            captures.put(sourceIndex, captured);
            while (captures.size() > MAX_CAPTURES_PER_WATCH) {
                captures.remove(captures.entrySet().iterator().next().getKey());
            }
        }
        void selectSource(int sourceIndex) {
            requestId = 0;
            result = captures.get(sourceIndex);
        }
    }
}
