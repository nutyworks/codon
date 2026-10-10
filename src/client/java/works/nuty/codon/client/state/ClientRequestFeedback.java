package works.nuty.codon.client.state;

import org.jspecify.annotations.Nullable;

import java.util.function.LongSupplier;

/** One transient notice, independent of acknowledged execution and breakpoint state. */
public final class ClientRequestFeedback {
    public enum Kind { CONTROL, BREAKPOINT }
    public record Notice(Kind kind, String titleKey, String messageKey) { }
    private static final long DISPLAY_NANOS = 6_000_000_000L;
    private final LongSupplier clock;
    private @Nullable Notice notice;
    private long shownAt;

    public ClientRequestFeedback(LongSupplier clock) { this.clock = clock; }

    public void show(Kind kind, String titleKey, String messageKey) {
        notice = new Notice(kind, titleKey, messageKey);
        shownAt = clock.getAsLong();
    }

    public @Nullable Notice current() {
        if (notice != null && clock.getAsLong() - shownAt >= DISPLAY_NANOS) clear();
        return notice;
    }

    public void clear() { notice = null; }

    /** Execution packets acknowledge controls, not independent breakpoint edits. */
    public void clearControl() {
        if (notice != null && notice.kind() == Kind.CONTROL) clear();
    }
}
