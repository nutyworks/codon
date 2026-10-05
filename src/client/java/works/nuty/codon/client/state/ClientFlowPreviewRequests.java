package works.nuty.codon.client.state;

import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.SourceLocation;

import java.util.HashSet;
import java.util.Set;

/** Flow preview attempts are bounded per snapshot, including terminal server failures. */
public final class ClientFlowPreviewRequests {
    private @Nullable PauseSnapshot snapshot;
    private final Set<SourceLocation> requested = new HashSet<>();

    public boolean needsRequest(PauseSnapshot snapshot, SourceLocation location,
                                ClientStagePreviewState.@Nullable Preview preview, String command) {
        if (this.snapshot != snapshot) {
            this.snapshot = snapshot;
            requested.clear();
        }
        return !(location instanceof SourceLocation.Player) && !requested.contains(location)
            && (ClientStagePreviewState.needsRefresh(preview, command)
                || preview != null && switch (preview.status()) {
                    case NOT_FOUND, UNAUTHORIZED, INVALID -> true;
                    case LOADING, READY, TIMED_OUT -> false;
                });
    }

    /** One retry per expired request, even if this snapshot already attempted it. */
    public boolean needsRequestWithState(PauseSnapshot snapshot, SourceLocation location,
                                         ClientStagePreviewState previews, String command) {
        if (this.snapshot != snapshot) {
            this.snapshot = snapshot;
            requested.clear();
        }
        if (previews.loadingExpired(location) && !(location instanceof SourceLocation.Player)) return true;
        return needsRequest(snapshot, location, previews.get(location), command);
    }

    public void requested(SourceLocation location) { requested.add(location); }
}
