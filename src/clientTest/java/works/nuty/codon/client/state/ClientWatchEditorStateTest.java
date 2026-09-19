package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ClientWatchEditorStateTest {
    private static WatchEditorQuery query() {
        return new WatchEditorQuery(WatchEditorQuery.Mode.OBJECTIVES, WatchSpec.Kind.SCORE, "", "", null, "", 0);
    }
    private static WatchEditorPage page() {
        return new WatchEditorPage(WatchResult.Status.VALUE, List.of(), 0, false, null);
    }
    @Test void sourceChangesAndReconnectRejectStaleReplies() {
        var state = new ClientWatchEditorState(() -> 0);
        state.request(1, 0, query());
        var stale = state.drainQueries().getFirst();
        state.request(1, 1, query());
        var current = state.drainQueries().getFirst();
        state.accept(1, stale.requestId(), page());
        assertNull(state.page());
        state.accept(1, current.requestId(), page());
        assertNotNull(state.page());
        state.reset();
        state.request(1, 1, query());
        state.accept(1, current.requestId(), page());
        assertNull(state.page());
        assertTrue(state.waiting());
    }
    @Test void timeoutIsStableUntilExplicitRetryAndRejectsLateReply() {
        var clock = new AtomicLong();
        var state = new ClientWatchEditorState(clock::get);
        state.request(0, -1, query());
        var stale = state.drainQueries().getFirst();
        clock.set(5_000_000_000L);
        assertTrue(state.timedOut());
        state.accept(0, stale.requestId(), page());
        assertEquals(WatchResult.Status.UNAVAILABLE, state.page().status());
        state.request(0, -1, query());
        assertTrue(state.drainQueries().isEmpty());
        state.retry();
        var current = state.drainQueries().getFirst();
        assertNotEquals(stale.requestId(), current.requestId());
        state.accept(0, current.requestId(), page());
        assertFalse(state.waiting());
        assertFalse(state.timedOut());
        assertEquals(WatchResult.Status.VALUE, state.page().status());
    }
}
