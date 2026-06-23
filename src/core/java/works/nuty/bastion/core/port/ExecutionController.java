package works.nuty.bastion.core.port;

import java.util.function.BooleanSupplier;

/**
 * Driven port: the mechanism that actually suspends the thread executing commands while the
 * debugger is paused. The Minecraft adapter implements this with {@code server.managedBlock},
 * which keeps the server responsive (ticking the network connection) so resume/step packets can
 * arrive while parked.
 */
public interface ExecutionController {
    /**
     * Block the calling thread until {@code resumed} reports {@code true}, keeping the host
     * responsive in the meantime. Must return promptly once {@code resumed} becomes true (or the
     * host is shutting down).
     */
    void parkUntil(BooleanSupplier resumed);
}
