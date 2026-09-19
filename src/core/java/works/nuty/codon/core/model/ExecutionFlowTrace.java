package works.nuty.codon.core.model;

import java.util.List;

/** One command invocation and its independently identified execute-stage history. */
public record ExecutionFlowTrace(
    long invocationId,
    SourceLocation location,
    List<ExecutionFlowStage> stages,
    boolean truncated
) {
    public ExecutionFlowTrace {
        stages = List.copyOf(stages);
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
