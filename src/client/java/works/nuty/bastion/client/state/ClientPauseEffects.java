package works.nuty.bastion.client.state;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import works.nuty.bastion.mixin.client.SoundManagerAccessor;

/** Keeps game presentation paused while client input, rendering, and networking continue. */
public final class ClientPauseEffects {
    private final ClientDebuggerState state;
    private final PausedClock clock = new PausedClock();
    private volatile boolean paused;

    public ClientPauseEffects(ClientDebuggerState state) {
        this.state = state;
    }

    public void synchronize(Minecraft client) {
        boolean nextPaused = state.isPaused() && client.level != null;
        clock.setPaused(nextPaused, Util.getMillis());
        // Freeze interpolation immediately, including a pause packet received between frames.
        ((DeltaTracker.Timer) client.getDeltaTracker()).updatePauseState(client.isPaused());
        if (nextPaused != paused) {
            paused = nextPaused;
            if (paused) {
                client.getSoundManager().pauseAllExcept();
            } else {
                if (client.level != null && client.player != null) {
                    client.gameRenderer.update(client.getDeltaTracker());
                    client.getSoundManager().updateSource(client.gameRenderer.mainCamera());
                }
                // A debugger resume must not override an ordinary singleplayer pause menu.
                var engine = ((SoundManagerAccessor) client.getSoundManager()).bastion$soundEngine();
                ((DebuggerSoundControl) engine).bastion$resumeAfterDebuggerPause(client.isPaused());
            }
        }
    }

    public boolean isPaused() { return paused; }

    public long effectTimeMillis(long realTimeMillis) {
        return clock.timeMillis(realTimeMillis);
    }
}
