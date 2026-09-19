package works.nuty.codon.client.state;

import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.WatchEditorPage;
import works.nuty.codon.core.model.WatchEditorQuery;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Transient, read-only editor requests. Drafts never become saved watch definitions. */
public final class ClientWatchEditorState {
    public record Draft(String target, String path, String entity) {
        public static final Draft EMPTY = new Draft("", "", "");
    }
    public record Query(long pauseId, long requestId, int sourceIndex, WatchEditorQuery query) { }

    private final LongSupplier clock;
    private final EnumMap<WatchSpec.Kind, Draft> drafts = new EnumMap<>(WatchSpec.Kind.class);
    private WatchSpec.Kind kind = WatchSpec.Kind.SCORE;
    private long nextRequest;
    private long requestedAt;
    private @Nullable Query current;
    private @Nullable Query unsent;
    private @Nullable WatchEditorPage page;
    private boolean waiting;
    private boolean timedOut;

    public ClientWatchEditorState(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }

    public WatchSpec.Kind kind() { return kind; }
    public void kind(WatchSpec.Kind kind) { this.kind = kind; }
    public Draft draft(WatchSpec.Kind kind) { return drafts.getOrDefault(kind, Draft.EMPTY); }
    public void draft(WatchSpec.Kind kind, Draft draft) { drafts.put(kind, draft); }

    public void request(long pauseId, int sourceIndex, WatchEditorQuery query) {
        if (current != null && current.pauseId() == pauseId && current.sourceIndex() == sourceIndex
            && current.query().equals(query)) return;
        current = new Query(pauseId, ++nextRequest, sourceIndex, query);
        unsent = current;
        requestedAt = clock.getAsLong();
        page = null;
        waiting = true;
        timedOut = false;
    }

    public List<Query> drainQueries() {
        if (unsent == null) return List.of();
        Query request = unsent;
        unsent = null;
        return List.of(request);
    }

    public void accept(long pauseId, long requestId, WatchEditorPage result) {
        if (!waiting || current == null || current.pauseId() != pauseId || current.requestId() != requestId) return;
        page = result;
        waiting = false;
    }

    public @Nullable WatchEditorPage page() { expire(); return page; }
    public boolean waiting() { expire(); return waiting; }
    public boolean timedOut() { expire(); return timedOut; }

    public void retry() {
        Query previous = current;
        cancel();
        if (previous != null) request(previous.pauseId(), previous.sourceIndex(), previous.query());
    }

    public void cancel() {
        current = unsent = null;
        page = null;
        waiting = timedOut = false;
    }

    public void reset() {
        cancel();
        drafts.clear();
        kind = WatchSpec.Kind.SCORE;
        // Requests from a disconnected world must never match a request after rejoin.
    }

    private void expire() {
        if (waiting && clock.getAsLong() - requestedAt >= 5_000_000_000L) {
            waiting = false;
            timedOut = true;
            unsent = null;
            page = new WatchEditorPage(WatchResult.Status.UNAVAILABLE, List.of(), 0, false, null);
        }
    }
}
