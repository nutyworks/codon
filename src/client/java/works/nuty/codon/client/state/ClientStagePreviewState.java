package works.nuty.codon.client.state;

import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.TransferBudget;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Request-id guarded previews of the server's exact Brigadier stage ranges. */
public final class ClientStagePreviewState {
    public enum Status { LOADING, READY, NOT_FOUND, UNAUTHORIZED, INVALID, TIMED_OUT }
    public record StageSpan(int index, int start, int end, boolean terminal) { }
    public record Preview(Status status, String savedCommand, List<StageSpan> spans) {
        public Preview { spans = List.copyOf(spans); }
    }
    private final Map<SourceLocation, Entry> entries = new HashMap<>();
    private final LongSupplier clock;
    private long nextRequestId;

    public ClientStagePreviewState() { this(System::nanoTime); }

    public ClientStagePreviewState(LongSupplier clock) { this.clock = java.util.Objects.requireNonNull(clock); }

    public synchronized long begin(SourceLocation location) {
        return begin(location, 0);
    }

    /** A missing reply gets one automatic retry; explicit Reload starts a fresh attempt. */
    public synchronized long beginAutomatic(SourceLocation location) {
        Entry previous = entries.get(location);
        if (previous != null && expired(previous)) {
            if (previous.retries() >= 1) return 0;
            return begin(location, previous.retries() + 1);
        }
        return begin(location, 0);
    }

    private long begin(SourceLocation location, int retries) {
        if (location instanceof SourceLocation.Player) throw new IllegalArgumentException("Player has no saved stages");
        long id = ++nextRequestId;
        if (id <= 0) id = nextRequestId = 1;
        entries.put(location, new Entry(id, new Preview(Status.LOADING, "", List.of()), clock.getAsLong(), retries));
        return id;
    }

    public synchronized boolean accept(long requestId, SourceLocation location, Status status,
                                       String savedCommand, List<StageSpan> spans) {
        Entry entry = entries.get(location);
        if (entry == null || entry.requestId() != requestId || status == Status.LOADING || expired(entry)) return false;
        entries.put(location, new Entry(requestId, new Preview(status, savedCommand, spans), entry.startedAt(), entry.retries()));
        return true;
    }

    public synchronized @Nullable Preview get(SourceLocation location) {
        Entry entry = entries.get(location);
        return entry == null ? null : expired(entry) ? new Preview(Status.TIMED_OUT, "", List.of()) : entry.preview();
    }

    /** A reload invalidates READY text; an in-flight request must retain its request id. */
    public static boolean needsRefresh(@Nullable Preview preview, String savedCommand) {
        return preview == null || preview.status() == Status.READY && !preview.savedCommand().equals(savedCommand);
    }

    public synchronized boolean refreshNeeded(SourceLocation location, String savedCommand) {
        return needsRefresh(get(location), savedCommand) || loadingExpired(location);
    }

    public synchronized boolean loadingExpired(SourceLocation location) {
        Entry entry = entries.get(location);
        return entry != null && expired(entry) && entry.retries() < 1;
    }

    private boolean expired(Entry entry) {
        return entry.preview().status() == Status.LOADING
            && clock.getAsLong() - entry.startedAt() >= TransferBudget.TIMEOUT_NANOS;
    }

    public synchronized void reset() { entries.clear(); nextRequestId = 0; }

    private record Entry(long requestId, Preview preview, long startedAt, int retries) { }
}
