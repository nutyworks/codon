package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.TransferBudget;
import works.nuty.codon.core.model.WatchSpec;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ClientWatchSaveDeadlineTest {
    private static final WatchSpec FIRST = new WatchSpec(WatchSpec.Kind.SCORE, "first", "");
    private static final WatchSpec SECOND = new WatchSpec(WatchSpec.Kind.SCORE, "second", "");

    @Test
    void validationDelayCannotLeavePageProgressAfterUiTimeout() {
        AtomicLong clock = new AtomicLong();
        ClientWatchState watches = new ClientWatchState(clock::get);
        ClientWatchUploadState upload = new ClientWatchUploadState(clock::get);
        long startedAt = clock.get();
        watches.saveStarted(1, TransferBudget.TIMEOUT_NANOS, startedAt);
        clock.set(7_909_155L); // Measured validation/copy gap in the native diagnosis.
        upload.begin(1, List.of(List.of(FIRST), List.of(SECOND)), startedAt);

        clock.set(TransferBudget.TIMEOUT_NANOS);
        assertEquals(ClientWatchState.SaveStatus.FAILED, watches.saveStatus());
        assertTrue(upload.acknowledge(1, 1).isEmpty(), "UI FAILED must leave no later page progress");
        assertFalse(upload.finish(1, true));
        assertEquals(1, upload.expire());
        assertEquals(0, upload.expire());
    }

    @Test
    void snapshotCopyTimeCountsTowardTheSameDeadline() {
        AtomicLong clock = new AtomicLong();
        ClientWatchState watches = new ClientWatchState(clock::get);
        ClientWatchUploadState upload = new ClientWatchUploadState(clock::get);
        long startedAt = clock.get();
        watches.saveStarted(1, TransferBudget.TIMEOUT_NANOS, startedAt);
        List<List<WatchSpec>> delayedPages = new java.util.AbstractList<>() {
            @Override public List<WatchSpec> get(int index) {
                clock.set(TransferBudget.TIMEOUT_NANOS);
                return index == 0 ? List.of(FIRST) : List.of(SECOND);
            }
            @Override public int size() { return 2; }
        };
        upload.begin(1, delayedPages, startedAt);
        assertEquals(ClientWatchState.SaveStatus.FAILED, watches.saveStatus());
        assertFalse(upload.active(), "copying must not start a fresh timeout window");
        assertTrue(upload.acknowledge(1, 1).isEmpty());
        assertEquals(1, upload.expire());
    }

    @Test
    void pageProgressBeforeTheBoundaryDoesNotExtendTheSharedDeadline() {
        AtomicLong clock = new AtomicLong(100);
        ClientWatchState watches = new ClientWatchState(clock::get);
        ClientWatchUploadState upload = new ClientWatchUploadState(clock::get);
        long startedAt = clock.get();
        watches.saveStarted(1, TransferBudget.TIMEOUT_NANOS, startedAt);
        clock.addAndGet(10_000_000L);
        upload.begin(1, List.of(List.of(FIRST), List.of(SECOND), List.of()), startedAt);
        clock.set(startedAt + TransferBudget.TIMEOUT_NANOS - 1);
        assertEquals(ClientWatchState.SaveStatus.SAVING, watches.saveStatus());
        assertEquals(List.of(SECOND), upload.acknowledge(1, 1).orElseThrow().definitions());
        clock.incrementAndGet();
        assertEquals(ClientWatchState.SaveStatus.FAILED, watches.saveStatus());
        assertTrue(upload.acknowledge(1, 2).isEmpty());
        assertFalse(upload.finish(1, true));
        watches.saveFinished(1, true);
        assertEquals(ClientWatchState.SaveStatus.FAILED, watches.saveStatus());
    }

    @Test
    void v2AndLegacyFinalRepliesSucceedBeforeTheBoundaryAndFailAtIt() {
        AtomicLong clock = new AtomicLong();
        ClientWatchState watches = new ClientWatchState(clock::get);
        ClientWatchUploadState upload = new ClientWatchUploadState(clock::get);
        watches.saveStarted(1, TransferBudget.TIMEOUT_NANOS, clock.get());
        clock.set(10_000_000L);
        upload.begin(1, List.of(List.of(FIRST)), 0);
        clock.set(TransferBudget.TIMEOUT_NANOS - 1);
        assertTrue(upload.finish(1, true));
        watches.saveFinished(1, true);
        assertEquals(ClientWatchState.SaveStatus.SAVED, watches.saveStatus());

        long startedAt = clock.get();
        watches.saveStarted(2, TransferBudget.TIMEOUT_NANOS, startedAt);
        upload.begin(2, List.of(List.of(FIRST)), startedAt);
        clock.set(startedAt + TransferBudget.TIMEOUT_NANOS);
        assertFalse(upload.finish(2, true));
        watches.saveFinished(2, true);
        assertEquals(ClientWatchState.SaveStatus.FAILED, watches.saveStatus());

        startedAt = clock.get();
        watches.saveStarted(3, TransferBudget.TIMEOUT_NANOS, startedAt); // Legacy has only a final reply.
        clock.set(startedAt + TransferBudget.TIMEOUT_NANOS - 1);
        watches.saveFinished(3, true);
        assertEquals(ClientWatchState.SaveStatus.SAVED, watches.saveStatus());
        startedAt = clock.get();
        watches.saveStarted(4, TransferBudget.TIMEOUT_NANOS, startedAt);
        clock.set(startedAt + TransferBudget.TIMEOUT_NANOS);
        watches.saveFinished(4, true);
        assertEquals(ClientWatchState.SaveStatus.FAILED, watches.saveStatus());
    }

    @Test
    void replacementAndDisconnectDiscardOldRepliesWithoutChangingTheNewDeadline() {
        AtomicLong clock = new AtomicLong();
        ClientWatchState watches = new ClientWatchState(clock::get);
        ClientWatchUploadState upload = new ClientWatchUploadState(clock::get);
        watches.saveStarted(1, TransferBudget.TIMEOUT_NANOS, clock.get());
        upload.begin(1, List.of(List.of(FIRST), List.of(SECOND)), clock.get());
        clock.set(TransferBudget.TIMEOUT_NANOS);
        assertTrue(upload.acknowledge(1, 1).isEmpty()); // Expiry notification is still pending.
        long replacementStartedAt = clock.get();
        watches.saveStarted(2, TransferBudget.TIMEOUT_NANOS, replacementStartedAt);
        upload.begin(2, List.of(List.of(SECOND)), replacementStartedAt);
        assertEquals(0, upload.expire(), "replacement discards the old timeout notification");
        assertTrue(upload.acknowledge(1, 1).isEmpty());
        assertFalse(upload.finish(1, true));
        watches.saveFinished(1, true);
        assertEquals(ClientWatchState.SaveStatus.SAVING, watches.saveStatus());

        upload.reset();
        watches.endConnection();
        watches.reset();
        assertTrue(upload.acknowledge(2, 1).isEmpty());
        assertFalse(upload.finish(2, true));
        watches.saveFinished(2, true);
        assertEquals(ClientWatchState.SaveStatus.IDLE, watches.saveStatus());
        assertEquals(0, upload.expire());
        assertThrows(IllegalArgumentException.class, () -> upload.begin(2, List.of(List.of(FIRST)), clock.get()));
        long reconnectedAt = clock.get();
        watches.saveStarted(3, TransferBudget.TIMEOUT_NANOS, reconnectedAt);
        clock.addAndGet(10_000_000L);
        upload.begin(3, List.of(List.of(FIRST), List.of(SECOND)), reconnectedAt);
        assertTrue(upload.acknowledge(2, 1).isEmpty());
        clock.set(reconnectedAt + TransferBudget.TIMEOUT_NANOS);
        assertEquals(ClientWatchState.SaveStatus.FAILED, watches.saveStatus());
        assertTrue(upload.acknowledge(3, 1).isEmpty());
    }
}
