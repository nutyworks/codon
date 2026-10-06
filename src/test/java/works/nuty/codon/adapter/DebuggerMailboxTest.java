package works.nuty.codon.adapter;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DebuggerMailboxTest {
    private static final Runnable NOTHING = () -> { };

    @Test void oneConnectionCannotRetainAnUnlimitedPayloadOrCommandFlood() {
        var mailbox = new DebuggerMailbox();
        Object connection = new Object();
        int requests = 0, controls = 0;
        for (int i = 0; i < 10_000; i++) {
            if (mailbox.offerNetwork(connection, false, NOTHING)) requests++;
            if (mailbox.offerNetwork(connection, true, NOTHING)) controls++;
        }
        assertEquals(DebuggerMailbox.REQUESTS_PER_CONNECTION, requests);
        assertEquals(DebuggerMailbox.CONTROLS_PER_CONNECTION, controls);
        assertTrue(mailbox.offerNetwork(new Object(), false, NOTHING), "another connection still has capacity");
        assertEquals(requests + controls + 1, drain(mailbox));
        assertTrue(mailbox.offerNetwork(connection, false, NOTHING), "draining releases the connection budget");
    }

    @Test void thirtyThirdRequestDependsOnWhetherDrainInterleaves() {
        Object connection = new Object();
        var saturated = new DebuggerMailbox();
        for (int i = 0; i < 32; i++) assertTrue(saturated.offerNetwork(connection, false, NOTHING));
        assertFalse(saturated.offerNetwork(connection, false, NOTHING));

        var interleaved = new DebuggerMailbox();
        for (int i = 0; i < 32; i++) assertTrue(interleaved.offerNetwork(connection, false, NOTHING));
        assertSame(NOTHING, interleaved.poll());
        assertTrue(interleaved.offerNetwork(connection, false, NOTHING));
    }

    @Test void unrelatedRequestsCanConsumeTheWatchBurstHeadroom() {
        var mailbox = new DebuggerMailbox();
        Object connection = new Object();
        for (int i = 0; i < 21; i++) assertTrue(mailbox.offerNetwork(connection, false, NOTHING));
        for (int i = 0; i < 11; i++) assertTrue(mailbox.offerNetwork(connection, false, NOTHING));
        assertFalse(mailbox.offerNetwork(connection, false, NOTHING),
            "twelve Watch credits cannot guarantee admission after twenty-one other requests");
    }

    @Test void globalSaturationLeavesControlAndLocalRecoveryCapacity() {
        var mailbox = new DebuggerMailbox();
        int admitted = 0;
        for (int i = 0; i < 10_000; i++)
            if (mailbox.offerNetwork(new Object(), false, NOTHING)) admitted++;
        assertEquals(DebuggerMailbox.REQUEST_CAPACITY, admitted);
        for (int i = 0; i < DebuggerMailbox.CONTROL_CAPACITY; i++)
            assertTrue(mailbox.offerNetwork(new Object(), true, NOTHING));
        assertFalse(mailbox.offerNetwork(new Object(), true, NOTHING));
        Runnable recovery = () -> { };
        for (int i = 0; i < DebuggerMailbox.RECOVERY_CAPACITY; i++) assertTrue(mailbox.offerRecovery(recovery));
        assertFalse(mailbox.offerRecovery(recovery));
        for (int i = 0; i < DebuggerMailbox.REQUEST_CAPACITY + DebuggerMailbox.CONTROL_CAPACITY; i++) {
            assertSame(NOTHING, mailbox.poll(), "previously admitted requests keep their order");
            assertTrue(mailbox.offerNetwork(new Object(), i >= DebuggerMailbox.REQUEST_CAPACITY, NOTHING),
                "new ingress may continue");
        }
        assertSame(recovery, mailbox.poll(), "new ingress cannot delay already admitted local recovery");
        assertEquals(DebuggerMailbox.REQUEST_CAPACITY + DebuggerMailbox.CONTROL_CAPACITY
            + DebuggerMailbox.RECOVERY_CAPACITY - 1, drain(mailbox));
    }

    @Test void acceptedQueriesRemainBeforeStepsAndFailedTasksReleaseCapacity() {
        var mailbox = new DebuggerMailbox();
        var observed = new ArrayList<String>();
        Object connection = new Object();
        assertTrue(mailbox.offerNetwork(connection, false, () -> observed.add("query")));
        assertTrue(mailbox.offerNetwork(connection, true, () -> observed.add("step")));
        assertTrue(mailbox.offerRecovery(() -> observed.add("console-step")));
        assertTrue(mailbox.offerNetwork(connection, false, () -> { throw new IllegalStateException(); }));
        mailbox.poll().run();
        mailbox.poll().run();
        mailbox.poll().run();
        assertEquals(List.of("query", "step", "console-step"), observed);
        assertThrows(IllegalStateException.class, mailbox.poll()::run);
        for (int i = 0; i < DebuggerMailbox.REQUESTS_PER_CONNECTION; i++)
            assertTrue(mailbox.offerNetwork(connection, false, NOTHING));
    }

    @Test void simultaneousProducersCannotOverrunEitherBudget() throws Exception {
        var mailbox = new DebuggerMailbox();
        Object sameConnection = new Object();
        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> producers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            producers.add(Thread.startVirtualThread(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                for (int n = 0; n < 1_000; n++)
                    if (mailbox.offerNetwork(sameConnection, false, NOTHING)) admitted.incrementAndGet();
            }));
        }
        start.countDown();
        for (var thread : producers) thread.join();
        assertEquals(DebuggerMailbox.REQUESTS_PER_CONNECTION, admitted.get());
        assertEquals(admitted.get(), drain(mailbox));
    }

    @Test void closedMailboxDropsPendingWorkAndRejectsRacingProducers() {
        var mailbox = new DebuggerMailbox();
        Object connection = new Object();
        mailbox.offerNetwork(connection, false, NOTHING);
        mailbox.offerRecovery(NOTHING);
        mailbox.close();
        assertNull(mailbox.poll());
        assertFalse(mailbox.offerNetwork(connection, false, NOTHING));
        assertFalse(mailbox.offerRecovery(NOTHING));
    }

    private static int drain(DebuggerMailbox mailbox) {
        int count = 0;
        while (mailbox.poll() != null) count++;
        return count;
    }
}
