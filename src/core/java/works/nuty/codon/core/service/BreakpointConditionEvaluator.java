package works.nuty.codon.core.service;

import works.nuty.codon.core.model.BreakpointCondition;
import works.nuty.codon.core.model.ExecutionFlowStage;

/** Evaluates only data actually observed for a finalized Brigadier modifier stage. */
public final class BreakpointConditionEvaluator {
    public enum Result { MATCH, NO_MATCH, UNMEASURED, UNSUPPORTED }

    private BreakpointConditionEvaluator() { }

    public static Result evaluate(BreakpointCondition condition, ExecutionFlowStage stage) {
        if (condition.kind() == BreakpointCondition.Kind.ALWAYS) return Result.UNSUPPORTED;
        if (stage.terminal()) return Result.UNSUPPORTED;
        int actual = observedCount(condition.kind(), stage);
        if (actual < 0) return Result.UNMEASURED;
        boolean match = condition.kind().isEvent() ? actual > 0
            : condition.comparison().test(actual, condition.threshold());
        return match ? Result.MATCH : Result.NO_MATCH;
    }

    /** Returns the recorded count for a result condition, or UNMEASURED when it was not observed. */
    public static int observedCount(BreakpointCondition.Kind kind, ExecutionFlowStage stage) {
        if (kind == BreakpointCondition.Kind.ALWAYS || stage.terminal()
            || !stage.complete() || !stage.lineageComplete()) return ExecutionFlowStage.UNMEASURED;
        return switch (kind) {
            case ALWAYS -> throw new AssertionError();
            case INPUT_COUNT -> stage.inputCount();
            case OUTPUT_COUNT -> stage.outputCount();
            case REMOVED, REMOVED_COUNT -> stage.droppedCount();
            case CREATED, CREATED_COUNT -> createdCount(stage);
            case CHANGED, CHANGED_COUNT -> changedCount(stage);
        };
    }

    private static int createdCount(ExecutionFlowStage stage) {
        if (!hasCompleteDetail(stage)) return ExecutionFlowStage.UNMEASURED;
        return (int) stage.outputs().stream().filter(output -> stage.isBranchedContext(output.id())).count();
    }

    private static int changedCount(ExecutionFlowStage stage) {
        if (!hasCompleteDetail(stage)) return ExecutionFlowStage.UNMEASURED;
        return (int) stage.outputs().stream().filter(output -> stage.isChangedContext(output.id())).count();
    }

    private static boolean hasCompleteDetail(ExecutionFlowStage stage) {
        return !stage.truncated() && stage.inputCount() == stage.inputs().size()
            && stage.outputCount() == stage.outputs().size() && stage.edges().size() == stage.outputCount();
    }
}
