package works.nuty.bastion.core.port;

import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.ExecutionFlowTrace;
import works.nuty.bastion.core.model.PauseSnapshot;

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

    /** The engine has resumed normal execution, or a step finished without another pause. */
    void resumed();

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
