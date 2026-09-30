package works.nuty.codon.client.state;

import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.SourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Request-id guarded previews of the server's exact Brigadier stage ranges. */
public final class ClientStagePreviewState {
    public enum Status { LOADING, READY, NOT_FOUND, UNAUTHORIZED, INVALID }
    public record StageSpan(int index, int start, int end, boolean terminal) { }
    public record Preview(Status status, String savedCommand, List<StageSpan> spans) {
        public Preview { spans = List.copyOf(spans); }
    }
    private final Map<SourceLocation, Entry> entries = new HashMap<>();
    private long nextRequestId;

    public synchronized long begin(SourceLocation location) {
        if (location instanceof SourceLocation.Player) throw new IllegalArgumentException("Player has no saved stages");
        long id = ++nextRequestId;
        if (id <= 0) id = nextRequestId = 1;
        entries.put(location, new Entry(id, new Preview(Status.LOADING, "", List.of())));
        return id;
    }

    public synchronized boolean accept(long requestId, SourceLocation location, Status status,
                                       String savedCommand, List<StageSpan> spans) {
        Entry entry = entries.get(location);
        if (entry == null || entry.requestId() != requestId || status == Status.LOADING) return false;
        entries.put(location, new Entry(requestId, new Preview(status, savedCommand, spans)));
        return true;
    }

    public synchronized @Nullable Preview get(SourceLocation location) {
        Entry entry = entries.get(location);
        return entry == null ? null : entry.preview();
    }

    /** A reload invalidates READY text; an in-flight request must retain its request id. */
    public static boolean needsRefresh(@Nullable Preview preview, String savedCommand) {
        return preview == null || preview.status() == Status.READY && !preview.savedCommand().equals(savedCommand);
    }

    public synchronized void reset() { entries.clear(); nextRequestId = 0; }

    private record Entry(long requestId, Preview preview) { }
}
