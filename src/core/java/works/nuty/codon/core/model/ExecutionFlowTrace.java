package works.nuty.codon.core.model;

import java.util.List;

/** One command invocation and its independently identified execute-stage history. */
public record ExecutionFlowTrace(
    long invocationId,
    SourceLocation location,
    List<ExecutionFlowStage> stages,
    boolean truncated,
    List<ExecutionFlowWarning> warnings
) {
    public ExecutionFlowTrace {
        stages = List.copyOf(stages);
        warnings = List.copyOf(warnings);
    }

    /** Compatibility constructor for traces recorded before diagnostic warnings existed. */
    public ExecutionFlowTrace(long invocationId, SourceLocation location, List<ExecutionFlowStage> stages, boolean truncated) {
        this(invocationId, location, stages, truncated, List.of());
    }

    public int finalContextCount() {
        return stages.isEmpty() ? ExecutionFlowStage.UNMEASURED : stages.getLast().outputCount();
    }

    public int executionCount() {
        return stages.isEmpty() ? ExecutionFlowStage.UNMEASURED : stages.getLast().executionCount();
    }

    public int successCount() {
        return stages.isEmpty() ? ExecutionFlowStage.UNMEASURED : stages.getLast().successCount();
    }
}
