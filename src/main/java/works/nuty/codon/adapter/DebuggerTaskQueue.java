package works.nuty.codon.adapter;

import net.minecraft.server.MinecraftServer;
import works.nuty.codon.CodonMod;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** A bounded server-thread mailbox reserved for debugger control work. */
public final class DebuggerTaskQueue {
    private static final int MAX_TASKS_PER_DRAIN = 64;
    private static final ConcurrentHashMap<MinecraftServer, ConcurrentLinkedQueue<Runnable>> QUEUES =
        new ConcurrentHashMap<>();

    private DebuggerTaskQueue() {
    }

    /** Recognizes only controls that must remain available while a command is paused. */
    public static boolean isControlCommand(String message) {
        String command = net.minecraft.commands.Commands.trimOptionalPrefix(message.trim());
        return command.equals("codon") || command.startsWith("codon ") || command.equals("stop");
    }

    /** Enqueues debugger-only work, including while the normal server tick is running. */
    public static void execute(MinecraftServer server, Runnable task) {
        if (server == null || task == null || !server.isRunning()) {
            return;
        }
        var queue = QUEUES.computeIfAbsent(server, ignored -> new ConcurrentLinkedQueue<>());
        if (!server.isRunning()) {
            QUEUES.remove(server, queue);
            return;
        }
        queue.offer(task);
    }

    /** Preserves executeBlocking's completion contract without racing entry into a pause. */
    public static void executeBlocking(MinecraftServer server, Runnable task) {
        if (server.isSameThread()) {
            task.run();
            return;
        }
        CompletableFuture<Void> completion = new CompletableFuture<>();
        // Execution and shutdown cancellation compete to claim a task. Once execution starts,
        // even a task that stops the server must finish before its caller is released.
        AtomicBoolean claimed = new AtomicBoolean();
        execute(server, () -> {
            if (!claimed.compareAndSet(false, true)) return;
            try {
                task.run();
                completion.complete(null);
            } catch (RuntimeException | Error failure) {
                completion.completeExceptionally(failure);
            }
        });
        boolean interrupted = false;
        try {
            while (true) {
                if (!server.isRunning() && claimed.compareAndSet(false, true)) {
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
        ConcurrentLinkedQueue<Runnable> queue = QUEUES.get(server);
        if (queue == null) {
            return 0;
        }
        for (int i = 0; i < MAX_TASKS_PER_DRAIN; i++) {
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
            QUEUES.remove(server);
        }
    }
}
