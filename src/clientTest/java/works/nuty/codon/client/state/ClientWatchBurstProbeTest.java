package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Isolated verification probe for the reported mailbox burst; no production change. */
class ClientWatchBurstProbeTest {
    @Test void thirtyThreePrimaryQueriesAreAllDrainedAndAnUnansweredOneTimesOutWithoutRetry() {
        AtomicLong clock = new AtomicLong();
        var watches = new ClientWatchState(clock::get);
        for (int i = 0; i < 33; i++)
            assertTrue(watches.add(new WatchSpec(WatchSpec.Kind.SCORE, "objective" + i, "")));

        watches.paused(1, 0);
        var burst = watches.drainQueries();
        assertEquals(33, burst.size());
        for (int i = 0; i < 32; i++)
            watches.accept(1, burst.get(i).requestId(), new WatchResult(WatchResult.Status.VALUE, "1", "score:holder"));
        assertTrue(watches.drainQueries().isEmpty());

        clock.set(5_000_000_000L);
        assertEquals(WatchResult.Status.UNAVAILABLE, watches.entries().get(32).result().status());
        assertTrue(watches.drainQueries().isEmpty(), "timed-out reads have no automatic retry");
    }

    @Test void seventeenEntityTargetsProduceThirtyFourQueriesAfterStep() {
        var watches = new ClientWatchState(() -> 0);
        UUID executor = UUID.randomUUID();
        for (int i = 0; i < 17; i++)
            assertTrue(watches.add(new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "field" + i)));

        watches.paused(1, 0);
        for (var query : watches.drainQueries())
            watches.accept(1, query.requestId(), new WatchResult(WatchResult.Status.VALUE, "1", "entity:" + executor));
        watches.stepping();
        watches.paused(2, 0);
        var burst = watches.drainQueries();
        assertEquals(34, burst.size());
        assertEquals(17, burst.stream().filter(q -> q.capturedEntity() != null).count());
    }

    @Test void steppedEntityBurstClaimsOnlyTheQueriesThatFitEachSendWindow() {
        var watches = new ClientWatchState(() -> 0);
        UUID executor = UUID.randomUUID();
        for (int i = 0; i < 17; i++)
            assertTrue(watches.add(new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "field" + i)));
        watches.paused(1, 0);
        for (var query : watches.drainQueries())
            watches.accept(1, query.requestId(), new WatchResult(WatchResult.Status.VALUE, "1", "entity:" + executor));
        watches.stepping();
        watches.paused(2, 0);

        var first = watches.drainQueries(12);
        var second = watches.drainQueries(12);
        var third = watches.drainQueries(12);
        assertEquals(List.of(12, 12, 10), List.of(first.size(), second.size(), third.size()));
        assertEquals(17, java.util.stream.Stream.of(first, second, third).flatMap(List::stream)
            .filter(query -> query.capturedEntity() != null).count());
        assertTrue(watches.drainQueries(12).isEmpty());
    }
}
