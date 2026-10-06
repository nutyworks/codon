package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientWatchManagementStateTest {
    @Test
    void simpleQuotedNbtPathsShareOneIdentityButComplexPathsRemainLiteral() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        WatchSpec plain = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Inventory[0].tag.display");
        WatchSpec quoted = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "\"Inventory\"[0].\"tag\".\"display\"");
        WatchSpec filter = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Inventory[{id:\"minecraft:stone\"}]");

        long id = state.addOrFind(plain);
        assertEquals(id, state.addOrFind(quoted));
        assertFalse(state.add(quoted));
        assertTrue(state.add(filter), "filter syntax is not guessed into a different expression");
        assertEquals(2, state.definitions().size());
    }

    @Test
    void updateKeepsRowOrderAndRejectsRepliesForItsOldExpression() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        WatchSpec first = new WatchSpec(WatchSpec.Kind.SCORE, "first", "");
        WatchSpec replacement = new WatchSpec(WatchSpec.Kind.SCORE, "replacement", "");
        WatchSpec second = new WatchSpec(WatchSpec.Kind.SCORE, "second", "");
        long id = state.addOrFind(first);
        state.add(second);
        state.paused(4, 0);
        ClientWatchState.Query stale = state.drainQueries().stream().filter(query -> query.spec().equals(first)).findFirst().orElseThrow();

        assertTrue(state.update(id, replacement));
        assertEquals(List.of(replacement, second), state.definitions());
        ClientWatchState.Query current = state.drainQueries().stream().filter(query -> query.spec().equals(replacement)).findFirst().orElseThrow();
        state.accept(4, stale.requestId(), value("old"));
        assertNull(state.entries().getFirst().result());
        state.accept(4, current.requestId(), value("fresh"));
        assertEquals("fresh", state.entries().getFirst().result().value());
        assertEquals(id, state.revealId());
    }

    @Test
    void retryReplacesServerFailedRequestAndRejectsItsOldReply() {
        AtomicLong now = new AtomicLong();
        ClientWatchState state = new ClientWatchState(now::get);
        long id = state.addOrFind(new WatchSpec(WatchSpec.Kind.SCORE, "points", ""));
        state.paused(6, 0);
        ClientWatchState.Query failed = state.drainQueries().getFirst();
        now.set(4_000_000_000L);
        state.accept(6, failed.requestId(), WatchResult.absent(WatchResult.Status.UNAVAILABLE, ""));
        assertEquals(WatchResult.Status.UNAVAILABLE, state.entries().getFirst().result().status());
        assertTrue(state.canRetry(id));

        state.retry(id);
        ClientWatchState.Query retried = state.drainQueries().getFirst();
        state.accept(6, failed.requestId(), value("late"));
        assertNull(state.entries().getFirst().result());
        state.accept(6, retried.requestId(), value("fresh"));
        assertEquals("fresh", state.entries().getFirst().result().value());
    }

    @Test
    void saveAcknowledgementsAreCorrelatedAndExpiryFailsTheCurrentTransfer() {
        AtomicLong now = new AtomicLong();
        ClientWatchState state = new ClientWatchState(now::get);
        state.saveStarted(10);
        state.saveStarted(11);
        state.saveFinished(10, true);
        assertEquals(ClientWatchState.SaveStatus.SAVING, state.saveStatus());
        state.saveFinished(11, true);
        assertEquals(ClientWatchState.SaveStatus.SAVED, state.saveStatus());
        state.saveStarted(12);
        now.set(5_000_000_000L);
        assertEquals(ClientWatchState.SaveStatus.FAILED, state.saveStatus());
        state.saveFinished(12, true);
        assertEquals(ClientWatchState.SaveStatus.FAILED, state.saveStatus());
        state.reset();
        assertEquals(ClientWatchState.SaveStatus.IDLE, state.saveStatus());
    }

    @Test
    void savedNonPinsKeepInsertionOrderWhilePinsLeadAndAddsRequestReveal() {
        UUID bound = UUID.randomUUID();
        ClientWatchState state = new ClientWatchState(() -> 0);
        long first = state.addOrFind(new WatchSpec(WatchSpec.Kind.SCORE, "first", ""));
        state.addOrFind(new WatchSpec(WatchSpec.Kind.SCORE, "bound", "", bound));
        long last = state.addOrFind(new WatchSpec(WatchSpec.Kind.SCORE, "last", ""));

        assertEquals(List.of("bound", "first", "last"), state.displayedEntries().stream()
            .map(entry -> entry.spec().target()).toList());
        assertEquals(last, state.revealId());
        state.reveal(first);
        assertEquals(first, state.revealId());
        assertEquals(4, state.revealRevision());
    }

    private static WatchResult value(String value) {
        return new WatchResult(WatchResult.Status.VALUE, value, "entity:00000000-0000-0000-0000-000000000001");
    }
}
