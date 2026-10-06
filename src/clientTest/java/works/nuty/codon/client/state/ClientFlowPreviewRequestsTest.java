package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

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

    @Test void droppedLoadingPreviewRetriesAfterDeadlineWithANewId() {
        var now = new AtomicLong();
        var previews = new ClientStagePreviewState(now::get);
        var requests = new ClientFlowPreviewRequests();
        var snapshot = pause(1);
        assertTrue(requests.needsRequestWithState(snapshot, LOCATION, previews, "say ready"));
        requests.requested(LOCATION);
        long old = previews.begin(LOCATION);
        now.set(TransferBudget.TIMEOUT_NANOS - 1);
        assertFalse(requests.needsRequestWithState(pause(2), LOCATION, previews, "say ready"));
        now.incrementAndGet();
        assertTrue(requests.needsRequestWithState(snapshot, LOCATION, previews, "say ready"));
        assertTrue(previews.refreshNeeded(LOCATION, "say ready"));
        assertFalse(previews.accept(old, LOCATION, ClientStagePreviewState.Status.READY, "say ready", List.of()));
        long fresh = previews.beginAutomatic(LOCATION);
        requests.requested(LOCATION);
        assertFalse(requests.needsRequestWithState(snapshot, LOCATION, previews, "say ready"));
        assertFalse(previews.accept(old, LOCATION, ClientStagePreviewState.Status.READY, "say ready", List.of()));
        assertTrue(previews.accept(fresh, LOCATION, ClientStagePreviewState.Status.READY, "say ready",
            List.of(new ClientStagePreviewState.StageSpan(0, 0, 9, true))));
    }

    @Test void secondLostPreviewStopsAutomaticRetriesAndExplicitReloadRecovers() {
        var now = new AtomicLong();
        var previews = new ClientStagePreviewState(now::get);
        var requests = new ClientFlowPreviewRequests();
        var snapshot = pause(1);
        long old = previews.beginAutomatic(LOCATION);
        requests.requested(LOCATION);
        now.set(TransferBudget.TIMEOUT_NANOS);
        assertTrue(requests.needsRequestWithState(snapshot, LOCATION, previews, "say ready"));
        long retry = previews.beginAutomatic(LOCATION);
        assertTrue(retry > old);
        now.addAndGet(TransferBudget.TIMEOUT_NANOS);
        assertEquals(ClientStagePreviewState.Status.TIMED_OUT, previews.get(LOCATION).status());
        assertFalse(previews.refreshNeeded(LOCATION, "say ready"));
        assertFalse(requests.needsRequestWithState(snapshot, LOCATION, previews, "say ready"));
        assertFalse(requests.needsRequestWithState(pause(2), LOCATION, previews, "say ready"));
        assertEquals(0, previews.beginAutomatic(LOCATION));
        assertFalse(previews.accept(retry, LOCATION, ClientStagePreviewState.Status.READY, "say ready", List.of()));
        long manual = previews.begin(LOCATION);
        assertTrue(previews.accept(manual, LOCATION, ClientStagePreviewState.Status.READY, "say ready", List.of()));
    }
}
