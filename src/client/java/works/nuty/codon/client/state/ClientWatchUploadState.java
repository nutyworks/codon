package works.nuty.codon.client.state;

import works.nuty.codon.core.model.TransferBudget;
import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.core.model.WatchIdentity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/** Client-thread stop-and-wait upload; send each returned page at most once while its transfer is active. */
public final class ClientWatchUploadState {
    public record Page(long transferId, int offset, boolean last, List<WatchSpec> definitions) {
        public Page { definitions = List.copyOf(definitions); }
    }

    private final LongSupplier clock;
    private long lastTransferId;
    private long transferId;
    private long startedAt;
    private long timedOutTransferId;
    private List<List<WatchSpec>> pages = List.of();
    private int pageIndex;
    private int offset;

    public ClientWatchUploadState(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Replaces the previous upload with an immutable snapshot, without retaining an edit queue.
     * The caller supplies wire-validated pages and strictly increasing IDs, including after reset.
     */
    public Page begin(long id, List<List<WatchSpec>> sourcePages) {
        return begin(id, sourcePages, clock.getAsLong());
    }

    /** Uses the UI save's start instant so page validation/copying cannot extend its deadline. */
    public Page begin(long id, List<List<WatchSpec>> sourcePages, long saveStartedAt) {
        if (id <= lastTransferId) throw new IllegalArgumentException("watch transfer ID must increase");
        if (sourcePages.isEmpty() || sourcePages.size() > TransferBudget.MAX_PAGES) {
            throw new IllegalArgumentException("invalid watch upload page count");
        }
        List<List<WatchSpec>> snapshot = new ArrayList<>(sourcePages.size());
        Set<WatchIdentity.Key> unique = new HashSet<>();
        int entries = 0;
        for (int index = 0; index < sourcePages.size(); index++) {
            List<WatchSpec> source = sourcePages.get(index);
            if ((source.isEmpty() && index != sourcePages.size() - 1)
                || source.size() > TransferBudget.WATCH_DEFINITIONS.entries() - entries) {
                throw new IllegalArgumentException("invalid watch upload page");
            }
            List<WatchSpec> page = List.copyOf(source);
            for (WatchSpec spec : page) {
                if (!unique.add(WatchIdentity.rawKey(spec))) throw new IllegalArgumentException("duplicate watch definition");
            }
            entries += page.size();
            snapshot.add(page);
        }
        pages = List.copyOf(snapshot);
        transferId = lastTransferId = id;
        startedAt = saveStartedAt;
        timedOutTransferId = 0;
        pageIndex = offset = 0;
        return currentPage();
    }

    /** An unsolicited, duplicate, stale, final-page or noncontiguous ACK never releases a page. */
    public Optional<Page> acknowledge(long id, int nextOffset) {
        expireIfNeeded();
        if (transferId == 0 || id != transferId || pageIndex == pages.size() - 1
            || nextOffset <= 0 || nextOffset != offset + pages.get(pageIndex).size()) {
            return Optional.empty();
        }
        offset = nextOffset;
        pageIndex++;
        return Optional.of(currentPage());
    }

    /** Only the final durable-save reply can complete successfully; any current page may fail. */
    public boolean finish(long id, boolean saved) {
        expireIfNeeded();
        if (transferId == 0 || id != transferId || (saved && pageIndex != pages.size() - 1)) return false;
        clearActive();
        return true;
    }

    /** Returns one timeout notification, even when an earlier ACK or status check noticed expiry. */
    public long expire() {
        expireIfNeeded();
        long expired = timedOutTransferId;
        timedOutTransferId = 0;
        return expired;
    }

    public boolean active() { expireIfNeeded(); return transferId != 0; }
    public long transferId() { expireIfNeeded(); return transferId; }

    /** Disconnect cancels retained pages and timeout notifications, but never reuses transfer IDs. */
    public void reset() {
        clearActive();
        timedOutTransferId = 0;
    }

    private Page currentPage() {
        return new Page(transferId, offset, pageIndex == pages.size() - 1, pages.get(pageIndex));
    }

    private void expireIfNeeded() {
        if (transferId != 0 && clock.getAsLong() - startedAt >= TransferBudget.TIMEOUT_NANOS) {
            timedOutTransferId = transferId;
            clearActive();
        }
    }

    private void clearActive() {
        pages = List.of();
        transferId = startedAt = 0;
        pageIndex = offset = 0;
    }
}
