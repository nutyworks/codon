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
    /** Query identity retained across an invalidation so only compatible displays survive. */
    private @Nullable WatchEditorQuery invalidatedQuery;
    /** Presentation-only page retained while a compatible replacement is pending. */
    private final PendingDisplay<WatchEditorPage> displayed;
    private boolean waiting;
    private boolean timedOut;

    public ClientWatchEditorState(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
        displayed = new PendingDisplay<>(clock);
    }

    public WatchSpec.Kind kind() { return kind; }
    public void kind(WatchSpec.Kind kind) { this.kind = kind; }
    public Draft draft(WatchSpec.Kind kind) { return drafts.getOrDefault(kind, Draft.EMPTY); }
    public void draft(WatchSpec.Kind kind, Draft draft) { drafts.put(kind, draft); }

    public void request(long pauseId, int sourceIndex, WatchEditorQuery query) {
        if (current != null && current.pauseId() == pauseId && current.sourceIndex() == sourceIndex
            && current.query().equals(query)) return;
        WatchEditorQuery previous = current == null ? invalidatedQuery : current.query();
        if (previous != null && compatible(previous, query)) displayed.retain(page);
        else displayed.clear();
        invalidatedQuery = null;
        current = new Query(pauseId, ++nextRequest, sourceIndex, query);
        unsent = current;
        requestedAt = clock.getAsLong();
        page = null;
        waiting = true;
        timedOut = false;
    }

    public List<Query> drainQueries() {
        expire();
        if (unsent == null) return List.of();
        Query request = unsent;
        unsent = null;
        requestedAt = clock.getAsLong();
        return List.of(request);
    }

    public void accept(long pauseId, long requestId, WatchEditorPage result) {
        expire();
        if (!waiting || current == null || current.pauseId() != pauseId || current.requestId() != requestId) return;
        page = result;
        waiting = false;
    }

    public @Nullable WatchEditorPage page() { expire(); return page; }
    /** A visual-only page that may outlive {@link #page()} for at most 250ms. */
    public @Nullable WatchEditorPage displayedPage() { expire(); return displayed.resolve(page); }
    public boolean waiting() { expire(); return waiting; }
    public boolean timedOut() { expire(); return timedOut; }

    public void retry() {
        Query previous = current;
        if (previous == null) return;
        // Preserve the page only for display; a retry always receives a new request ID.
        displayed.retain(page);
        current = new Query(previous.pauseId(), ++nextRequest, previous.sourceIndex(), previous.query());
        unsent = current;
        requestedAt = clock.getAsLong();
        page = null;
        waiting = true;
        timedOut = false;
    }

    /**
     * Drop authoritative data for a pause transition while retaining a compatible
     * presentation value for its short grace period.  Late replies cannot match.
     */
    public void invalidate() {
        if (current != null) invalidatedQuery = current.query();
        displayed.retain(page);
        current = unsent = null;
        page = null;
        waiting = timedOut = false;
    }

    public void cancel() {
        current = unsent = null;
        page = null;
        invalidatedQuery = null;
        displayed.clear();
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

    private static boolean compatible(WatchEditorQuery previous, WatchEditorQuery next) {
        return previous.mode() == next.mode() && previous.kind() == next.kind();
    }
}
