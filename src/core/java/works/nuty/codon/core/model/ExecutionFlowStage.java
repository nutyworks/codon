package works.nuty.codon.core.model;

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
    boolean truncated,
    long observationOrder,
    List<CallFrame> callStack
) {
    public ExecutionFlowStage {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        edges = List.copyOf(edges);
        droppedContextIds = List.copyOf(droppedContextIds);
        callStack = List.copyOf(callStack);
    }

    /** Compatibility constructor for stages decoded or built before observation order existed. */
    public ExecutionFlowStage(int index, CommandSnippet command, List<ExecutionFlowContext> inputs,
                              List<ExecutionFlowContext> outputs, List<ExecutionFlowEdge> edges,
                              List<Long> droppedContextIds, int inputCount, int outputCount, int droppedCount,
                              boolean terminal, int executionCount, int successCount, boolean complete,
                              boolean lineageComplete, boolean truncated) {
        this(index, command, inputs, outputs, edges, droppedContextIds, inputCount, outputCount, droppedCount,
            terminal, executionCount, successCount, complete, lineageComplete, truncated, -1, List.of());
    }

    /** Compatibility constructor for stages built before historical call stacks existed. */
    public ExecutionFlowStage(int index, CommandSnippet command, List<ExecutionFlowContext> inputs,
                              List<ExecutionFlowContext> outputs, List<ExecutionFlowEdge> edges,
                              List<Long> droppedContextIds, int inputCount, int outputCount, int droppedCount,
                              boolean terminal, int executionCount, int successCount, boolean complete,
                              boolean lineageComplete, boolean truncated, long observationOrder) {
        this(index, command, inputs, outputs, edges, droppedContextIds, inputCount, outputCount, droppedCount,
            terminal, executionCount, successCount, complete, lineageComplete, truncated, observationOrder, List.of());
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

    /** A changed source relative to its recorded parent; a fresh occurrence ID alone is not new. */
    public boolean isCreatedContext(long contextId) {
        if (terminal || !complete || !lineageComplete) return false;
        if (inputs.stream().anyMatch(context -> context.id() == contextId)) return false;
        ExecutionFlowContext output = outputs.stream().filter(context -> context.id() == contextId)
            .findFirst().orElse(null);
        if (output == null) return false;
        boolean hasParent = false;
        for (ExecutionFlowEdge edge : edges) {
            if (edge.outputContextId() != contextId) continue;
            for (ExecutionFlowContext input : inputs) {
                if (input.id() != edge.inputContextId()) continue;
                hasParent = true;
                if (sameSource(input.source(), output.source())) return false;
            }
        }
        return hasParent;
    }

    private static boolean sameSource(PauseSource before, PauseSource after) {
        // Entity names are presentation metadata. Renaming an executor does not create a source.
        boolean sameEntity = before.entity() == null ? after.entity() == null
            : after.entity() != null && before.entity().uuid().equals(after.entity().uuid());
        return sameEntity && before.dimension().equals(after.dimension())
            && before.anchor().x() == after.anchor().x()
            && before.anchor().y() == after.anchor().y()
            && before.anchor().z() == after.anchor().z()
            && before.pitch() == after.pitch() && before.yaw() == after.yaw();
    }
}
