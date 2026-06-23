package works.nuty.bastion.core.model;

/**
 * How execution should advance after the debugger resumes from a pause.
 *
 * <ul>
 *   <li>{@link #NONE} — run until the next breakpoint.</li>
 *   <li>{@link #INTO} — pause at the very next command stage, descending into called functions.</li>
 *   <li>{@link #OVER} — pause at the next stage at the same call depth, skipping over called functions.</li>
 *   <li>{@link #OUT} — pause once execution returns to a shallower call depth.</li>
 * </ul>
 */
public enum StepMode {
    NONE,
    INTO,
    OVER,
    OUT
}
