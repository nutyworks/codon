package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClientFlowPreviewRequestsTest {
    private static final SourceLocation LOCATION = new SourceLocation.Function(new FunctionLocation(new FunctionId("pack", "main"), 1));
    private static PauseSnapshot pause(long id) {
        return new PauseSnapshot(LOCATION, CommandSnippet.plain("say ready"), 0,
            List.of(), List.of(), List.of(), PauseReason.BREAKPOINT, id);
    }

    @Test void failedPreviewsRetryOnlyOnLaterSnapshotsAndNeverReplaceLoadingRequests() {
        for (var failure : List.of(ClientStagePreviewState.Status.NOT_FOUND,
                ClientStagePreviewState.Status.UNAUTHORIZED, ClientStagePreviewState.Status.INVALID)) {
            var requests = new ClientFlowPreviewRequests();
            var previews = new ClientStagePreviewState();
            var first = pause(1);
            assertTrue(requests.needsRequest(first, LOCATION, null, "say ready"));
            requests.requested(LOCATION);
            long request = previews.begin(LOCATION);
            previews.accept(request, LOCATION, failure, "", List.of());
            assertFalse(requests.needsRequest(first, LOCATION, previews.get(LOCATION), "say ready"));
            var next = pause(2);
            assertTrue(requests.needsRequest(next, LOCATION, previews.get(LOCATION), "say ready"), failure.toString());
            requests.requested(LOCATION);
            request = previews.begin(LOCATION);
            assertFalse(requests.needsRequest(pause(3), LOCATION, previews.get(LOCATION), "say ready"));
            previews.accept(request, LOCATION, failure, "", List.of());
            assertTrue(requests.needsRequest(pause(4), LOCATION, previews.get(LOCATION), "say ready"));
        }
    }

    @Test void matchingReadyPreviewIsRetainedAndCommandChangesAreBoundedPerSnapshot() {
        var requests = new ClientFlowPreviewRequests();
        var preview = new ClientStagePreviewState.Preview(ClientStagePreviewState.Status.READY, "say old", List.of());
        var snapshot = pause(1);
        assertFalse(requests.needsRequest(snapshot, LOCATION, preview, "say old"));
        assertTrue(requests.needsRequest(snapshot, LOCATION, preview, "say ready"));
        requests.requested(LOCATION);
        assertFalse(requests.needsRequest(snapshot, LOCATION, preview, "say ready"));
    }
}
