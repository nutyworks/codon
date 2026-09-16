package works.nuty.bastion.client.state;

/** Restores channels after a debugger pause without overriding the vanilla pause state. */
public interface DebuggerSoundControl {
    /**
     * @param vanillaPaused whether Minecraft remains paused after the debugger resumes
     */
    void bastion$resumeAfterDebuggerPause(boolean vanillaPaused);
}
