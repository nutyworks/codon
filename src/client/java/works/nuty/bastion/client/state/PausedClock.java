package works.nuty.bastion.client.state;

/** A presentation clock which excludes paused time, including across repeated pauses. */
public final class PausedClock {
    private volatile State state = new State(false, 0, 0);

    /** Called by the client thread; readers may include the renderer and sound thread. */
    public void setPaused(boolean paused, long nowMillis) {
        State previous = state;
        if (paused == previous.paused()) return;
        state = paused
            ? new State(true, nowMillis, previous.excludedMillis())
            : new State(false, 0, previous.excludedMillis() + nowMillis - previous.pausedAtMillis());
    }

    public long timeMillis(long nowMillis) {
        State current = state;
        return (current.paused() ? current.pausedAtMillis() : nowMillis) - current.excludedMillis();
    }

    private record State(boolean paused, long pausedAtMillis, long excludedMillis) { }
}
