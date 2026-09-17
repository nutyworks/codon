package works.nuty.bastion.core.model;

/** Why the debugger paused, including inspection after the final command of a step. */
public enum PauseReason {
    BREAKPOINT,
    STEP,
    EXECUTION_COMPLETE
}
