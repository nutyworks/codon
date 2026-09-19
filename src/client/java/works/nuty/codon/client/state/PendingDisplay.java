package works.nuty.codon.client.state;

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.LongSupplier;

/** A short presentation-only hold while an authoritative replacement is pending. */
public final class PendingDisplay<T> {
    public static final long GRACE_NANOS = 250_000_000L;

    private final LongSupplier clock;
    private @Nullable T previous;
    private long startedAt;

    public PendingDisplay(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }

    /** Supply only a current observation; repeated invalidations without a reply must not renew it. */
    public void retain(@Nullable T current) {
        if (current == null) return;
        previous = current;
        startedAt = clock.getAsLong();
    }

    public @Nullable T resolve(@Nullable T current) {
        if (current != null) {
            clear();
            return current;
        }
        if (previous != null && clock.getAsLong() - startedAt >= GRACE_NANOS) clear();
        return previous;
    }

    public void clear() { previous = null; }
}
