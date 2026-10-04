package works.nuty.codon.adapter;

import net.minecraft.server.MinecraftServer;
import works.nuty.codon.CodonMod;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** A bounded server-thread mailbox reserved for debugger control work. */
public final class DebuggerTaskQueue {
    private static final int MAX_TASKS_PER_DRAIN = 64;
    private static final ConcurrentHashMap<MinecraftServer, DebuggerMailbox> QUEUES =
        new ConcurrentHashMap<>();

    private DebuggerTaskQueue() {
    }

    /** Recognizes only controls that must remain available while a command is paused. */
    public static boolean isControlCommand(String message) {
        String command = net.minecraft.commands.Commands.trimOptionalPrefix(message.trim());
        return command.equals("codon") || command.startsWith("codon ") || command.equals("stop");
    }

    private static DebuggerMailbox mailbox(MinecraftServer server) {
        if (server == null || !server.isRunning()) return null;
        var queue = QUEUES.computeIfAbsent(server, ignored -> new DebuggerMailbox());
        if (!server.isRunning()) {
            QUEUES.remove(server, queue);
            queue.close();
            return null;
        }
        return queue;
    }

    /** Reserved for trusted local controls and lifecycle work, never for player packets. */
    public static boolean execute(MinecraftServer server, Runnable task) {
        var queue = mailbox(server);
        return queue != null && task != null && queue.offerRecovery(task);
    }

    /**
     * Bounds retained client work before server-thread permission checks. Control capacity is
     * reserved against payload floods, but remains untrusted and shares the network FIFO.
     * Overload drops the request: never move it to an unbounded vanilla queue or another lane.
     */
    public static boolean executeNetwork(MinecraftServer server, Object connection, boolean control, Runnable task) {
        var queue = mailbox(server);
        return queue != null && connection != null && task != null && queue.offerNetwork(connection, control, task);
    }

    /** Preserves executeBlocking's completion contract without racing entry into a pause. */
    public static void executeBlocking(MinecraftServer server, Runnable task) {
        if (server.isSameThread()) {
            task.run();
            return;
        }
        awaitExecution(server::isRunning, queued -> execute(server, queued), task);
    }

    /** Shared completion contract, also testable without booting a Minecraft server. */
    static void awaitExecution(BooleanSupplier running, Predicate<Runnable> admit, Runnable task) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        // Execution and shutdown cancellation compete to claim a task. Once execution starts,
        // even a task that stops the server must finish before its caller is released.
        AtomicBoolean claimed = new AtomicBoolean();
        Runnable queued = () -> {
            if (!claimed.compareAndSet(false, true)) return;
            try {
                task.run();
                completion.complete(null);
            } catch (RuntimeException | Error failure) {
                completion.completeExceptionally(failure);
            }
        };
        boolean interrupted = false;
        try {
            // Disconnect cleanup must complete even when the reserved lane is temporarily full.
            // Only its existing caller waits; no additional retained mailbox entry is created.
            while (!admit.test(queued)) {
                if (!running.getAsBoolean()) throw new CancellationException("Server stopped before debugger task was admitted");
                try {
                    TimeUnit.MILLISECONDS.sleep(100);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            while (true) {
                if (!running.getAsBoolean() && claimed.compareAndSet(false, true)) {
                    completion.completeExceptionally(new CancellationException("Server stopped before debugger task started"));
                }
                try {
                    completion.get(100, TimeUnit.MILLISECONDS);
                    return;
                } catch (TimeoutException ignored) {
                    // Recheck shutdown while an unstarted task is waiting in the mailbox.
                } catch (InterruptedException ignored) {
                    interrupted = true;
                } catch (ExecutionException failure) {
                    throw new CompletionException(failure.getCause());
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Runs a bounded batch so a resume or shutdown check cannot be starved by queued work. */
    public static int drain(MinecraftServer server) {
        DebuggerMailbox queue = QUEUES.get(server);
        if (queue == null) {
            return 0;
        }
        for (int i = 0; i < MAX_TASKS_PER_DRAIN; i++) {
            // Reserved admission and a bounded FIFO keep recovery reachable without reordering
            // a console/RCON step ahead of an already accepted inspection request.
            Runnable task = queue.poll();
            if (task == null) {
                return i;
            }
            try {
                task.run();
            } catch (RuntimeException e) {
                CodonMod.LOGGER.error("Debugger mailbox task failed", e);
            }
        }
        return MAX_TASKS_PER_DRAIN;
    }

    /** Drops queued work when a server session ends. */
    public static void clear(MinecraftServer server) {
        if (server != null) {
            var queue = QUEUES.remove(server);
            if (queue != null) queue.close();
        }
    }
}
