package works.nuty.codon.core.model;

import java.util.Objects;

/** A fixed, inspectable condition over one recorded command stage. */
public record BreakpointCondition(Kind kind, Comparison comparison, int threshold) {
    public enum Kind {
        ALWAYS,
        CREATED, REMOVED, CHANGED,
        INPUT_COUNT, OUTPUT_COUNT, CREATED_COUNT, REMOVED_COUNT, CHANGED_COUNT;

        public boolean isEvent() {
            return this == CREATED || this == REMOVED || this == CHANGED;
        }

        public boolean isCount() {
            return this != ALWAYS && !isEvent();
        }
    }

    public enum Comparison {
        EQ, NE, LT, LE, GT, GE;

        public boolean test(int actual, int expected) {
            return switch (this) {
                case EQ -> actual == expected;
                case NE -> actual != expected;
                case LT -> actual < expected;
                case LE -> actual <= expected;
                case GT -> actual > expected;
                case GE -> actual >= expected;
            };
        }
    }

    public static final BreakpointCondition ALWAYS = new BreakpointCondition(Kind.ALWAYS, Comparison.EQ, 0);

    public BreakpointCondition {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(comparison, "comparison");
        if (threshold < 0) throw new IllegalArgumentException("Breakpoint count must be nonnegative");
        if (!kind.isCount() && (comparison != Comparison.EQ || threshold != 0)) {
            throw new IllegalArgumentException("Always and event conditions have no comparison or threshold");
        }
    }

    public static BreakpointCondition event(Kind kind) {
        if (!kind.isEvent()) throw new IllegalArgumentException("Not an event condition");
        return new BreakpointCondition(kind, Comparison.EQ, 0);
    }

    public static BreakpointCondition count(Kind kind, Comparison comparison, int threshold) {
        if (!kind.isCount()) throw new IllegalArgumentException("Not a count condition");
        return new BreakpointCondition(kind, comparison, threshold);
    }

    public boolean isResultCondition() {
        return kind != Kind.ALWAYS;
    }
}
