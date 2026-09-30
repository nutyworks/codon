package works.nuty.codon.client.input;

/** Session-only UI visibility. Native press/release times are independent of game ticks. */
public final class UiHideGesture {
    public static final long HOLD_NANOS = 250_000_000L;
    private boolean hidden;
    private boolean pressed;
    private boolean awaitingRelease;
    private long pressedAt;

    public void press(long now) {
        if (pressed || awaitingRelease) return;
        pressed = true;
        pressedAt = now;
    }

    public void release(long now) {
        if (awaitingRelease) {
            awaitingRelease = false;
            return;
        }
        if (!pressed) return;
        pressed = false;
        if (now - pressedAt < HOLD_NANOS) hidden = !hidden;
    }

    public boolean isHidden() { return hidden || pressed; }
    public boolean awaitingRelease() { return awaitingRelease; }

    /** A lost release must never turn a cancelled gesture into a toggle. */
    public void reset() {
        awaitingRelease |= pressed;
        hidden = false;
        pressed = false;
    }
}
