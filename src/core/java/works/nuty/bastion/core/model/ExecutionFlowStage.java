package works.nuty.bastion.core.model;

import java.util.List;
import java.util.Set;

/** A bounded, immutable view of one modifier or final-command stage. */
public record ExecutionFlowStage(
    int index,
    CommandSnippet command,
    List<ExecutionFlowContext> inputs,
    List<ExecutionFlowContext> outputs,
    List<ExecutionFlowEdge> edges,
    List<Long> droppedContextIds,
    int inputCount,
    int outputCount,
    int droppedCount,
    boolean terminal,
    int executionCount,
    int successCount,
    boolean complete,
    boolean lineageComplete,
    boolean truncated
) {
    public ExecutionFlowStage {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        edges = List.copyOf(edges);
        droppedContextIds = List.copyOf(droppedContextIds);
    }

    /**
     * Contexts useful to the existing inspector and world markers: produced contexts followed by
     * recorded inputs which produced no output. The latter are the flows excluded by this stage.
     */
    public List<ExecutionFlowContext> displayContexts() {
        if (terminal || !complete || !lineageComplete) return inputs;
        Set<Long> dropped = Set.copyOf(droppedContextIds);
        var result = new java.util.ArrayList<ExecutionFlowContext>(outputs);
        for (ExecutionFlowContext input : inputs) {
            if (dropped.contains(input.id())) result.add(input);
        }
        return List.copyOf(result);
    }

    public boolean isDroppedContext(long contextId) {
        return !terminal && complete && lineageComplete && droppedContextIds.contains(contextId);
    }
}
