package works.nuty.codon.client.state;

import org.jspecify.annotations.Nullable;

import java.util.function.LongSupplier;

/** One transient notice, independent of acknowledged execution and breakpoint state. */
public final class ClientRequestFeedback {
    public record Notice(String titleKey, String messageKey) { }
    private static final long DISPLAY_NANOS = 6_000_000_000L;
    private final LongSupplier clock;
    private @Nullable Notice notice;
    private long shownAt;

    public ClientRequestFeedback(LongSupplier clock) { this.clock = clock; }

    public void show(String titleKey, String messageKey) {
        notice = new Notice(titleKey, messageKey);
        shownAt = clock.getAsLong();
    }

    public @Nullable Notice current() {
        if (notice != null && clock.getAsLong() - shownAt >= DISPLAY_NANOS) clear();
        return notice;
    }

    public void clear() { notice = null; }
}
