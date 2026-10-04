package works.nuty.codon.core.model;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Aggregate receive limits, independent of offsets, duplicate detection and the sender's last flag. */
public final class TransferBudget {
    public static final long TIMEOUT_NANOS = 30_000_000_000L;
    public static final int MAX_PAGES = 1024;
    public static final Limits WATCH_DEFINITIONS = new Limits(8192, 2_097_152);
    public static final Limits WATCH_CHANGES = new Limits(4096, 2_097_152);
    public static final Limits FUNCTION_LIST = new Limits(32_768, 4_194_304);
    // These match the source repository's existing complete-document limits.
    public static final Limits FUNCTION_SOURCE = new Limits(20_000, 700_000);

    /** Characters are UTF-16 code units, as in String.length() and the existing wire contracts. */
    public record Limits(int entries, long characters) { }

    private final Limits limits;
    private final LongSupplier clock;
    private int entries;
    private long characters;
    private int pages;
    private long startedAt;

    public TransferBudget(Limits limits, LongSupplier clock) {
        this.limits = Objects.requireNonNull(limits);
        this.clock = Objects.requireNonNull(clock);
    }

    public boolean accept(int count, long textLength, boolean last) {
        if (count < 0 || textLength < 0 || (!last && count == 0) || expired()
            || pages >= MAX_PAGES || count > limits.entries() - entries
            || textLength > limits.characters() - characters) return false;
        if (pages == 0) startedAt = clock.getAsLong();
        pages++;
        entries += count;
        characters += textLength;
        return true;
    }

    public boolean expired() { return pages > 0 && clock.getAsLong() - startedAt >= TIMEOUT_NANOS; }

    public void reset() { entries = pages = 0; characters = startedAt = 0; }

    public static long characters(WatchSpec spec) {
        return (long) spec.target().length() + spec.path().length()
            + (spec.scoreHolder() == null ? 0 : spec.scoreHolder().length());
    }

    public static long characters(WatchResult result) {
        return (long) result.value().length() + result.targetKey().length() + result.targetName().length();
    }
}
