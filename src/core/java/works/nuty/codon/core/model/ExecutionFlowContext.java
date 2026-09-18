package works.nuty.codon.core.model;

/**
 * One occurrence of a command execution context in an observed execute flow.
 *
 * <p>The id belongs to the invocation, not to an entity. Two contexts may therefore carry the
 * same entity UUID and coordinates while remaining distinct flow nodes.
 */
public record ExecutionFlowContext(long id, PauseSource source) {
}
