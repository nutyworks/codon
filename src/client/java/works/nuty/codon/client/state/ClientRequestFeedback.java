package works.nuty.codon.client.state;

import org.jspecify.annotations.Nullable;
import java.util.EnumMap;
import java.util.function.LongSupplier;

/** Independent transient notices with their original receipt-time deadlines. */
public final class ClientRequestFeedback {
    public enum Kind { CONTROL, BREAKPOINT }
    public record Notice(Kind kind, String titleKey, String messageKey) { }
    private record Entry(Notice notice, long shownAt, long sequence) { }
    private static final long DISPLAY_NANOS = 6_000_000_000L;
    private final LongSupplier clock;
    private final EnumMap<Kind, Entry> notices = new EnumMap<>(Kind.class);
    private long sequence;

    public ClientRequestFeedback(LongSupplier clock) { this.clock = clock; }

    public void show(Kind kind, String titleKey, String messageKey) {
        notices.put(kind, new Entry(new Notice(kind, titleKey, messageKey), clock.getAsLong(), ++sequence));
    }

    public @Nullable Notice current(Kind kind) {
        Entry entry = notices.get(kind);
        if (entry != null && clock.getAsLong() - entry.shownAt() >= DISPLAY_NANOS) {
            notices.remove(kind);
            return null;
        }
        return entry == null ? null : entry.notice();
    }

    /** Most recently received live notice, for callers needing a single summary. */
    public @Nullable Notice current() {
        Entry latest = null;
        for (Kind kind : Kind.values()) {
            if (current(kind) == null) continue;
            Entry entry = notices.get(kind);
            if (latest == null || entry.sequence() > latest.sequence()) latest = entry;
        }
        return latest == null ? null : latest.notice();
    }

    public void clear() { notices.clear(); }
    public void clear(Kind kind) { notices.remove(kind); }
    public void clearControl() { clear(Kind.CONTROL); }
}
