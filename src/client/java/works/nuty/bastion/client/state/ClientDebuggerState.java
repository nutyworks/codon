package works.nuty.bastion.client.state;

import org.jspecify.annotations.Nullable;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.PauseSnapshot;

import java.util.List;

/**
 * The client-side mirror of the debugger, updated purely from server sync packets. The UI, HUD,
 * and in-world renderers read from here instead of reaching into server state — which is what lets
 * Bastion work the same on a dedicated server as in singleplayer.
 *
 * <p>Fields are {@code volatile}: network handlers schedule updates onto the client thread, and the
 * render thread reads them.
 */
public final class ClientDebuggerState {
    private volatile boolean paused;
    private volatile @Nullable PauseSnapshot snapshot;
    private volatile List<BlockLocation> blockBreakpoints = List.of();

    public void applyPause(PauseSnapshot snapshot) {
        this.snapshot = snapshot;
        this.paused = true;
    }

    public void applyResume() {
        this.paused = false;
        this.snapshot = null;
    }

    public void applyBreakpoints(List<BlockLocation> blocks) {
        this.blockBreakpoints = List.copyOf(blocks);
    }

    public boolean isPaused() {
        return paused;
    }

    public @Nullable PauseSnapshot snapshot() {
        return snapshot;
    }

    public List<BlockLocation> blockBreakpoints() {
        return blockBreakpoints;
    }
}
