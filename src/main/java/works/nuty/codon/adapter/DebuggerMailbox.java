package works.nuty.codon.adapter;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;

/** Thread-safe admission accounting; all callbacks execute outside the mailbox lock. */
final class DebuggerMailbox {
    static final int REQUEST_CAPACITY = 256;
    static final int CONTROL_CAPACITY = 64;
    static final int REQUESTS_PER_CONNECTION = 32;
    static final int CONTROLS_PER_CONNECTION = 8;
    static final int RECOVERY_CAPACITY = 64;

    private record Request(Object connection, boolean control, Runnable task) { }

    private final ArrayDeque<Request> network = new ArrayDeque<>();
    private final IdentityHashMap<Object, int[]> connections = new IdentityHashMap<>();
    private final int[] pending = new int[2];
    private int recoveryPending;
    private boolean closed;

    synchronized boolean offerNetwork(Object connection, boolean control, Runnable task) {
        int kind = control ? 1 : 0;
        if (closed || pending[kind] >= (control ? CONTROL_CAPACITY : REQUEST_CAPACITY)) return false;
        int[] counts = connections.get(connection);
        if (counts != null && counts[kind] >= (control ? CONTROLS_PER_CONNECTION : REQUESTS_PER_CONNECTION))
            return false;
        if (counts == null) {
            counts = new int[2];
            connections.put(connection, counts);
        }
        counts[kind]++;
        pending[kind]++;
        // One FIFO preserves a request's observation before a subsequently admitted step.
        network.addLast(new Request(connection, control, task));
        return true;
    }

    synchronized boolean offerRecovery(Runnable task) {
        if (closed || recoveryPending >= RECOVERY_CAPACITY) return false;
        recoveryPending++;
        // Reserve admission without moving a local step ahead of an already admitted query.
        network.addLast(new Request(null, false, task));
        return true;
    }

    synchronized Runnable poll() {
        Request request = network.pollFirst();
        if (request == null) return null;
        if (request.connection() == null) {
            recoveryPending--;
            return request.task();
        }
        int kind = request.control() ? 1 : 0;
        int[] counts = connections.get(request.connection());
        counts[kind]--;
        pending[kind]--;
        if (counts[0] == 0 && counts[1] == 0) connections.remove(request.connection());
        return request.task();
    }

    synchronized void close() {
        closed = true;
        network.clear();
        connections.clear();
        pending[0] = pending[1] = 0;
        recoveryPending = 0;
    }
}
