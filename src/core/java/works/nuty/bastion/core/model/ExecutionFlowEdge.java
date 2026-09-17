package works.nuty.bastion.core.model;

/** An observed modifier result connecting one input-context occurrence to one output occurrence. */
public record ExecutionFlowEdge(long inputContextId, long outputContextId) {
}
