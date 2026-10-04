package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.TransferBudget;
import works.nuty.codon.core.model.WatchSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ClientWatchUploadStateTest {
    private static final WatchSpec FIRST = new WatchSpec(WatchSpec.Kind.SCORE, "first", "");
    private static final WatchSpec SECOND = new WatchSpec(WatchSpec.Kind.SCORE, "second", "");
    private static final WatchSpec THIRD = new WatchSpec(WatchSpec.Kind.SCORE, "third", "");

    @Test
    void advancesOnePagePerExactAcknowledgementAndWaitsForDurableCompletion() {
        ClientWatchUploadState state = new ClientWatchUploadState(() -> 0);
        assertEquals(new ClientWatchUploadState.Page(1, 0, false, List.of(FIRST, SECOND)),
            state.begin(1, List.of(List.of(FIRST, SECOND), List.of(THIRD))));
        assertFalse(state.finish(1, true), "a page ACK cannot impersonate the final save reply");

        assertEquals(new ClientWatchUploadState.Page(1, 2, true, List.of(THIRD)),
            state.acknowledge(1, 2).orElseThrow());
        assertTrue(state.active());
        assertTrue(state.acknowledge(1, 3).isEmpty(), "the last page has no offset ACK");
        assertTrue(state.finish(1, true));
        assertFalse(state.active());
        assertFalse(state.finish(1, true));
        assertTrue(state.acknowledge(1, 2).isEmpty());
    }

    @Test
    void unsolicitedStaleDuplicateAndOutOfOrderAcknowledgementsCannotAdvance() {
        ClientWatchUploadState state = new ClientWatchUploadState(() -> 0);
        assertTrue(state.acknowledge(1, 1).isEmpty());
        assertFalse(state.finish(1, true));
        state.begin(7, List.of(List.of(FIRST), List.of(SECOND), List.of(THIRD)));
        assertTrue(state.acknowledge(6, 1).isEmpty());
        assertTrue(state.acknowledge(8, 1).isEmpty());
        assertTrue(state.acknowledge(7, 0).isEmpty());
        assertTrue(state.acknowledge(7, -1).isEmpty());
        assertTrue(state.acknowledge(7, 2).isEmpty());
        assertTrue(state.acknowledge(7, Integer.MAX_VALUE).isEmpty());
        assertEquals(List.of(SECOND), state.acknowledge(7, 1).orElseThrow().definitions());
        for (int attempt = 0; attempt < 40; attempt++) assertTrue(state.acknowledge(7, 1).isEmpty());
        assertTrue(state.acknowledge(7, 3).isEmpty());
        assertEquals(List.of(THIRD), state.acknowledge(7, 2).orElseThrow().definitions());
        assertTrue(state.acknowledge(7, 2).isEmpty());
        assertTrue(state.finish(7, true));
    }

    @Test
    void aNewEditReplacesTheSnapshotAndCannotBeCompletedByOldReplies() {
        ClientWatchUploadState state = new ClientWatchUploadState(() -> 0);
        List<WatchSpec> mutablePage = new ArrayList<>(List.of(FIRST));
        List<List<WatchSpec>> mutablePages = new ArrayList<>(List.of(mutablePage, List.of(SECOND)));
        ClientWatchUploadState.Page first = state.begin(1, mutablePages);
        mutablePage.clear();
        mutablePages.clear();
        assertEquals(List.of(FIRST), first.definitions());
        assertThrows(UnsupportedOperationException.class, () -> first.definitions().clear());
        assertEquals(List.of(SECOND), state.acknowledge(1, 1).orElseThrow().definitions());

        assertEquals(List.of(THIRD), state.begin(2, List.of(List.of(THIRD))).definitions());
        assertTrue(state.acknowledge(1, 1).isEmpty());
        assertFalse(state.finish(1, false));
        assertFalse(state.finish(1, true));
        assertEquals(2, state.transferId());
        assertTrue(state.finish(2, true));
    }

    @Test
    void disconnectDiscardsPagesAndRejectsIdReuseAndLateReplies() {
        ClientWatchUploadState state = new ClientWatchUploadState(() -> 0);
        state.begin(1, List.of(List.of(FIRST), List.of(SECOND)));
        state.reset();
        assertFalse(state.active());
        assertEquals(0, state.transferId());
        assertEquals(0, state.expire());
        assertTrue(state.acknowledge(1, 1).isEmpty());
        assertFalse(state.finish(1, true));
        assertThrows(IllegalArgumentException.class, () -> state.begin(1, List.of(List.of(FIRST))));
        state.begin(2, List.of(List.of(SECOND), List.of(THIRD)));
        assertTrue(state.acknowledge(1, 1).isEmpty());
        assertEquals(List.of(THIRD), state.acknowledge(2, 1).orElseThrow().definitions());
    }

    @Test
    void emptySavesAndEmptyLastPagesAwaitOnlyTheDurableReply() {
        ClientWatchUploadState state = new ClientWatchUploadState(() -> 0);
        assertEquals(new ClientWatchUploadState.Page(1, 0, true, List.of()),
            state.begin(1, List.of(List.of())));
        assertTrue(state.acknowledge(1, 0).isEmpty());
        assertTrue(state.acknowledge(1, 1).isEmpty());
        assertTrue(state.finish(1, true));

        state.begin(2, List.of(List.of(FIRST), List.of()));
        assertEquals(new ClientWatchUploadState.Page(2, 1, true, List.of()),
            state.acknowledge(2, 1).orElseThrow());
        assertTrue(state.acknowledge(2, 1).isEmpty());
        assertTrue(state.finish(2, true));
    }

    @Test
    void progressDoesNotExtendTheThirtySecondDeadlineAndLateRepliesCannotReviveIt() {
        AtomicLong clock = new AtomicLong();
        ClientWatchUploadState state = new ClientWatchUploadState(clock::get);
        state.begin(1, List.of(List.of(FIRST), List.of(SECOND), List.of(THIRD)));
        clock.set(20_000_000_000L);
        assertEquals(List.of(SECOND), state.acknowledge(1, 1).orElseThrow().definitions());
        clock.set(TransferBudget.TIMEOUT_NANOS - 1);
        assertTrue(state.active());
        clock.incrementAndGet();
        assertTrue(state.acknowledge(1, 2).isEmpty());
        assertFalse(state.finish(1, true));
        assertFalse(state.active());
        assertEquals(1, state.expire(), "ACK-triggered expiry still notifies the client tick");
        assertEquals(0, state.expire(), "a timeout has no retry or repeated notification");
        assertTrue(state.acknowledge(1, 2).isEmpty());
    }

    @Test
    void finalPageAlsoTimesOutAndServerFailureTerminatesWithoutRetry() {
        AtomicLong clock = new AtomicLong();
        ClientWatchUploadState state = new ClientWatchUploadState(clock::get);
        state.begin(1, List.of(List.of(FIRST)));
        clock.set(TransferBudget.TIMEOUT_NANOS);
        assertFalse(state.finish(1, true));
        assertEquals(1, state.expire());
        state.begin(2, List.of(List.of(FIRST), List.of(SECOND)));
        assertTrue(state.finish(2, false));
        assertTrue(state.acknowledge(2, 1).isEmpty());
        assertFalse(state.active());
        clock.addAndGet(TransferBudget.TIMEOUT_NANOS);
        assertEquals(0, state.expire());
    }

    @Test
    void supportsMaximumDefinitionsAndPagesWithoutBurstingMoreThanOnePagePerAck() {
        ClientWatchUploadState state = new ClientWatchUploadState(() -> 0);
        List<WatchSpec> definitions = IntStream.range(0, 8192)
            .mapToObj(index -> new WatchSpec(WatchSpec.Kind.SCORE, "watch-" + index, "")).toList();
        List<List<WatchSpec>> pages = IntStream.range(0, 1024)
            .mapToObj(index -> definitions.subList(index * 8, (index + 1) * 8)).toList();
        List<WatchSpec> emitted = new ArrayList<>();
        ClientWatchUploadState.Page page = state.begin(1, pages);
        int pagesSent = 0;
        while (true) {
            assertEquals(emitted.size(), page.offset());
            emitted.addAll(page.definitions());
            pagesSent++;
            if (page.last()) break;
            page = state.acknowledge(1, emitted.size()).orElseThrow();
        }
        assertEquals(1024, pagesSent);
        assertEquals(definitions, emitted);
        assertTrue(state.finish(1, true));
    }

    @Test
    void rejectsUnboundedOrMalformedSnapshotsBeforeReplacingAnActiveUpload() {
        ClientWatchUploadState state = new ClientWatchUploadState(() -> 0);
        state.begin(1, List.of(List.of(FIRST), List.of(SECOND)));
        assertThrows(IllegalArgumentException.class, () -> state.begin(2, List.of()));
        assertThrows(IllegalArgumentException.class, () -> state.begin(2, List.of(List.of(), List.of(SECOND))));
        assertThrows(IllegalArgumentException.class, () -> state.begin(2, List.of(List.of(FIRST), List.of(FIRST))));
        assertThrows(IllegalArgumentException.class, () -> state.begin(2, java.util.Collections.nCopies(1025, List.of(FIRST))));
        assertThrows(IllegalArgumentException.class, () -> state.begin(2,
            List.of(java.util.Collections.nCopies(8193, FIRST))));
        assertEquals(1, state.transferId());
        assertEquals(List.of(SECOND), state.acknowledge(1, 1).orElseThrow().definitions());
    }
}
