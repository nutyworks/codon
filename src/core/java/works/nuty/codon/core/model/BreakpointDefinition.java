package works.nuty.codon.core.model;

import java.util.Objects;

/** Saved breakpoint state. Turning it off keeps its condition and exact target intact. */
public record BreakpointDefinition(BreakpointTarget target, boolean enabled, BreakpointCondition condition,
                                   boolean staleSource) {
    public BreakpointDefinition {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(condition, "condition");
        if (enabled && staleSource) throw new IllegalArgumentException("A stale breakpoint cannot be enabled");
    }

    public BreakpointDefinition(BreakpointTarget target, boolean enabled, BreakpointCondition condition) {
        this(target, enabled, condition, false);
    }

    public static BreakpointDefinition plain(BreakpointTarget target) {
        return new BreakpointDefinition(target, true, BreakpointCondition.ALWAYS);
    }

    public BreakpointDefinition withEnabled(boolean enabled) {
        return new BreakpointDefinition(target, enabled, condition, enabled ? false : staleSource);
    }

    public BreakpointDefinition withCondition(BreakpointCondition condition) {
        return new BreakpointDefinition(target, enabled, condition, staleSource);
    }

    public BreakpointDefinition withStaleSource(boolean stale) {
        return new BreakpointDefinition(target, stale ? false : enabled, condition, stale);
    }
}
