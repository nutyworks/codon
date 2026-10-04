package works.nuty.codon.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DebuggerTaskQueueTest {
    @BeforeAll static void bootstrapCommands() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void recognizedControlsKeepExistingParserForms() {
        for (String command : new String[]{"codon", "codon resume", "/codon stepinto 7", " stop "})
            assertTrue(DebuggerTaskQueue.isControlCommand(command));
        for (String command : new String[]{"say codon", "codonish", "stopping", "bastion resume"})
            assertFalse(DebuggerTaskQueue.isControlCommand(command));
    }

    @Test void blockedRecoveryAdmissionRetriesAndRestoresInterruption() throws Exception {
        var mailbox = new DebuggerMailbox();
        for (int i = 0; i < DebuggerMailbox.RECOVERY_CAPACITY; i++) mailbox.offerRecovery(() -> { });
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean restoredInterrupt = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = Thread.startVirtualThread(() -> {
            Thread.currentThread().interrupt();
            try {
                DebuggerTaskQueue.awaitExecution(() -> true, task -> {
                    attempted.countDown();
                    return mailbox.offerRecovery(task);
                }, () -> completed.set(true));
                restoredInterrupt.set(Thread.currentThread().isInterrupted());
            } catch (Throwable problem) { failure.set(problem); }
        });
        assertTrue(attempted.await(5, TimeUnit.SECONDS));
        assertFalse(completed.get());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!completed.get() && System.nanoTime() < deadline) {
            Runnable task = mailbox.poll();
            if (task != null) task.run();
            else Thread.sleep(5);
        }
        caller.join(5_000);
        assertFalse(caller.isAlive());
        assertNull(failure.get());
        assertTrue(completed.get());
        assertTrue(restoredInterrupt.get());
    }

    @Test void shutdownCancelsAnUnstartedTaskEvenAfterAdmission() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicBoolean ran = new AtomicBoolean();
        assertThrows(CancellationException.class, () -> DebuggerTaskQueue.awaitExecution(() -> false, task -> {
            queued.set(task);
            return true;
        }, () -> ran.set(true)));
        queued.get().run();
        assertFalse(ran.get(), "late draining cannot execute cancelled cleanup");
        assertThrows(CancellationException.class,
            () -> DebuggerTaskQueue.awaitExecution(() -> false, task -> false, () -> fail("not admitted")));
    }

    @Test void startedStopTaskFinishesAndFailuresReachTheCaller() {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean finished = new AtomicBoolean();
        DebuggerTaskQueue.awaitExecution(running::get, task -> { task.run(); return true; }, () -> {
            running.set(false);
            finished.set(true);
        });
        assertTrue(finished.get());
        var cause = new IllegalStateException("cleanup failed");
        CompletionException failure = assertThrows(CompletionException.class,
            () -> DebuggerTaskQueue.awaitExecution(() -> true, task -> { task.run(); return true; }, () -> { throw cause; }));
        assertSame(cause, failure.getCause());
    }
}
