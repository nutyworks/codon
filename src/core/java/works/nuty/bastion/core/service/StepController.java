package works.nuty.bastion.core.service;

import works.nuty.bastion.core.model.StepMode;

/**
 * Encapsulates step-mode logic in terms of call depth. A step request is made relative to the
 * depth at which the debugger last paused:
 *
 * <ul>
 *   <li>{@code INTO} pauses at the very next command stage (any depth).</li>
 *   <li>{@code OVER} pauses at the next stage at the same depth as the pause.</li>
 *   <li>{@code OUT} pauses once execution returns to a shallower depth.</li>
 * </ul>
 *
 * Fields are {@code volatile} because step requests arrive on the command thread while pause
 * decisions are evaluated on the command-execution thread.
 */
public final class StepController {
    private volatile StepMode mode = StepMode.NONE;
    private volatile int targetDepth = -1;
    private volatile int lastPausedDepth = 0;

    public StepMode mode() {
        return mode;
    }

    public boolean isStepping() {
        return mode != StepMode.NONE;
    }

    public void stepInto() {
        mode = StepMode.INTO;
    }

    public void stepOver() {
        mode = StepMode.OVER;
        targetDepth = lastPausedDepth;
    }

    public void stepOut() {
        mode = lastPausedDepth > 0 ? StepMode.OUT : StepMode.NONE;
        targetDepth = lastPausedDepth - 1;
    }

    /** Whether the current step request wants to pause at a frame of the given depth. */
    public boolean shouldPauseAt(int depth) {
        return switch (mode) {
            case INTO -> true;
            case OVER, OUT -> depth <= targetDepth;
            case NONE -> false;
        };
    }

    /** Records that execution paused at {@code depth} and clears any active step request. */
    public void onPaused(int depth) {
        lastPausedDepth = depth;
        mode = StepMode.NONE;
    }

    /** Clears any active step request (e.g. on a plain resume). */
    public void clear() {
        mode = StepMode.NONE;
    }
}
