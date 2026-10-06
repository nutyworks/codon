package works.nuty.codon.client.state;

import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.core.model.WatchChange;
import works.nuty.codon.core.model.EntityRef;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.WatchIdentity;
import works.nuty.codon.core.model.TransferBudget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
        public boolean automatic() { return id < 0; }
    }
    public record Query(long pauseId, long requestId, int sourceIndex, WatchSpec spec, @Nullable UUID capturedEntity) {}
    /** Short-lived undo token; a world reset invalidates it. */
    public record Removed(long id, WatchSpec spec, int index, long generation) { }
    private WatchGrouping.Mode grouping = WatchGrouping.Mode.CONTEXT;
    public WatchGrouping.Mode grouping() { return grouping; }
    public void grouping(WatchGrouping.Mode mode) { grouping = java.util.Objects.requireNonNull(mode); }

    public enum SaveStatus { IDLE, SAVING, SAVED, FAILED, RESTORE_FAILED }

    private static final long TIMEOUT_NANOS = 5_000_000_000L;
    private static final int MAX_CAPTURES_PER_WATCH = 256;
    private final LongSupplier clock;
    private final Map<Long, Slot> slots = new LinkedHashMap<>();
    private final Map<WatchIdentity.Key, Long> identities = new java.util.HashMap<>();
    private final TransferBudget changesBudget;
    private boolean changesRejected;
    private boolean changesTooLarge;
    /** Presentation only: retained rows never satisfy a query or become a comparison baseline. */
    private final Map<Long, PendingDisplay<Entry>> pendingDisplays = new LinkedHashMap<>();
    /** Last selected result, retained to distinguish a transient availability failure from a later recovery. */
    private final Map<Long, WatchResult> previous = new LinkedHashMap<>();
    /** Last completed-step result for each known target, bounded independently for every watch. */
    private final Map<Long, LinkedHashMap<String, WatchResult>> targetHistory = new LinkedHashMap<>();
    /** Re-read the executor we just stepped, even if the next command has a different/no executor. */
    private final Map<Long, WatchResult> stepTargets = new LinkedHashMap<>();
    private final Map<UUID, String> executorNames = new LinkedHashMap<>();
    private final Map<WatchIdentity.Key, Entry> automaticChanges = new LinkedHashMap<>();
    private long nextAutomaticId;
    private int changesOffset;
    private boolean changesComplete;
    private List<PauseSource> currentSources = List.of();
    private long nextEntryId;
    private long nextRequestId;
    private long generation;
    public long generation() { return generation; }
    private long pauseId;
    private int sourceIndex = -1;
    private boolean continuingStep;
    private Consumer<List<WatchSpec>> changeListener = ignored -> {};
    private boolean initialDefinitionsReceived;
    private boolean initialRestoreFailed;
    private @Nullable InitialDefinitions pendingInitialization;
    private SaveStatus saveStatus = SaveStatus.IDLE;
    private long saveTransferId;
    private long saveStartedAt;
    private long saveTimeoutNanos = TIMEOUT_NANOS;
    private long revealId = -1;
    private long revealRevision;

    public ClientWatchState(LongSupplier clock) {
        this.clock = clock;
        changesBudget = new TransferBudget(TransferBudget.WATCH_CHANGES, clock);
    }

    public List<WatchSpec> definitions() {
        return slots.values().stream().map(slot -> slot.spec).toList();
    }

    public void setChangeListener(Consumer<List<WatchSpec>> listener) {
        changeListener = java.util.Objects.requireNonNull(listener);
    }

    public boolean initialDefinitionsReceived() { return initialDefinitionsReceived; }
    public boolean initialRestoreFailed() { return initialRestoreFailed; }

    /** A rejected remote snapshot stays session-only; never save an unseen server subset. */
    public void rejectInitialDefinitions() {
        if (initialDefinitionsReceived) return;
        initialRestoreFailed = true;
        changeListener = ignored -> {};
        saveStatus = SaveStatus.RESTORE_FAILED;
    }

    /**
     * First authenticated restore for this connection. Local edits made while waiting remain
     * local slots, including their current IDs/results; only missing server definitions are added.
     * The persistence listener is installed only after the complete union is transport-valid.
     */
    public boolean initializeDefinitions(List<WatchSpec> definitions, Predicate<List<WatchSpec>> canSave,
                                         Consumer<List<WatchSpec>> save) {
        if (initialDefinitionsReceived || initialRestoreFailed) return false;
        List<WatchSpec> checked = checkedDefinitions(definitions);
        pendingInitialization = new InitialDefinitions(checked, java.util.Objects.requireNonNull(canSave),
            java.util.Objects.requireNonNull(save));
        initialDefinitionsReceived = true;
        // On a size conflict, edits or explicit Retry reattempt the same bounded server snapshot.
        // They must never upload only the local subset and erase unseen server definitions.
        changeListener = ignored -> finishInitialization();
        return finishInitialization();
    }

    private boolean finishInitialization() {
        InitialDefinitions initial = pendingInitialization;
        if (initial == null) return false;
        Map<WatchIdentity.Key, WatchSpec> server = new LinkedHashMap<>();
        for (WatchSpec spec : initial.definitions()) server.putIfAbsent(WatchIdentity.key(spec), spec);
        Map<WatchIdentity.Key, WatchSpec> merged = new LinkedHashMap<>();
        slots.values().forEach(slot -> merged.put(WatchIdentity.key(slot.spec), slot.spec));
        boolean localAdditions = merged.keySet().stream().anyMatch(key -> !server.containsKey(key));
        server.forEach(merged::putIfAbsent);
        if (!initial.canSave().test(List.copyOf(merged.values()))) {
            saveStatus = SaveStatus.FAILED;
            return false;
        }
        for (var entry : merged.entrySet()) {
            if (identities.containsKey(entry.getKey())) continue;
            long id = ++nextEntryId;
            slots.put(id, new Slot(entry.getValue()));
            identities.put(entry.getKey(), id);
        }
        pendingInitialization = null;
        changeListener = initial.save();
        saveStatus = SaveStatus.IDLE;
        if (localAdditions) changeListener.accept(definitions());
        return true;
    }

    private record InitialDefinitions(List<WatchSpec> definitions, Predicate<List<WatchSpec>> canSave,
                                      Consumer<List<WatchSpec>> save) { }

    private static List<WatchSpec> checkedDefinitions(List<WatchSpec> definitions) {
        List<WatchSpec> checked = List.copyOf(definitions);
        if (checked.stream().map(WatchIdentity::rawKey).distinct().count() != checked.size())
            throw new IllegalArgumentException("Invalid watch definitions");
        return checked;
    }

    /** A world restore never writes back or restores stale observations from a prior connection. */
    public void restoreDefinitions(List<WatchSpec> definitions) {
        List<WatchSpec> checked = checkedDefinitions(definitions);
        reset();
        for (WatchSpec spec : checked) {
            WatchIdentity.Key key = WatchIdentity.key(spec);
            if (!identities.containsKey(key)) {
                long id = ++nextEntryId;
                identities.put(key, id);
                slots.put(id, new Slot(spec));
            }
        }
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
            if (executor == null && completed == null && slot.spec.kind() != WatchSpec.Kind.STORAGE_NBT
                && slot.spec.scoreHolder() == null) {
                executor = slot.spec.executor() != null ? slot.spec.executor() : selectedExecutor();
            }
            String name = executor == null ? "" : displayed != null && !displayed.targetName().isBlank()
                ? displayed.targetName() : executorNames.getOrDefault(executor, "");
            return new Entry(e.getKey(), slot.spec, slot.result, change,
                previousValue(before, change), completed, executor, name);
        }).toList();
    }

    /** Saved pins lead the list; every automatic change remains visible for the entire stop. */
    public List<Entry> displayedEntries() {
        return currentDisplayedEntries().stream().map(entry -> {
            PendingDisplay<Entry> pending = pendingDisplays.get(entry.id());
            if (pending == null) return entry;
            Entry displayed = pending.resolve(entry.displayedResult() == null ? null : entry);
            if (displayed == null || displayed == entry) {
                pendingDisplays.remove(entry.id());
                return entry;
            }
            // Retain the identity and change metadata too; never label an old value as a new executor's.
            return displayed;
        }).toList();
    }

    private List<Entry> currentDisplayedEntries() {
        expire();
        List<Entry> saved = new ArrayList<>();
        Set<WatchIdentity.Key> represented = new LinkedHashSet<>();
        Map<WatchIdentity.Key, Entry> automaticByField = new java.util.HashMap<>();
        for (Entry automatic : automaticChanges.values()) {
            automaticByField.putIfAbsent(WatchIdentity.key(automatic.spec(), automatic.displayedExecutor()), automatic);
        }
        for (Entry entry : entries()) {
            Entry displayed = entry;
            Entry automatic = automaticByField.get(WatchIdentity.key(entry.spec(), entry.displayedExecutor()));
            if (automatic != null) {
                represented.add(WatchIdentity.rawKey(automatic.spec()));
                // Server captures are available even when a query was not answered before stepping.
                displayed = new Entry(entry.id(), entry.spec(),
                    entry.completedStep() == null ? automatic.result() : entry.result(),
                    entry.completedStep() == null ? automatic.change() : entry.change(),
                    entry.completedStep() == null ? automatic.previousValue() : entry.previousValue(),
                    entry.completedStep() == null ? null : new Observation(automatic.result(), automatic.change(), automatic.previousValue()),
                    automatic.displayedExecutor(), automatic.executorName());
            }
            saved.add(displayed);
        }
        List<Entry> result = new ArrayList<>();
        saved.stream().filter(entry -> entry.spec().isPinned()).forEach(result::add);
        saved.stream().filter(entry -> !entry.spec().isPinned()).forEach(result::add);
        automaticChanges.values().stream().filter(entry -> !represented.contains(WatchIdentity.rawKey(entry.spec()))).forEach(result::add);
        return List.copyOf(result);
    }

    /** Start once at invalidation, not on render or on the subsequent pause acknowledgement. */
    private void retainPendingDisplays() {
        for (Entry entry : currentDisplayedEntries()) {
            if (!entry.automatic() && entry.displayedResult() != null) {
                pendingDisplays.computeIfAbsent(entry.id(), ignored -> new PendingDisplay<>(clock)).retain(entry);
            }
        }
    }

    /** Pages cannot repopulate another pause, or overwrite a completed transfer. */
    public void acceptChanges(long id, int offset, boolean last, List<WatchChange> changes) {
        if (pauseId <= 0 || id != pauseId || changesComplete || offset != changesOffset) return;
        long characters = changes.stream().mapToLong(delta -> TransferBudget.characters(delta.spec())
            + TransferBudget.characters(delta.before()) + TransferBudget.characters(delta.after())).sum();
        if (!changesBudget.accept(changes.size(), characters, last)) {
            rejectChanges();
            return;
        }
        for (WatchChange delta : changes) {
            Change change = compare(delta.before(), delta.after());
            // Oversized before/after values can have the same status while the server detects a real change.
            if (change == Change.UNCHANGED) change = Change.VALUE_CHANGED;
            UUID executor = delta.spec().executor();
            String name = delta.after().targetName().isBlank() ? delta.before().targetName() : delta.after().targetName();
            Entry existing = automaticChanges.get(WatchIdentity.rawKey(delta.spec()));
            automaticChanges.put(WatchIdentity.rawKey(delta.spec()), new Entry(existing == null ? --nextAutomaticId : existing.id(),
                delta.spec(), delta.after(), change, previousValue(delta.before(), change), null, executor, name));
        }
        changesOffset += changes.size();
        changesComplete = last;
    }

    /** Promote a temporary row without changing its executor or losing this pause's before/after value. */
    public boolean pinChange(long id) {
        expire();
        Entry entry = automaticChanges.values().stream().filter(candidate -> candidate.id() == id).findFirst().orElse(null);
        if (entry == null) return false;
        long existing = findId(entry.spec());
        if (existing >= 0) {
            reveal(existing);
            return true;
        }
        return add(entry.spec());
    }

    /** A failed transfer is never presented as a complete or partial observation. */
    public boolean changesRejected() { expire(); return changesRejected; }

    public boolean changesTooLarge() { return changesTooLarge; }

    public void acceptUnavailableChanges(long id, boolean tooLarge) {
        if (pauseId <= 0 || id != pauseId || changesComplete) return;
        rejectChanges();
        changesTooLarge = tooLarge;
    }

    private void rejectChanges() {
        automaticChanges.clear();
        changesComplete = true;
        changesRejected = true;
        changesBudget.reset();
    }

    private void clearChanges() {
        automaticChanges.clear();
        changesBudget.reset();
        changesRejected = false;
        changesTooLarge = false;
        changesOffset = 0;
        changesComplete = false;
    }

    public boolean add(WatchSpec spec) {
        if (findId(spec) >= 0) return false;
        long id = ++nextEntryId;
        putSlot(id, spec);
        changeListener.accept(definitions());
        reveal(id);
        return true;
    }

    /** Returns the stable saved row ID, adding this expression only when it is not already present. */
    public long addOrFind(WatchSpec spec) {
        long existing = findId(spec);
        if (existing >= 0) {
            reveal(existing);
            return existing;
        }
        add(spec);
        return nextEntryId;
    }

    /** Finds a saved equivalent expression, including a matching executor binding. */
    public long findId(WatchSpec spec) {
        return identities.getOrDefault(WatchIdentity.key(spec), -1L);
    }

    private void putSlot(long id, WatchSpec spec) {
        Slot previous = slots.put(id, new Slot(spec));
        if (previous != null) identities.remove(WatchIdentity.key(previous.spec));
        identities.put(WatchIdentity.key(spec), id);
    }

    private @Nullable Slot removeSlot(long id) {
        Slot removed = slots.remove(id);
        if (removed != null) identities.remove(WatchIdentity.key(removed.spec));
        return removed;
    }

    private void clearSlots() { slots.clear(); identities.clear(); }

    /** Replaces a saved definition in place and cancels all replies for its old expression. */
    public boolean update(long id, WatchSpec spec) {
        long existing = findId(spec);
        if (!slots.containsKey(id) || existing >= 0 && existing != id) return false;
        putSlot(id, spec);
        pendingDisplays.remove(id);
        previous.remove(id);
        targetHistory.remove(id);
        stepTargets.remove(id);
        pruneExecutorNames();
        changeListener.accept(definitions());
        reveal(id);
        return true;
    }

    /** Adds a deduplicated group atomically; callers can treat an already-present group as successful. */
    public boolean addAll(List<WatchSpec> specs) {
        List<WatchSpec> requested = List.copyOf(specs);
        Set<WatchIdentity.Key> known = new java.util.HashSet<>(identities.keySet());
        List<WatchSpec> missing = new ArrayList<>();
        for (WatchSpec spec : requested) {
            if (known.add(WatchIdentity.key(spec))) {
                missing.add(spec);
            }
        }
        if (missing.isEmpty()) return true;
        long lastAdded = -1;
        for (WatchSpec spec : missing) {
            lastAdded = ++nextEntryId;
            putSlot(lastAdded, spec);
        }
        changeListener.accept(definitions());
        reveal(lastAdded);
        return true;
    }

    /** Fills a partially pinned group, or removes the complete group in one persisted edit. */
    public void toggleAll(List<WatchSpec> specs) {
        Map<WatchIdentity.Key, WatchSpec> requested = new LinkedHashMap<>();
        for (WatchSpec spec : List.copyOf(specs)) requested.putIfAbsent(WatchIdentity.key(spec), spec);
        if (requested.isEmpty()) return;
        if (requested.keySet().stream().anyMatch(key -> !identities.containsKey(key))) {
            addAll(List.copyOf(requested.values()));
            return;
        }
        List<Long> removed = requested.keySet().stream().map(identities::get).toList();
        for (long id : removed) {
            removeSlot(id);
            pendingDisplays.remove(id);
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
        clearSlots();
        pendingDisplays.clear();
        previous.clear();
        targetHistory.clear();
        stepTargets.clear();
        pruneExecutorNames();
        changeListener.accept(List.of());
    }

    public @Nullable Removed removeForUndo(long id) {
        int index = new ArrayList<>(slots.keySet()).indexOf(id);
        Slot removed = removeSlot(id);
        if (removed == null) return null;
        pendingDisplays.remove(id);
        previous.remove(id);
        targetHistory.remove(id);
        stepTargets.remove(id);
        pruneExecutorNames();
        changeListener.accept(definitions());
        return new Removed(id, removed.spec, index, generation);
    }

    public void remove(long id) { removeForUndo(id); }

    public boolean restore(Removed removed) {
        if (removed == null || removed.generation() != generation || slots.containsKey(removed.id())
            || findId(removed.spec()) >= 0) return false;
        Map<Long, Slot> restored = new LinkedHashMap<>();
        int index = 0;
        boolean inserted = false;
        for (var entry : slots.entrySet()) {
            if (index++ == removed.index()) {
                restored.put(removed.id(), new Slot(removed.spec()));
                inserted = true;
            }
            restored.put(entry.getKey(), entry.getValue());
        }
        if (!inserted) restored.put(removed.id(), new Slot(removed.spec()));
        clearSlots();
        slots.putAll(restored);
        restored.forEach((id, slot) -> identities.put(WatchIdentity.key(slot.spec), id));
        nextEntryId = Math.max(nextEntryId, removed.id());
        changeListener.accept(definitions());
        reveal(removed.id());
        return true;
    }

    public boolean pin(long id, EntityRef executor) {
        Slot slot = slots.get(id);
        if (pauseId <= 0 || executor == null || slot == null || slot.spec.kind() == WatchSpec.Kind.STORAGE_NBT
            || slot.spec.scoreHolder() != null) return false;
        if (!rebind(id, slot.spec.withExecutor(executor.uuid()))) return false;
        executorNames.put(executor.uuid(), executor.name());
        return true;
    }

    public boolean unpin(long id) {
        Slot slot = slots.get(id);
        if (slot == null || !slot.spec.isPinned()) return false;
        WatchSpec following = slot.spec.withExecutor(null);
        long existing = findId(following);
        if (existing > 0 && existing != id) {
            // Keep the existing context-following row and its observations; discard only the pin.
            remove(id);
            reveal(existing);
            return true;
        }
        return rebind(id, following);
    }

    private boolean rebind(long id, WatchSpec spec) {
        long existing = findId(spec);
        if (existing >= 0 && existing != id) return false;
        // A new binding is an initial observation; cancel in-flight replies for the old target.
        putSlot(id, spec);
        pendingDisplays.remove(id);
        previous.remove(id);
        targetHistory.remove(id);
        stepTargets.remove(id);
        pruneExecutorNames();
        changeListener.accept(definitions());
        reveal(id);
        return true;
    }

    /** Only server-reported failures permit Retry; a local timeout must still fail this pause. */
    public boolean canRetry(long id) {
        expire();
        Slot slot = slots.get(id);
        return pauseId > 0 && slot != null && !slot.requestTimedOut && slot.result != null
            && (slot.result.status() == WatchResult.Status.UNAVAILABLE || slot.result.status() == WatchResult.Status.ERROR);
    }

    public void retry(long id) {
        if (!canRetry(id)) return;
        Slot slot = slots.get(id);
        retainPendingDisplays();
        slot.captures.remove(slot.requestSourceIndex);
        slot.requestId = 0;
        slot.requestedAt = 0;
        slot.requestTimedOut = false;
        slot.result = null;
    }

    public void retrySave() { changeListener.accept(definitions()); }

    public void saveStarted(long transferId) { saveStarted(transferId, TIMEOUT_NANOS); }

    public void saveStarted(long transferId, long timeoutNanos) {
        if (initialRestoreFailed) return;
        if (timeoutNanos <= 0) throw new IllegalArgumentException("invalid save timeout");
        saveTimeoutNanos = timeoutNanos;
        saveTransferId = transferId;
        saveStartedAt = clock.getAsLong();
        saveStatus = SaveStatus.SAVING;
    }

    public void saveFinished(long transferId, boolean success) {
        expire();
        if (saveStatus != SaveStatus.SAVING || transferId != saveTransferId) return;
        saveStatus = success ? SaveStatus.SAVED : SaveStatus.FAILED;
    }

    public SaveStatus saveStatus() { expire(); return saveStatus; }

    public long revealId() { return revealId; }
    public long revealRevision() { return revealRevision; }
    public void reveal(long id) {
        if (!slots.containsKey(id)) return;
        revealId = id;
        revealRevision++;
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
        if (!continuingStep) {
            clearHistory();
            pendingDisplays.clear();
        }
        continuingStep = false;
        pauseId = id;
        clearChanges();
        sourceIndex = index;
        currentSources = List.of();
        slots.values().forEach(Slot::clear);
    }

    public void selectSource(int index) {
        if (sourceIndex == index) return;
        retainPendingDisplays();
        sourceIndex = index;
        // Storage is independent of the selected executor; executor captures remain available on a revisit.
        slots.values().stream().filter(s -> s.spec.kind() != WatchSpec.Kind.STORAGE_NBT && !s.spec.isPinned())
            .forEach(s -> s.selectSource(index));
    }

    public void stepping() {
        retainPendingDisplays();
        clearChanges();
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
        pendingDisplays.clear();
        clearChanges();
        pauseId = 0;
        continuingStep = false;
        currentSources = List.of();
        clearHistory();
        slots.values().forEach(Slot::clear);
        pruneExecutorNames();
    }

    /** Release connection-scoped persistence before the enclosing debugger resets its state. */
    public void endConnection() {
        changeListener = ignored -> {};
        initialDefinitionsReceived = false;
        initialRestoreFailed = false;
        pendingInitialization = null;
    }

    /** Silent in-session reset; the current connection's persistence initialization survives. */
    public void reset() {
        generation++;
        resumed();
        clearSlots();
        executorNames.clear();
        saveStatus = initialRestoreFailed ? SaveStatus.RESTORE_FAILED
            : pendingInitialization == null ? SaveStatus.IDLE : SaveStatus.FAILED;
        saveTransferId = 0;
        saveStartedAt = 0;
        revealId = -1;
        // Keep counters monotonic so responses from a disconnected session cannot match.
    }

    /** One read per selection plus one previous-executor read per watch/stop; no automatic retries. */
    public List<Query> drainQueries() { return drainQueries(Integer.MAX_VALUE); }

    /** Create IDs and start timeouts only for queries the transport can send now. */
    public List<Query> drainQueries(int limit) {
        if (limit < 0) throw new IllegalArgumentException("negative query limit");
        expire();
        if (pauseId <= 0 || limit == 0) return List.of();
        List<Query> queries = new ArrayList<>();
        slots.forEach((watchId, slot) -> {
            if (queries.size() < limit && slot.requestId == 0 && slot.result == null) {
                slot.requestId = ++nextRequestId;
                slot.requestedAt = clock.getAsLong();
                slot.requestSourceIndex = slot.spec.isPinned() ? -1 : sourceIndex;
                queries.add(new Query(pauseId, slot.requestId, slot.requestSourceIndex, slot.spec, slot.spec.executor()));
            }
            UUID capturedEntity = entityId(stepTargets.get(watchId));
            if (queries.size() < limit && capturedEntity != null
                && slot.completionRequestId == 0 && slot.completionResult == null) {
                slot.completionRequestId = ++nextRequestId;
                slot.completionRequestedAt = clock.getAsLong();
                queries.add(new Query(pauseId, slot.completionRequestId, -1, slot.spec, capturedEntity));
            }
        });
        return List.copyOf(queries);
    }

    /** Includes unsent reads, so a step cannot overtake the rest of a throttled Watch burst. */
    public boolean hasUnresolvedQueries() {
        expire();
        if (pauseId <= 0) return false;
        for (var entry : slots.entrySet()) {
            Slot slot = entry.getValue();
            if (slot.result == null || entityId(stepTargets.get(entry.getKey())) != null && slot.completionResult == null)
                return true;
        }
        return false;
    }

    /** A client timeout is not a reply, even if the row now displays UNAVAILABLE. */
    public boolean hasTimedOutQueries() {
        expire();
        return slots.values().stream().anyMatch(slot -> slot.requestTimedOut || slot.completionTimedOut);
    }

    /** Stop this pause's remaining reads after transport failure; omitted values were not captured. */
    public void failUnresolvedQueries() {
        if (pauseId <= 0) return;
        slots.forEach((watchId, slot) -> {
            if (slot.result == null) {
                if (slot.requestId == 0) slot.requestSourceIndex = slot.spec.isPinned() ? -1 : sourceIndex;
                slot.result = WatchResult.absent(WatchResult.Status.UNAVAILABLE, "");
                slot.capture(slot.requestSourceIndex, slot.result);
            }
            if (entityId(stepTargets.get(watchId)) != null && slot.completionResult == null)
                slot.completionResult = WatchResult.absent(WatchResult.Status.UNAVAILABLE, "");
        });
    }

    public void accept(long id, long requestId, WatchResult result) {
        expire();
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
        if (!changesComplete && changesBudget.expired()) rejectChanges();
        long now = clock.getAsLong();
        if (saveStatus == SaveStatus.SAVING && now - saveStartedAt >= saveTimeoutNanos) saveStatus = SaveStatus.FAILED;
        slots.values().forEach(slot -> {
            if (slot.requestId != 0 && slot.result == null && now - slot.requestedAt >= TIMEOUT_NANOS) {
                slot.requestTimedOut = true;
                slot.result = WatchResult.absent(WatchResult.Status.UNAVAILABLE, "");
                slot.capture(slot.requestSourceIndex, slot.result);
            }
            if (slot.completionRequestId != 0 && slot.completionResult == null
                && now - slot.completionRequestedAt >= TIMEOUT_NANOS) {
                slot.completionTimedOut = true;
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
        boolean requestTimedOut;
        int requestSourceIndex;
        @Nullable WatchResult result;
        long completionRequestId;
        long completionRequestedAt;
        boolean completionTimedOut;
        @Nullable WatchResult completionResult;
        Slot(WatchSpec spec) { this.spec = spec; }
        void clear() {
            requestId = 0;
            requestTimedOut = false;
            result = null;
            captures.clear();
            completionRequestId = 0;
            completionTimedOut = false;
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
            requestTimedOut = false;
            result = captures.get(sourceIndex);
        }
    }
}
