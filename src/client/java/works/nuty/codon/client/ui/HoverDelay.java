package works.nuty.codon.client.ui;

import java.util.HashMap;
import java.util.Map;

/**
 * Hover delay for tooltips that a panel draws itself, matching the delay {@link DebuggerButton}
 * applies to its own. Callers pass a key that is stable across frames for one region and ask
 * only while the pointer is over it; a region that goes unasked for longer than a frame gap
 * has been left, so its timer restarts. Keyboard focus is not delayed and bypasses this.
 */
final class HoverDelay {
    static final long DELAY_NANOS = 350_000_000L;
    /** Frames are asked far more often than this while a region stays hovered. */
    static final long FRAME_GAP_NANOS = 250_000_000L;

    private static final HoverDelay SHARED = new HoverDelay();

    private record Hover(long startedAt, long lastAskedAt) { }

    private final Map<Object, Hover> hovers = new HashMap<>();

    /** True once the pointer has rested on {@code region} for the tooltip delay. */
    static boolean elapsed(Object region) { return SHARED.elapsed(region, System.nanoTime()); }

    boolean elapsed(Object region, long now) {
        hovers.values().removeIf(hover -> now - hover.lastAskedAt() > FRAME_GAP_NANOS);
        long startedAt = hovers.containsKey(region) ? hovers.get(region).startedAt() : now;
        hovers.put(region, new Hover(startedAt, now));
        return now - startedAt >= DELAY_NANOS;
    }
}
