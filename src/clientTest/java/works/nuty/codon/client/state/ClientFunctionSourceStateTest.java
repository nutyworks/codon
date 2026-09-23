package works.nuty.codon.client.state;

import java.util.List;
import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.FunctionId;
import works.nuty.codon.core.model.FunctionLocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientFunctionSourceStateTest {
    private static final FunctionId FIRST = new FunctionId("demo", "tick");
    private static final FunctionId SECOND = new FunctionId("demo", "sub/hit");

    @Test
    void assemblesContiguousListAndSourcePages() {
        ClientFunctionSourceState state = new ClientFunctionSourceState();
        state.open();
        long list = ((ClientFunctionSourceState.Request.ListFunctions) state.drainRequests().getFirst()).requestId();
        state.accept(new ClientFunctionSourceState.ListPage(list, ClientFunctionSourceState.Status.READY, 0, false, List.of(FIRST)));
        state.accept(new ClientFunctionSourceState.ListPage(list, ClientFunctionSourceState.Status.READY, 1, true, List.of(SECOND)));
        assertEquals(List.of(FIRST, SECOND), state.functions());
        assertEquals(ClientFunctionSourceState.Status.READY, state.listStatus());

        state.select(FIRST);
        long read = ((ClientFunctionSourceState.Request.ReadFunction) state.drainRequests().getFirst()).requestId();
        state.accept(new ClientFunctionSourceState.SourcePage(read, ClientFunctionSourceState.Status.READY, FIRST, "file/demo",
            "a".repeat(64), false, 0, false, List.of("say one")));
        state.accept(new ClientFunctionSourceState.SourcePage(read, ClientFunctionSourceState.Status.READY, FIRST, "file/demo",
            "a".repeat(64), false, 1, true, List.of("say two")));

        assertEquals(List.of("say one", "say two"), state.document().lines());
        assertEquals("a".repeat(64), state.document().revision());
        assertEquals(ClientFunctionSourceState.Status.READY, state.sourceStatus());
    }

    @Test
    void rejectsAStaleSelectionAndARevisionMixedAcrossPages() {
        ClientFunctionSourceState state = new ClientFunctionSourceState();
        state.select(FIRST);
        long stale = ((ClientFunctionSourceState.Request.ReadFunction) state.drainRequests().getFirst()).requestId();
        state.select(SECOND);
        long current = ((ClientFunctionSourceState.Request.ReadFunction) state.drainRequests().getFirst()).requestId();

        state.accept(new ClientFunctionSourceState.SourcePage(stale, ClientFunctionSourceState.Status.READY, FIRST, "file/demo",
            "a".repeat(64), false, 0, true, List.of("stale")));
        assertNull(state.document());
        state.accept(new ClientFunctionSourceState.SourcePage(current, ClientFunctionSourceState.Status.READY, SECOND, "file/demo",
            "a".repeat(64), false, 0, false, List.of("first")));
        state.accept(new ClientFunctionSourceState.SourcePage(current, ClientFunctionSourceState.Status.READY, SECOND, "file/demo",
            "b".repeat(64), false, 1, true, List.of("mixed")));

        assertNull(state.document());
        assertEquals(ClientFunctionSourceState.Status.ERROR, state.sourceStatus());
        assertTrue(state.drainRequests().isEmpty());
    }

    @Test
    void disconnectResetMakesEveryOldReplyStale() {
        ClientFunctionSourceState state = new ClientFunctionSourceState();
        state.open();
        long request = ((ClientFunctionSourceState.Request.ListFunctions) state.drainRequests().getFirst()).requestId();
        state.reset();
        state.accept(new ClientFunctionSourceState.ListPage(request, ClientFunctionSourceState.Status.READY, 0, true, List.of(FIRST)));

        assertTrue(state.functions().isEmpty());
        assertEquals(ClientFunctionSourceState.Status.IDLE, state.listStatus());
        assertFalse(state.document() != null);
    }

    @Test
    void retainsBrowserScrollAndStageSelectionUntilAUserSelectsAnotherFunction() {
        ClientFunctionSourceState state = new ClientFunctionSourceState();
        state.select(FIRST);
        state.rememberBrowseView(4, 12, 13, 2, 6);
        assertEquals(new ClientFunctionSourceState.BrowseView(4, 12, 13, 2, 6), state.browseView());

        state.select(FIRST);
        assertEquals(new ClientFunctionSourceState.BrowseView(4, 12, 13, 2, 6), state.browseView());
        state.select(SECOND);
        assertEquals(new ClientFunctionSourceState.BrowseView(0, 0, -1, -1, 0), state.browseView());
    }

    @Test
    void followsLoadedFunctionReferencesAndRestoresEachFunctionsViewOnBack() {
        ClientFunctionSourceState state = new ClientFunctionSourceState();
        state.open();
        long list = ((ClientFunctionSourceState.Request.ListFunctions) state.drainRequests().getFirst()).requestId();
        state.accept(new ClientFunctionSourceState.ListPage(list, ClientFunctionSourceState.Status.READY, 0, true,
            List.of(FIRST, SECOND)));
        state.select(FIRST);
        state.rememberBrowseView(2, 7, 8, 1, 5);

        assertTrue(state.follow(SECOND));
        state.rememberBrowseView(0, 3, 4, -1, 0);
        assertTrue(state.canGoBack());
        assertTrue(state.goBack());
        assertEquals(FIRST, state.selected());
        assertEquals(new ClientFunctionSourceState.BrowseView(2, 7, 8, 1, 5), state.browseView());
        assertFalse(state.follow(new FunctionId("demo", "missing")));
    }

    @Test
    void opensBreakpointSourceAtItsLineAndPreservesThePreviousView() {
        ClientFunctionSourceState state = new ClientFunctionSourceState();
        state.select(FIRST);
        state.rememberBrowseView(2, 7, 8, 1, 5);

        state.selectAt(new FunctionLocation(SECOND, 42));
        assertEquals(SECOND, state.selected());
        assertEquals(new ClientFunctionSourceState.BrowseView(0, 41, 42, -1, 0), state.browseView());

        state.select(FIRST);
        assertEquals(new ClientFunctionSourceState.BrowseView(2, 7, 8, 1, 5), state.browseView());
    }
}
