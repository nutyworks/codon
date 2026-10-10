package works.nuty.codon.client.state;

import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.service.BreakpointRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Consumer;

/** Server-confirmed breakpoint definitions, paged snapshots, and per-target edit status. */
public final class ClientBreakpointState {
    public enum Action { TOGGLE, SAVE, DELETE }
    public enum Result { APPLIED, STALE_SOURCE, INVALID_TARGET, NO_PERMISSION, FAILED, TIMED_OUT }
    public record Edit(long requestId, Action action, BreakpointDefinition definition) { }
    private static final long TIMEOUT_NANOS = 10_000_000_000L;
    private final LongSupplier clock;
    private final Consumer<Result> inlineFailure;
    private final Map<BreakpointTarget, BreakpointDefinition> definitions = new HashMap<>();
    private final Map<BreakpointTarget, Pending> pending = new HashMap<>();
    private final Map<BreakpointTarget, Result> errors = new HashMap<>();
    private final List<BreakpointDefinition> incoming = new ArrayList<>();
    private long incomingTransferId;
    private long completedTransferId;
    private long nextRequestId;

    public ClientBreakpointState() { this(System::nanoTime); }
    public ClientBreakpointState(LongSupplier clock) { this(clock, result -> { }); }
    public ClientBreakpointState(LongSupplier clock, Consumer<Result> inlineFailure) {
        this.clock = clock;
        this.inlineFailure = inlineFailure;
    }

    public synchronized boolean acceptPage(long transferId, int offset, boolean last,
                                           List<BreakpointDefinition> page) {
        if (transferId <= completedTransferId || offset < 0 || page.size() > 48
            || offset > BreakpointRegistry.MAX_DEFINITIONS - page.size()) return false;
        if (transferId > incomingTransferId) {
            incomingTransferId = transferId;
            incoming.clear();
        }
        if (transferId != incomingTransferId || offset != incoming.size()) return false;
        incoming.addAll(page);
        if (!last) return false;
        definitions.clear();
        for (BreakpointDefinition definition : incoming) definitions.put(definition.target(), definition);
        completedTransferId = transferId;
        incomingTransferId = 0;
        incoming.clear();
        return true;
    }

    public synchronized @Nullable Edit begin(Action action, BreakpointDefinition definition) {
        BreakpointTarget target = definition.target();
        expire(target);
        if (pending.containsKey(target)) return null;
        long id = ++nextRequestId;
        if (id <= 0) id = nextRequestId = 1;
        pending.put(target, new Pending(id, clock.getAsLong(), action));
        errors.remove(target);
        return new Edit(id, action, definition);
    }

    public synchronized void finish(long requestId, Result result) {
        expirePending();
        BreakpointTarget target = null;
        for (var entry : pending.entrySet()) {
            if (entry.getValue().requestId() == requestId) { target = entry.getKey(); break; }
        }
        if (target == null) return;
        Pending entry = pending.remove(target);
        if (result != Result.APPLIED) {
            errors.put(target, result);
            if (entry.action() == Action.TOGGLE) inlineFailure.accept(result);
        }
    }

    public synchronized @Nullable BreakpointDefinition get(BreakpointTarget target) {
        return definitions.get(target);
    }

    public synchronized List<BreakpointDefinition> definitions() {
        return List.copyOf(definitions.values());
    }

    /** The first complete authoritative snapshot has arrived for this connection. */
    public synchronized boolean ready() {
        return completedTransferId > 0;
    }

    public synchronized boolean pending(BreakpointTarget target) {
        expire(target);
        return pending.containsKey(target);
    }

    public synchronized @Nullable Result error(BreakpointTarget target) {
        expire(target);
        return errors.get(target);
    }

    private void expire(BreakpointTarget target) {
        Pending entry = pending.get(target);
        if (entry != null && clock.getAsLong() - entry.startedAt() >= TIMEOUT_NANOS) {
            pending.remove(target);
            errors.put(target, Result.TIMED_OUT);
            if (entry.action() == Action.TOGGLE) inlineFailure.accept(Result.TIMED_OUT);
        }
    }

    /** Poll even when the marker is hidden; no automatic retry or optimistic edit. */
    public synchronized void expirePending() {
        for (var target : List.copyOf(pending.keySet())) expire(target);
    }

    public synchronized void reset() {
        definitions.clear();
        pending.clear();
        errors.clear();
        incoming.clear();
        incomingTransferId = completedTransferId = 0;
    }

    private record Pending(long requestId, long startedAt, Action action) { }
}
