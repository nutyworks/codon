package works.nuty.codon.core.model;

import java.util.List;

/**
 * An immutable description of the debugger's state at a pause, handed to the presentation layer
 * (in-game UI, and later DAP) through {@code DebuggerEventSink}. It contains everything needed
 * to render the pause without reaching back into live Minecraft state.
 */
public record PauseSnapshot(
    SourceLocation location,
    CommandSnippet command,
    int depth,
    List<CallFrame> callStack,
    List<PauseSource> pauseSources,
    List<ExecutionFlowTrace> executionFlows,
    PauseReason reason,
    long pauseId
) {
    /** Fixtures and offline presentations have no queryable server pause. */
    public PauseSnapshot(SourceLocation location, CommandSnippet command, int depth,
                         List<CallFrame> callStack, List<PauseSource> pauseSources, PauseReason reason) {
        this(location, command, depth, callStack, pauseSources, List.of(), reason, 0);
    }

    public PauseSnapshot(SourceLocation location, CommandSnippet command, int depth,
                         List<CallFrame> callStack, List<PauseSource> pauseSources,
                         PauseReason reason, long pauseId) {
        this(location, command, depth, callStack, pauseSources, List.of(), reason, pauseId);
    }

    public PauseSnapshot(SourceLocation location, CommandSnippet command, int depth,
                         List<CallFrame> callStack, List<PauseSource> pauseSources,
                         List<ExecutionFlowTrace> executionFlows,
                         PauseReason reason) {
        this(location, command, depth, callStack, pauseSources, executionFlows, reason, 0);
    }

    public PauseSnapshot {
        callStack = List.copyOf(callStack);
        pauseSources = List.copyOf(pauseSources);
        executionFlows = List.copyOf(executionFlows);
    }
}
