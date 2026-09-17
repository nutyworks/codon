package works.nuty.bastion.core.model;

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
    PauseReason reason,
    long pauseId
) {
    /** Fixtures and offline presentations have no queryable server pause. */
    public PauseSnapshot(SourceLocation location, CommandSnippet command, int depth,
                         List<CallFrame> callStack, List<PauseSource> pauseSources, PauseReason reason) {
        this(location, command, depth, callStack, pauseSources, reason, 0);
    }

    public PauseSnapshot {
        callStack = List.copyOf(callStack);
        pauseSources = List.copyOf(pauseSources);
    }
}
