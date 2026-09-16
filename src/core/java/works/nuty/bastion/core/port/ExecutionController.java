package works.nuty.bastion.core.port;

import java.util.function.BooleanSupplier;

/**
 * Driven port: the mechanism that actually suspends the thread executing commands while the
 * debugger is paused. The Minecraft adapter services only debugger requests and
 * connection maintenance while parked.
 */
public interface ExecutionController {
    enum ParkResult { RESUMED, CANCELLED }
    /**
     * Block the calling thread until {@code resumed} reports {@code true}, keeping the host
     * responsive in the meantime. Must return promptly once {@code resumed} becomes true (or the
     * host is shutting down). Return CANCELLED for host shutdown or an unavailable host.
     */
    ParkResult parkUntil(BooleanSupplier resumed);
}
