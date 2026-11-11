package works.nuty.codon.core.port;

import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.SourceLocation;

import java.util.List;
import java.util.Set;

/**
 * Driven port: where the engine publishes state transitions for presentation. Adapters include
 * the server&#8594;client sync sender (for in-game UI on both integrated and dedicated servers)
 * and, on the roadmap, a Debug Adapter Protocol bridge for external tooling.
 */
public interface DebuggerEventSink {
    /** The engine has paused execution; {@code snapshot} fully describes the pause. */
    void paused(PauseSnapshot snapshot);

    /**
     * Whether a live recipient can present a pause and release it. Without one the engine does not
     * park: nobody could see the stop, and the host would stay frozen until an administrator acted.
     */
    default boolean hasPauseRecipient() {
        return true;
    }

    /**
     * A stop at {@code location} was not kept because no recipient could present it: skipped before
     * parking, or {@code released} after every recipient of the paused stop disconnected.
     */
    default void pauseWithoutRecipient(SourceLocation location, boolean released) {
    }

    /** Debugger inspection ended; restore normal player presentation. */
    void resumed();

    /** Run to the next breakpoint within the current execution, retaining the detached camera. */
    default void continued() {
        resumed();
    }

    /** Execution is advancing to the next step; presentation may retain its detached camera. */
    default void stepping() {
        resumed();
    }

    /**
     * The outer execution scope completed. This is inspection data only: recipients must not
     * recreate a pause or reactivate debugger controls.
     */
    default void executionFlowsCompleted(List<ExecutionFlowTrace> flows) {
    }

    /**
     * The set of breakpoints changed; {@code blockBreakpoints} is the new full set of block
     * breakpoints (the ones the in-world visualization renders). Default no-op, since not every
     * sink cares.
     */
    default void breakpointsChanged(Set<BlockLocation> blockBreakpoints) {
    }

    /** A no-op sink, useful as a default before adapters are wired and in tests. */
    DebuggerEventSink NOOP = new DebuggerEventSink() {
        @Override
        public void paused(PauseSnapshot snapshot) {
        }

        @Override
        public void resumed() {
        }
    };
}
