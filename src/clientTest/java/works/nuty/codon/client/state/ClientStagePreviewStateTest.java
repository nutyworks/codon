package works.nuty.codon.client.state;

import java.util.List;
import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientStagePreviewStateTest {
    @Test void unselectedReloadedRowRefreshesOnceAndRetainsTheOtherRowsPreview() {
        var function = new FunctionId("pack", "main");
        var first = new SourceLocation.Function(new FunctionLocation(function, 1));
        var second = new SourceLocation.Function(new FunctionLocation(function, 2));
        var previews = new ClientStagePreviewState();
        long one = previews.begin(first), two = previews.begin(second);
        previews.accept(one, first, ClientStagePreviewState.Status.READY, "say old", List.of(new ClientStagePreviewState.StageSpan(0, 0, 7, true)));
        previews.accept(two, second, ClientStagePreviewState.Status.READY, "say second", List.of(new ClientStagePreviewState.StageSpan(0, 0, 10, true)));
        var retained = previews.get(second);
        var sources = new ClientFunctionSourceState();
        sources.select(function);
        long read = sources.drainRequests().getFirst().requestId();
        sources.accept(new ClientFunctionSourceState.SourcePage(read, ClientFunctionSourceState.Status.READY,
            function, "fixture", "old", false, 0, true, List.of("say old", "say second")));
        sources.rememberBrowseView(0, 0, 2, 0, 0);
        sources.refreshSource();
        read = sources.drainRequests().getFirst().requestId();
        sources.accept(new ClientFunctionSourceState.SourcePage(read, ClientFunctionSourceState.Status.READY,
            function, "fixture", "new", false, 0, true, List.of("say replacement", "say second")));
        String changed = sources.document().lines().getFirst().trim();
        assertTrue(ClientStagePreviewState.needsRefresh(previews.get(first), changed));
        int requests = 0;
        long latest = 0;
        for (int hoverFrame = 0; hoverFrame < 4; hoverFrame++)
            if (ClientStagePreviewState.needsRefresh(previews.get(first), changed)) { latest = previews.begin(first); requests++; }
        assertEquals(1, requests, "LOADING must retain the request id across hover frames");
        assertFalse(previews.accept(one, first, ClientStagePreviewState.Status.READY, "say old", List.of()));
        assertTrue(previews.accept(latest, first, ClientStagePreviewState.Status.READY, changed,
            List.of(new ClientStagePreviewState.StageSpan(0, 0, changed.length(), true))));
        assertFalse(ClientStagePreviewState.needsRefresh(previews.get(first), changed));
        assertSame(retained, previews.get(second));
        assertEquals(2, sources.browseView().selectedLine(), "hover refresh does not select the changed row");
    }
}
