package works.nuty.codon.core.model;

import java.util.Objects;

/**
 * An unsaved condition as the inline editor presents it: a mode (Always, Event or Count),
 * the event or metric for that mode, a comparison and the threshold text the user typed.
 * Switching modes keeps the choices of the other modes, so switching back restores them.
 */
public record BreakpointConditionDraft(Mode mode, BreakpointCondition.Kind event, BreakpointCondition.Kind metric,
                                       BreakpointCondition.Comparison comparison, String threshold) {
    public enum Mode { ALWAYS, EVENT, COUNT }

    public static final int MAX_THRESHOLD_LENGTH = 9;

    public BreakpointConditionDraft {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(comparison, "comparison");
        Objects.requireNonNull(threshold, "threshold");
        if (!event.isEvent()) throw new IllegalArgumentException("Not an event: " + event);
        if (!metric.isCount()) throw new IllegalArgumentException("Not a count metric: " + metric);
    }

    public static BreakpointConditionDraft of(BreakpointCondition condition) {
        var kind = condition.kind();
        Mode mode = kind == BreakpointCondition.Kind.ALWAYS ? Mode.ALWAYS : kind.isEvent() ? Mode.EVENT : Mode.COUNT;
        return new BreakpointConditionDraft(mode,
            kind.isEvent() ? kind : BreakpointCondition.Kind.CREATED,
            kind.isCount() ? kind : BreakpointCondition.Kind.OUTPUT_COUNT,
            kind.isCount() ? condition.comparison() : BreakpointCondition.Comparison.EQ,
            Integer.toString(kind.isCount() ? condition.threshold() : 0));
    }

    public BreakpointConditionDraft withMode(Mode value) {
        return new BreakpointConditionDraft(value, event, metric, comparison, threshold);
    }

    public BreakpointConditionDraft withEvent(BreakpointCondition.Kind value) {
        return new BreakpointConditionDraft(Mode.EVENT, value, metric, comparison, threshold);
    }

    public BreakpointConditionDraft withMetric(BreakpointCondition.Kind value) {
        return new BreakpointConditionDraft(Mode.COUNT, event, value, comparison, threshold);
    }

    public BreakpointConditionDraft withComparison(BreakpointCondition.Comparison value) {
        return new BreakpointConditionDraft(Mode.COUNT, event, metric, value, threshold);
    }

    public BreakpointConditionDraft withThreshold(String value) {
        return new BreakpointConditionDraft(mode, event, metric, comparison, value);
    }

    /** Adds {@code delta} to a valid threshold, stopping at zero; an invalid number restarts from zero. */
    public BreakpointConditionDraft step(int delta) {
        int current = thresholdValue();
        long next = Math.max(0L, (current < 0 ? 0L : current) + (long) delta);
        String text = Long.toString(Math.min(next, maxThreshold()));
        return new BreakpointConditionDraft(mode, event, metric, comparison, text);
    }

    public BreakpointCondition.Kind kind() {
        return switch (mode) {
            case ALWAYS -> BreakpointCondition.Kind.ALWAYS;
            case EVENT -> event;
            case COUNT -> metric;
        };
    }

    /** A whole number of at most {@value #MAX_THRESHOLD_LENGTH} digits, or -1 when the text is not one. */
    public int thresholdValue() {
        if (threshold.isEmpty() || threshold.length() > MAX_THRESHOLD_LENGTH) return -1;
        for (int i = 0; i < threshold.length(); i++) if (threshold.charAt(i) < '0' || threshold.charAt(i) > '9') return -1;
        return Integer.parseInt(threshold);
    }

    /** Only the Count mode reads the number, so other modes are always valid. */
    public boolean valid() {
        return mode != Mode.COUNT || thresholdValue() >= 0;
    }

    public BreakpointCondition condition() {
        if (!valid()) throw new IllegalStateException("Invalid count: " + threshold);
        return switch (mode) {
            case ALWAYS -> BreakpointCondition.ALWAYS;
            case EVENT -> BreakpointCondition.event(event);
            case COUNT -> BreakpointCondition.count(metric, comparison, thresholdValue());
        };
    }

    /** True when saving would store a condition other than {@code saved}; an invalid number is a change. */
    public boolean changedFrom(BreakpointCondition saved) {
        return !valid() || !condition().equals(saved);
    }

    /** Always stops before the target runs; Event and Count read its recorded result, so they stop after. */
    public boolean stopsAfter() {
        return mode != Mode.ALWAYS;
    }

    private static int maxThreshold() {
        return 999_999_999;
    }
}
