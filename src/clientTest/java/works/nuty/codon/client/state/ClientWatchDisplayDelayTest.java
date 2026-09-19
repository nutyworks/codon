package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.EntityRef;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.Vec3d;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientWatchDisplayDelayTest {
    private static final long GRACE_NANOS = 250_000_000L;

    @Test
    void fastRefreshReplacesTheOldValueWithoutFlashingPendingAtTheOriginalDeadline() {
        AtomicLong now = new AtomicLong();
        UUID executor = UUID.randomUUID();
        ClientWatchState state = new ClientWatchState(now::get);
        state.add(pinnedScore(executor));
        state.paused(1, 0);
        state.accept(1, onlyQuery(state).requestId(), value("7", executor));

        state.stepping();
        state.paused(2, 0);
        ClientWatchState.Query refresh = onlyQuery(state);
        now.set(GRACE_NANOS - 1);
        assertEquals("7", displayed(state).displayedResult().value());
        state.accept(2, refresh.requestId(), value("0", executor));
        assertEquals("0", displayed(state).displayedResult().value());

        now.set(GRACE_NANOS);
        assertEquals("0", displayed(state).displayedResult().value());
        now.set(GRACE_NANOS * 2);
        assertEquals("0", displayed(state).displayedResult().value());
    }

    @Test
    void holdsTheLastPinnedValueUntilTheGraceBoundaryWithoutChangingRawPendingState() {
        AtomicLong now = new AtomicLong();
        UUID executor = UUID.randomUUID();
        WatchSpec score = pinnedScore(executor);
        ClientWatchState state = new ClientWatchState(now::get);
        assertTrue(state.add(score));

        state.paused(1, 0);
        ClientWatchState.Query initial = onlyQuery(state);
        state.accept(1, initial.requestId(), value("10", executor));
        state.stepping();

        state.paused(2, 0);
        ClientWatchState.Query changed = onlyQuery(state);
        state.accept(2, changed.requestId(), value("11", executor));
        assertEquals(ClientWatchState.Change.VALUE_CHANGED, displayed(state).displayedChange());
        assertEquals("10", displayed(state).displayedPreviousValue());

        state.stepping();
        now.set(100_000_000L);
        state.paused(3, 0); // A pause acknowledgement must not restart the step's timer.
        ClientWatchState.Query abandoned = onlyQuery(state);
        state.stepping(); // No reply arrived, so this repeated step must not prolong the original hold.
        now.set(150_000_000L);
        state.paused(4, 0);
        ClientWatchState.Query replacement = onlyQuery(state);

        now.set(GRACE_NANOS - 1);
        assertNull(raw(state).result(), "the query state remains pending while presentation retains the old row");
        assertEquals("11", displayed(state).displayedResult().value());
        assertEquals(ClientWatchState.Change.VALUE_CHANGED, displayed(state).displayedChange());
        assertEquals("10", displayed(state).displayedPreviousValue());
        assertTrue(state.drainQueries().isEmpty(), "the retained display must not create a duplicate query");

        now.set(GRACE_NANOS);
        assertNull(displayed(state).displayedResult(), "exactly 250ms exposes the normal waiting row");
        state.accept(3, abandoned.requestId(), value("stale", executor));
        assertNull(raw(state).result(), "a reply correlated to the superseded pause cannot fill the current query");

        state.accept(4, replacement.requestId(), value("0", executor));
        assertEquals("0", raw(state).result().value());
        assertEquals("0", displayed(state).displayedResult().value(), "a fresh zero is not treated as waiting");
        assertEquals(ClientWatchState.Change.VALUE_CHANGED, displayed(state).displayedChange());
        assertEquals("11", displayed(state).displayedPreviousValue(), "retaining a row does not alter comparison history");

        state.stepping();
        state.paused(5, 0);
        ClientWatchState.Query unavailable = onlyQuery(state);
        WatchResult missing = WatchResult.absent(WatchResult.Status.OBJECTIVE_MISSING, entityKey(executor));
        state.accept(5, unavailable.requestId(), missing);
        assertEquals(WatchResult.Status.OBJECTIVE_MISSING, raw(state).result().status());
        assertEquals(WatchResult.Status.OBJECTIVE_MISSING, displayed(state).displayedResult().status(),
            "a fresh non-value result also replaces the held display immediately");
    }

    @Test
    void doesNotInventADisplayWhileTheFirstWatchQueryIsPending() {
        AtomicLong now = new AtomicLong();
        ClientWatchState state = new ClientWatchState(now::get);
        state.add(pinnedScore(UUID.randomUUID()));

        state.paused(1, 0);
        onlyQuery(state);
        assertNull(raw(state).result());
        assertNull(displayed(state).displayedResult());

        state.stepping();
        now.set(GRACE_NANOS - 1);
        state.paused(2, 0);
        onlyQuery(state);
        assertNull(raw(state).result());
        assertNull(displayed(state).displayedResult(), "a watch with no prior reply waits immediately");
    }

    @Test
    void lifecycleAndDefinitionChangesClearTheRetainedDisplay() {
        UUID executor = UUID.randomUUID();

        Retained retainedForResume = retained(executor);
        retainedForResume.state().resumed();
        assertNull(displayed(retainedForResume.state()).displayedResult());

        Retained retainedForReset = retained(executor);
        retainedForReset.state().reset();
        assertTrue(retainedForReset.state().displayedEntries().isEmpty());

        Retained retainedForEdit = retained(executor);
        assertTrue(retainedForEdit.state().update(retainedForEdit.id(),
            new WatchSpec(WatchSpec.Kind.SCORE, "deaths", "", executor)));
        assertNull(displayed(retainedForEdit.state()).displayedResult());

        Retained retainedForRemoval = retained(executor);
        retainedForRemoval.state().remove(retainedForRemoval.id());
        assertTrue(retainedForRemoval.state().displayedEntries().isEmpty());

        Retained retainedForRebind = retained(executor);
        assertTrue(retainedForRebind.state().unpin(retainedForRebind.id()));
        assertNull(displayed(retainedForRebind.state()).displayedResult());
    }

    @Test
    void retainsTheOutgoingExecutorsIdentityUntilTheNewSelectionHasWaitedForTheGracePeriod() {
        AtomicLong now = new AtomicLong();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ClientWatchState state = new ClientWatchState(now::get);
        WatchSpec score = new WatchSpec(WatchSpec.Kind.SCORE, "kills", "");
        state.add(score);
        state.paused(1, 0);
        state.rememberExecutors(List.of(source(first, "Pig"), source(second, "Cow")));
        ClientWatchState.Query firstQuery = onlyQuery(state);
        state.accept(1, firstQuery.requestId(), value("7", first));

        state.stepping();
        state.paused(2, 1);
        state.rememberExecutors(List.of(source(first, "Pig"), source(second, "Cow")));

        assertNull(raw(state).result());
        now.set(GRACE_NANOS - 1);
        assertEquals("7", displayed(state).displayedResult().value());
        assertEquals(first, displayed(state).displayedExecutor(), "the retained value keeps Pig's identity");

        now.set(GRACE_NANOS);
        assertNull(displayed(state).displayedResult());
        assertEquals(second, displayed(state).displayedExecutor());
        assertFalse(state.drainQueries().isEmpty(), "the selected executor still receives its normal query");
    }

    private static Retained retained(UUID executor) {
        AtomicLong now = new AtomicLong();
        ClientWatchState state = new ClientWatchState(now::get);
        state.add(pinnedScore(executor));
        long id = raw(state).id();
        state.paused(1, 0);
        ClientWatchState.Query query = onlyQuery(state);
        state.accept(1, query.requestId(), value("7", executor));
        state.stepping();
        return new Retained(state, id);
    }

    private static ClientWatchState.Query onlyQuery(ClientWatchState state) {
        return state.drainQueries().getFirst();
    }

    private static ClientWatchState.Entry raw(ClientWatchState state) {
        return state.entries().getFirst();
    }

    private static ClientWatchState.Entry displayed(ClientWatchState state) {
        return state.displayedEntries().getFirst();
    }

    private static WatchSpec pinnedScore(UUID executor) {
        return new WatchSpec(WatchSpec.Kind.SCORE, "kills", "", executor);
    }

    private static WatchResult value(String value, UUID executor) {
        return new WatchResult(WatchResult.Status.VALUE, value, entityKey(executor));
    }

    private static String entityKey(UUID executor) {
        return "entity:" + executor;
    }

    private static PauseSource source(UUID executor, String name) {
        return new PauseSource(new Vec3d(0, 64, 0), 0, 0, new EntityRef(executor, name), "minecraft:overworld");
    }

    private record Retained(ClientWatchState state, long id) { }
}
