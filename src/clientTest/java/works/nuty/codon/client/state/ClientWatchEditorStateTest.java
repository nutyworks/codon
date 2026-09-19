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
    private static WatchEditorPage page(String value) {
        return new WatchEditorPage(WatchResult.Status.VALUE, List.of(), 0, false,
            new WatchResult(WatchResult.Status.VALUE, value, "", ""));
    }
    private static WatchEditorQuery query(String search) {
        return new WatchEditorQuery(WatchEditorQuery.Mode.OBJECTIVES, WatchSpec.Kind.SCORE, "", "", null, search, 0);
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

    @Test void retainsCompatiblePageForExactlyGracePeriodAndFreshReplyReplacesIt() {
        var clock = new AtomicLong();
        var state = new ClientWatchEditorState(clock::get);
        state.request(1, 0, query("first"));
        var first = state.drainQueries().getFirst();
        state.accept(1, first.requestId(), page("old"));
        assertEquals("old", state.displayedPage().preview().value());

        state.request(1, 0, query("second"));
        var second = state.drainQueries().getFirst();
        assertNull(state.page());
        clock.set(PendingDisplay.GRACE_NANOS - 1);
        assertEquals("old", state.displayedPage().preview().value());
        state.accept(1, second.requestId(), page("new"));
        assertEquals("new", state.displayedPage().preview().value());
        state.request(1, 0, query("empty"));
        var empty = state.drainQueries().getFirst();
        var emptyPage = page();
        state.accept(1, empty.requestId(), emptyPage);
        assertSame(emptyPage, state.displayedPage());
    }

    @Test void repeatedPendingRequestsDoNotExtendDisplayHold() {
        var clock = new AtomicLong();
        var state = new ClientWatchEditorState(clock::get);
        state.request(1, 0, query("first"));
        var first = state.drainQueries().getFirst();
        state.accept(1, first.requestId(), page("old"));
        state.request(1, 0, query("second"));
        clock.set(100_000_000L);
        state.request(1, 0, query("third"));
        clock.set(PendingDisplay.GRACE_NANOS - 1);
        assertEquals("old", state.displayedPage().preview().value());
        clock.set(PendingDisplay.GRACE_NANOS);
        assertNull(state.displayedPage());
    }

    @Test void incompatibleCancellationAndTimeoutNeverExposeOldPage() {
        var clock = new AtomicLong();
        var state = new ClientWatchEditorState(clock::get);
        state.request(1, 0, query("first"));
        var first = state.drainQueries().getFirst();
        state.accept(1, first.requestId(), page("old"));
        var picker = new WatchEditorQuery(WatchEditorQuery.Mode.ENTITIES, WatchSpec.Kind.SCORE, "", "", null, "", 0);
        state.request(1, 0, picker);
        var pending = state.drainQueries().getFirst();
        assertNull(state.displayedPage());
        state.cancel();
        state.accept(1, pending.requestId(), page("late"));
        assertNull(state.displayedPage());

        state.request(1, 0, query());
        var timedOut = state.drainQueries().getFirst();
        clock.set(5_000_000_000L);
        assertEquals(WatchResult.Status.UNAVAILABLE, state.displayedPage().status());
        state.accept(1, timedOut.requestId(), page("late"));
        assertEquals(WatchResult.Status.UNAVAILABLE, state.page().status());
    }

    @Test void retryKeepsPriorPageOnlyForDisplayAndAllocatesNewRequestId() {
        var clock = new AtomicLong();
        var state = new ClientWatchEditorState(clock::get);
        state.request(1, 0, query());
        var first = state.drainQueries().getFirst();
        state.accept(1, first.requestId(), page("old"));
        state.retry();
        var retry = state.drainQueries().getFirst();
        assertNotEquals(first.requestId(), retry.requestId());
        assertNull(state.page());
        clock.set(PendingDisplay.GRACE_NANOS - 1);
        assertEquals("old", state.displayedPage().preview().value());
    }

    @Test void invalidateKeepsCompatibleDisplayWithoutRenewingItAndRejectsReplies() {
        var clock = new AtomicLong();
        var state = new ClientWatchEditorState(clock::get);
        state.request(1, 0, query());
        var old = state.drainQueries().getFirst();
        state.accept(1, old.requestId(), page("old"));
        state.invalidate();
        assertNull(state.page());
        clock.set(100_000_000L);
        state.request(2, 0, query());
        var current = state.drainQueries().getFirst();
        state.accept(1, old.requestId(), page("late-old"));
        clock.set(PendingDisplay.GRACE_NANOS - 1);
        assertEquals("old", state.displayedPage().preview().value());
        state.cancel();
        assertNull(state.displayedPage());
        state.accept(2, current.requestId(), page("late-current"));
        assertNull(state.page());
    }
}
