package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.CallFrame;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.EntityRef;
import works.nuty.codon.core.model.PauseReason;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.Vec3d;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientWatchStateTest {
    private static final WatchSpec SCORE = new WatchSpec(WatchSpec.Kind.SCORE, "kills", "");
    private static final WatchSpec ENTITY = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health");
    private static final WatchSpec STORAGE = new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "example:data", "value");

    @Test
    void rejectsDuplicatesAndAcceptsWatchesBeyondTheFormerLimit() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        assertTrue(state.add(SCORE));
        assertFalse(state.add(SCORE));
        for (int i = 1; i <= 12; i++) {
            assertTrue(state.add(new WatchSpec(WatchSpec.Kind.SCORE, "objective" + i, "")));
        }
        assertEquals(13, state.entries().size());

        long firstId = state.entries().getFirst().id();
        state.remove(firstId);
        assertTrue(state.add(new WatchSpec(WatchSpec.Kind.SCORE, "replacement", "")));
        assertEquals(13, state.entries().size());
    }

    @Test
    void highlightsValueChangesAndKeepsThePreviousValueForTheSameTarget() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.add(SCORE);
        state.paused(10, 0);
        ClientWatchState.Query first = onlyQuery(state);
        state.accept(10, first.requestId(), value("10", "executor-a"));

        state.stepping();
        state.paused(11, 0);
        ClientWatchState.Query second = onlyQuery(state);
        state.accept(11, second.requestId(), value("11", "executor-a"));

        ClientWatchState.Entry changed = onlyEntry(state);
        assertEquals(ClientWatchState.Change.VALUE_CHANGED, changed.change());
        assertEquals("10", changed.previousValue());

        state.stepping();
        state.paused(12, 0);
        ClientWatchState.Query third = onlyQuery(state);
        state.accept(12, third.requestId(), value("11", "executor-a"));
        assertEquals(ClientWatchState.Change.UNCHANGED, onlyEntry(state).change());
        assertEquals("", onlyEntry(state).previousValue());
    }

    @Test
    void retainsIndependentPreviousValuesWhenExecutorsAlternateAcrossSteps() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.add(SCORE);
        state.paused(1, 0);
        ClientWatchState.Query aFirst = onlyQuery(state);
        state.accept(1, aFirst.requestId(), value("10", "executor-a"));
        state.stepping();

        state.paused(2, 1);
        ClientWatchState.Query bFirst = onlyQuery(state);
        state.accept(2, bFirst.requestId(), value("20", "executor-b"));
        assertEquals(ClientWatchState.Change.TARGET_CHANGED, onlyEntry(state).change());
        state.stepping();

        state.paused(3, 0);
        ClientWatchState.Query aSecond = onlyQuery(state);
        state.accept(3, aSecond.requestId(), value("11", "executor-a"));
        assertEquals(ClientWatchState.Change.VALUE_CHANGED, onlyEntry(state).change());
        assertEquals("10", onlyEntry(state).previousValue());
        state.stepping();

        state.paused(4, 1);
        ClientWatchState.Query bSecond = onlyQuery(state);
        state.accept(4, bSecond.requestId(), value("21", "executor-b"));
        assertEquals(ClientWatchState.Change.VALUE_CHANGED, onlyEntry(state).change());
        assertEquals("20", onlyEntry(state).previousValue());
    }

    @Test
    void sourceRevisitInOnePauseRestoresItsCaptureAndKeepsItsExistingHighlight() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.add(SCORE);
        state.paused(1, 0);
        ClientWatchState.Query original = onlyQuery(state);
        state.accept(1, original.requestId(), value("10", "executor-a"));
        state.stepping();

        state.paused(2, 0);
        ClientWatchState.Query changed = onlyQuery(state);
        state.accept(2, changed.requestId(), value("11", "executor-a"));
        assertEquals(ClientWatchState.Change.VALUE_CHANGED, onlyEntry(state).change());

        state.selectSource(1);
        ClientWatchState.Query other = onlyQuery(state);
        state.accept(2, other.requestId(), value("20", "executor-b"));
        assertEquals(ClientWatchState.Change.TARGET_CHANGED, onlyEntry(state).change());

        state.selectSource(0);
        ClientWatchState.Entry revisited = onlyEntry(state);
        assertEquals("11", revisited.result().value());
        assertEquals(ClientWatchState.Change.VALUE_CHANGED, revisited.change());
        assertEquals("10", revisited.previousValue());
        assertTrue(state.drainQueries().isEmpty());
    }

    @Test
    void distinguishesExecutorSwitchAndAvailabilityFromAChangedValue() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.add(SCORE);
        state.paused(1, 0);
        ClientWatchState.Query first = onlyQuery(state);
        state.accept(1, first.requestId(), value("10", "executor-a"));

        state.stepping();
        state.paused(2, 0);
        ClientWatchState.Query second = onlyQuery(state);
        state.accept(2, second.requestId(), value("99", "executor-b"));
        assertEquals(ClientWatchState.Change.TARGET_CHANGED, onlyEntry(state).change());
        assertEquals("", onlyEntry(state).previousValue());

        state.stepping();
        state.paused(3, 0);
        ClientWatchState.Query missing = onlyQuery(state);
        state.accept(3, missing.requestId(), WatchResult.absent(WatchResult.Status.VALUE_MISSING, "executor-b"));
        assertEquals(ClientWatchState.Change.VALUE_DISAPPEARED, onlyEntry(state).change());
        assertEquals("99", onlyEntry(state).previousValue());

        state.stepping();
        state.paused(4, 0);
        ClientWatchState.Query restored = onlyQuery(state);
        state.accept(4, restored.requestId(), value("12", "executor-b"));
        ClientWatchState.Entry available = onlyEntry(state);
        assertEquals(ClientWatchState.Change.VALUE_APPEARED, available.change());
        assertEquals("12", available.result().value(), "missing data must not become an invented numeric value");
        assertEquals("", available.previousValue());
    }

    @Test
    void distinguishesMissingValueAppearanceAndDisappearanceForTheSameTarget() {
        String key = entityKey(UUID.randomUUID());
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.add(SCORE);
        state.paused(1, 0);
        ClientWatchState.Query missing = onlyQuery(state);
        state.accept(1, missing.requestId(), WatchResult.absent(WatchResult.Status.VALUE_MISSING, key));
        state.stepping();

        state.paused(2, 0);
        ClientWatchState.Query appeared = currentQuery(state);
        state.accept(2, appeared.requestId(), value("3", key));
        ClientWatchState.Entry present = onlyEntry(state);
        assertEquals(ClientWatchState.Change.VALUE_APPEARED, present.change());
        assertEquals("", present.previousValue());
        assertTrue(present.change().isValueChange());
        state.stepping();

        state.paused(3, 0);
        ClientWatchState.Query unchanged = currentQuery(state);
        state.accept(3, unchanged.requestId(), value("3", key));
        assertEquals(ClientWatchState.Change.UNCHANGED, onlyEntry(state).change());
        state.stepping();

        state.paused(4, 0);
        ClientWatchState.Query disappeared = currentQuery(state);
        state.accept(4, disappeared.requestId(), WatchResult.absent(WatchResult.Status.VALUE_MISSING, key));
        ClientWatchState.Entry absent = onlyEntry(state);
        assertEquals(ClientWatchState.Change.VALUE_DISAPPEARED, absent.change());
        assertEquals("3", absent.previousValue());
        assertTrue(absent.change().isValueChange());
    }

    @Test
    void capturedMissingValueAppearanceDisplaysImmediatelyWithoutRepeatingOnReselect() {
        UUID executor = UUID.randomUUID();
        String key = entityKey(executor);
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.add(SCORE);
        state.paused(1, 0);
        ClientWatchState.Query missing = onlyQuery(state);
        state.accept(1, missing.requestId(), WatchResult.absent(WatchResult.Status.VALUE_MISSING, key));
        state.stepping();

        state.paused(2, 0);
        List<ClientWatchState.Query> queries = state.drainQueries();
        ClientWatchState.Query current = queries.stream().filter(query -> query.capturedEntity() == null).findFirst().orElseThrow();
        ClientWatchState.Query captured = queries.stream().filter(query -> executor.equals(query.capturedEntity())).findFirst().orElseThrow();
        state.accept(2, current.requestId(), WatchResult.absent(WatchResult.Status.NO_EXECUTOR, "no-executor"));
        state.accept(2, captured.requestId(), value("3", key));
        ClientWatchState.Entry completed = onlyEntry(state);
        assertEquals(ClientWatchState.Change.VALUE_APPEARED, completed.displayedChange());
        assertEquals("", completed.displayedPreviousValue());
        assertEquals("3", completed.displayedResult().value());

        state.stepping();
        state.paused(3, 0);
        ClientWatchState.Query reselected = state.drainQueries().stream()
            .filter(query -> query.capturedEntity() == null).findFirst().orElseThrow();
        state.accept(3, reselected.requestId(), value("3", key));
        assertEquals(ClientWatchState.Change.UNCHANGED, onlyEntry(state).change());
    }

    @Test
    void doesNotClassifyDifferentTargetsOrErrorRecoveryAsValueAppearance() {
        String targetA = entityKey(UUID.randomUUID());
        String targetB = entityKey(UUID.randomUUID());
        ClientWatchState switched = new ClientWatchState(() -> 0);
        switched.add(SCORE);
        switched.paused(1, 0);
        ClientWatchState.Query missingA = onlyQuery(switched);
        switched.accept(1, missingA.requestId(), WatchResult.absent(WatchResult.Status.VALUE_MISSING, targetA));
        switched.stepping();
        switched.paused(2, 0);
        ClientWatchState.Query valueB = currentQuery(switched);
        switched.accept(2, valueB.requestId(), value("3", targetB));
        assertEquals(ClientWatchState.Change.TARGET_CHANGED, onlyEntry(switched).change());
        assertNotEquals(ClientWatchState.Change.VALUE_APPEARED, onlyEntry(switched).change());

        ClientWatchState recovered = new ClientWatchState(() -> 0);
        recovered.add(SCORE);
        recovered.paused(1, 0);
        ClientWatchState.Query knownMissing = onlyQuery(recovered);
        recovered.accept(1, knownMissing.requestId(), WatchResult.absent(WatchResult.Status.VALUE_MISSING, targetA));
        recovered.stepping();
        recovered.paused(2, 0);
        ClientWatchState.Query error = currentQuery(recovered);
        recovered.accept(2, error.requestId(), WatchResult.absent(WatchResult.Status.ERROR, ""));
        assertEquals(ClientWatchState.Change.AVAILABILITY_CHANGED, onlyEntry(recovered).change());
        recovered.stepping();
        recovered.paused(3, 0);
        ClientWatchState.Query recoveredValue = currentQuery(recovered);
        recovered.accept(3, recoveredValue.requestId(), value("3", targetA));
        assertEquals(ClientWatchState.Change.AVAILABILITY_CHANGED, onlyEntry(recovered).change());
        assertNotEquals(ClientWatchState.Change.VALUE_APPEARED, onlyEntry(recovered).change());
    }

    @Test
    void treatsEmptyTargetKeysFromErrorsAndTimeoutsAsAvailabilityChanges() {
        AtomicLong now = new AtomicLong();
        ClientWatchState state = new ClientWatchState(now::get);
        state.add(SCORE);
        state.paused(1, 0);
        ClientWatchState.Query initial = onlyQuery(state);
        state.accept(1, initial.requestId(), value("10", "executor-a"));

        state.stepping();
        state.paused(2, 0);
        ClientWatchState.Query error = onlyQuery(state);
        state.accept(2, error.requestId(), WatchResult.absent(WatchResult.Status.ERROR, ""));
        assertEquals(ClientWatchState.Change.AVAILABILITY_CHANGED, onlyEntry(state).change());

        state.stepping();
        state.paused(3, 0);
        ClientWatchState.Query restored = onlyQuery(state);
        state.accept(3, restored.requestId(), value("11", "executor-a"));
        assertEquals(ClientWatchState.Change.AVAILABILITY_CHANGED, onlyEntry(state).change());

        state.stepping();
        state.paused(4, 0);
        onlyQuery(state);
        now.addAndGet(5_000_000_000L);
        ClientWatchState.Entry timedOut = onlyEntry(state);
        assertEquals(WatchResult.Status.UNAVAILABLE, timedOut.result().status());
        assertEquals(ClientWatchState.Change.AVAILABILITY_CHANGED, timedOut.change());
    }

    @Test
    void anonymousFailureDoesNotEraseTheKnownTargetBaseline() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.add(SCORE);
        captureAndStep(state, 1, 0, "10", "executor-a");

        state.paused(2, 0);
        ClientWatchState.Query anonymousFailure = onlyQuery(state);
        state.accept(2, anonymousFailure.requestId(), WatchResult.absent(WatchResult.Status.ERROR, ""));
        state.stepping();

        state.paused(3, 0);
        ClientWatchState.Query identifiedFailure = onlyQuery(state);
        state.accept(3, identifiedFailure.requestId(), WatchResult.absent(WatchResult.Status.ERROR, "executor-a"));
        assertEquals(ClientWatchState.Change.AVAILABILITY_CHANGED, onlyEntry(state).change(),
            "the prior executor-a value remains the comparison baseline after an anonymous failure");
    }

    @Test
    void boundsCurrentPauseCapturesBySource() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.add(SCORE);
        state.paused(1, 0);
        ClientWatchState.Query first = onlyQuery(state);
        state.accept(1, first.requestId(), value("0", "executor-0"));

        for (int source = 1; source <= 256; source++) {
            state.selectSource(source);
            ClientWatchState.Query query = onlyQuery(state);
            state.accept(1, query.requestId(), value(Integer.toString(source), "executor-" + source));
        }

        state.selectSource(0);
        assertEquals(0, onlyQuery(state).sourceIndex(), "the oldest source capture is evicted within the pause");
    }

    @Test
    void evictsOldTargetsButRetainsRecentlyRecapturedTargets() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.add(SCORE);
        for (int target = 0; target < 256; target++) {
            captureAndStep(state, target + 1L, target, "0", "executor-" + target);
        }

        captureAndStep(state, 257, 0, "1", "executor-0");
        captureAndStep(state, 258, 256, "0", "executor-256");

        state.paused(259, 1);
        ClientWatchState.Query evicted = onlyQuery(state);
        state.accept(259, evicted.requestId(), value("1", "executor-1"));
        assertEquals(ClientWatchState.Change.TARGET_CHANGED, onlyEntry(state).change(),
            "executor-1 is evicted after executor-0 was refreshed and a new target was captured");
        state.stepping();

        state.paused(260, 0);
        ClientWatchState.Query retained = onlyQuery(state);
        state.accept(260, retained.requestId(), value("2", "executor-0"));
        assertEquals(ClientWatchState.Change.VALUE_CHANGED, onlyEntry(state).change());
        assertEquals("1", onlyEntry(state).previousValue());
    }

    @Test
    void selectionInvalidatesExecutorWatchButRetainsStorageAndRejectsOldReply() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.add(ENTITY);
        state.add(STORAGE);
        state.paused(7, 0);
        List<ClientWatchState.Query> initial = state.drainQueries();
        ClientWatchState.Query entity = initial.stream().filter(q -> q.spec().equals(ENTITY)).findFirst().orElseThrow();
        ClientWatchState.Query storage = initial.stream().filter(q -> q.spec().equals(STORAGE)).findFirst().orElseThrow();
        state.accept(7, entity.requestId(), value("20", "executor-a"));
        state.accept(7, storage.requestId(), value("one", "storage:example:data"));

        state.selectSource(1);
        ClientWatchState.Query replacement = onlyQuery(state);
        assertEquals(ENTITY, replacement.spec());
        assertEquals(1, replacement.sourceIndex());
        state.accept(7, entity.requestId(), value("stale", "executor-a"));
        assertNull(entry(state, ENTITY).result());
        assertEquals("one", entry(state, STORAGE).result().value());

        state.accept(7, replacement.requestId(), value("18", "executor-b"));
        assertEquals("18", entry(state, ENTITY).result().value());
        assertTrue(state.drainQueries().isEmpty(), "each valid item is queried once per selection and stop");
    }

    @Test
    void rejectsRepliesFromPriorPauseAndRemovedEntryEvenWhenRequestsArriveLate() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.add(SCORE);
        state.paused(1, 0);
        ClientWatchState.Query oldPause = onlyQuery(state);
        state.stepping();
        state.paused(2, 0);
        ClientWatchState.Query current = onlyQuery(state);
        state.accept(1, oldPause.requestId(), value("old", "executor-a"));
        assertNull(onlyEntry(state).result());

        long entryId = onlyEntry(state).id();
        state.remove(entryId);
        assertTrue(state.add(SCORE));
        ClientWatchState.Query readded = onlyQuery(state);
        state.accept(2, current.requestId(), value("removed", "executor-a"));
        assertNull(onlyEntry(state).result());
        state.accept(2, readded.requestId(), value("current", "executor-a"));
        assertEquals("current", onlyEntry(state).result().value());
    }

    @Test
    void resumeAndResetClearTargetSpecificComparisonHistory() {
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.add(SCORE);
        captureAndStep(state, 1, 0, "10", "executor-a");
        captureAndStep(state, 2, 1, "20", "executor-b");
        state.resumed();
        state.paused(3, 0);
        ClientWatchState.Query afterResume = onlyQuery(state);
        state.accept(3, afterResume.requestId(), value("11", "executor-a"));
        assertEquals(ClientWatchState.Change.INITIAL, onlyEntry(state).change());

        state.reset();
        assertTrue(state.entries().isEmpty());
        assertTrue(state.drainQueries().isEmpty());
        assertTrue(state.add(SCORE));
        state.paused(4, 1);
        ClientWatchState.Query afterReset = onlyQuery(state);
        state.accept(4, afterReset.requestId(), value("21", "executor-b"));
        assertEquals(ClientWatchState.Change.INITIAL, onlyEntry(state).change());
    }

    @Test
    void timesOutUnansweredRequestWithoutInventingAValue() {
        AtomicLong now = new AtomicLong();
        ClientWatchState state = new ClientWatchState(now::get);
        state.add(SCORE);
        state.paused(1, 0);
        onlyQuery(state);
        now.addAndGet(5_000_000_000L);

        ClientWatchState.Entry expired = onlyEntry(state);
        assertEquals(WatchResult.Status.UNAVAILABLE, expired.result().status());
        assertEquals("", expired.result().value());
        assertTrue(state.drainQueries().isEmpty());
    }

    @Test
    void displaysCompletedCapturedEntityChangeWhenTheNextExecutorIsUnavailable() {
        UUID executor = UUID.randomUUID();
        String key = entityKey(executor);
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.add(SCORE);
        state.paused(1, 0);
        ClientWatchState.Query beforeStep = onlyQuery(state);
        state.accept(1, beforeStep.requestId(), value("0", key));
        state.stepping();

        state.paused(2, 0);
        List<ClientWatchState.Query> queries = state.drainQueries();
        ClientWatchState.Query current = queries.stream().filter(query -> query.capturedEntity() == null).findFirst().orElseThrow();
        ClientWatchState.Query captured = queries.stream().filter(query -> executor.equals(query.capturedEntity())).findFirst().orElseThrow();
        assertEquals(-1, captured.sourceIndex());
        state.accept(2, current.requestId(), WatchResult.absent(WatchResult.Status.NO_EXECUTOR, "no-executor"));
        state.accept(2, captured.requestId(), value("3", key));

        ClientWatchState.Entry entry = onlyEntry(state);
        assertEquals(WatchResult.Status.NO_EXECUTOR, entry.result().status(), "current pause observation remains authoritative raw state");
        assertEquals("3", entry.displayedResult().value());
        assertEquals(ClientWatchState.Change.VALUE_CHANGED, entry.displayedChange());
        assertEquals("0", entry.displayedPreviousValue());

        state.stepping();
        state.paused(3, 0);
        ClientWatchState.Query revisited = state.drainQueries().stream()
            .filter(query -> query.capturedEntity() == null).findFirst().orElseThrow();
        state.accept(3, revisited.requestId(), value("3", key));
        assertEquals(ClientWatchState.Change.UNCHANGED, onlyEntry(state).change());
    }

    @Test
    void keepsCurrentDifferentExecutorSeparateFromTheCapturedStepChange() {
        UUID executorA = UUID.randomUUID();
        UUID executorB = UUID.randomUUID();
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.add(SCORE);
        state.paused(1, 0);
        ClientWatchState.Query beforeStep = onlyQuery(state);
        state.accept(1, beforeStep.requestId(), value("0", entityKey(executorA)));
        state.stepping();

        state.paused(2, 1);
        List<ClientWatchState.Query> queries = state.drainQueries();
        ClientWatchState.Query currentB = queries.stream().filter(query -> query.capturedEntity() == null).findFirst().orElseThrow();
        ClientWatchState.Query capturedA = queries.stream().filter(query -> executorA.equals(query.capturedEntity())).findFirst().orElseThrow();
        state.accept(2, currentB.requestId(), value("99", entityKey(executorB)));
        state.accept(2, capturedA.requestId(), value("3", entityKey(executorA)));

        ClientWatchState.Entry entry = onlyEntry(state);
        assertEquals("99", entry.result().value());
        assertEquals(entityKey(executorB), entry.result().targetKey());
        assertEquals(ClientWatchState.Change.TARGET_CHANGED, entry.change());
        assertEquals("3", entry.displayedResult().value());
        assertEquals(entityKey(executorA), entry.displayedResult().targetKey());
        assertEquals(ClientWatchState.Change.VALUE_CHANGED, entry.displayedChange());
        assertEquals("0", entry.displayedPreviousValue());
    }

    @Test
    void ignoresCapturedRepliesOnceTheirPauseEndsOrTheirWatchIsRemoved() {
        UUID executor = UUID.randomUUID();
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.add(SCORE);
        state.paused(1, 0);
        ClientWatchState.Query initial = onlyQuery(state);
        state.accept(1, initial.requestId(), value("0", entityKey(executor)));
        state.stepping();

        state.paused(2, 0);
        ClientWatchState.Query oldCaptured = state.drainQueries().stream()
            .filter(query -> executor.equals(query.capturedEntity())).findFirst().orElseThrow();
        state.paused(3, 0);
        state.accept(2, oldCaptured.requestId(), value("3", entityKey(executor)));
        assertNull(onlyEntry(state).completedStep());

        state.resumed();
        state.accept(2, oldCaptured.requestId(), value("4", entityKey(executor)));
        state.paused(4, 0);
        ClientWatchState.Query fresh = onlyQuery(state);
        state.accept(4, fresh.requestId(), value("4", entityKey(executor)));
        state.stepping();
        state.paused(5, 0);
        ClientWatchState.Query removedCaptured = state.drainQueries().stream()
            .filter(query -> executor.equals(query.capturedEntity())).findFirst().orElseThrow();
        state.remove(onlyEntry(state).id());
        state.accept(5, removedCaptured.requestId(), value("5", entityKey(executor)));
        assertTrue(state.entries().isEmpty());
    }

    @Test
    void completionTimeoutDoesNotInventACompletedValue() {
        AtomicLong now = new AtomicLong();
        UUID executor = UUID.randomUUID();
        ClientWatchState state = new ClientWatchState(now::get);
        state.add(SCORE);
        state.paused(1, 0);
        ClientWatchState.Query initial = onlyQuery(state);
        state.accept(1, initial.requestId(), value("0", entityKey(executor)));
        state.stepping();

        state.paused(2, 0);
        List<ClientWatchState.Query> queries = state.drainQueries();
        ClientWatchState.Query current = queries.stream().filter(query -> query.capturedEntity() == null).findFirst().orElseThrow();
        state.accept(2, current.requestId(), WatchResult.absent(WatchResult.Status.NO_EXECUTOR, ""));
        now.addAndGet(5_000_000_000L);

        ClientWatchState.Entry entry = onlyEntry(state);
        assertEquals(WatchResult.Status.NO_EXECUTOR, entry.result().status());
        assertEquals(WatchResult.Status.UNAVAILABLE, entry.displayedResult().status());
        assertEquals("", entry.displayedResult().value());
        assertEquals(ClientWatchState.Change.AVAILABILITY_CHANGED, entry.displayedChange());
    }

    @Test
    void storageWatchNeverIssuesCapturedEntityQueries() {
        UUID entityLookingKey = UUID.randomUUID();
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.add(STORAGE);
        state.paused(1, 0);
        ClientWatchState.Query initial = onlyQuery(state);
        state.accept(1, initial.requestId(), value("0", entityKey(entityLookingKey)));
        state.stepping();

        state.paused(2, 0);
        List<ClientWatchState.Query> queries = state.drainQueries();
        assertEquals(1, queries.size());
        assertNull(queries.getFirst().capturedEntity());
    }

    @Test
    void floatingWatchDisplaysTheSelectedExecutorWhilePendingAndAfterItsReply() {
        PauseSource pig = source("Pig", 1);
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.add(SCORE);
        state.paused(1, 0);
        state.rememberExecutors(List.of(pig));

        assertEquals(pig.entity().uuid(), onlyEntry(state).displayedExecutor());
        assertEquals("Pig", onlyEntry(state).executorName());
        ClientWatchState.Query query = onlyQuery(state);
        state.accept(1, query.requestId(), value("4", entityKey(pig.entity().uuid())));
        assertEquals(pig.entity().uuid(), onlyEntry(state).displayedExecutor());
        assertEquals("Pig", onlyEntry(state).executorName());
    }

    @Test
    void floatingWatchSwitchesItsDisplayedExecutorToTheNewSource() {
        PauseSource first = source("Pig", 1);
        PauseSource second = source("Cow", 2);
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.add(SCORE);
        state.paused(1, 0);
        state.rememberExecutors(List.of(first, second));
        ClientWatchState.Query firstQuery = onlyQuery(state);
        state.accept(1, firstQuery.requestId(), value("4", entityKey(first.entity().uuid())));

        state.selectSource(1);
        assertEquals(second.entity().uuid(), onlyEntry(state).displayedExecutor());
        assertEquals("Cow", onlyEntry(state).executorName());
        ClientWatchState.Query secondQuery = onlyQuery(state);
        state.accept(1, secondQuery.requestId(), value("8", entityKey(second.entity().uuid())));
        assertEquals(second.entity().uuid(), onlyEntry(state).displayedExecutor());
        assertEquals("Cow", onlyEntry(state).executorName());
    }

    @Test
    void completedPreviousExecutorResultKeepsItsIdentityWhenTheCurrentSourceHasNone() {
        PauseSource pig = source("Pig", 1);
        ClientWatchState state = new ClientWatchState(() -> 0);
        state.add(SCORE);
        state.paused(1, 0);
        state.rememberExecutors(List.of(pig));
        ClientWatchState.Query before = onlyQuery(state);
        state.accept(1, before.requestId(), value("4", entityKey(pig.entity().uuid())));
        state.stepping();

        state.paused(2, -1);
        List<ClientWatchState.Query> queries = state.drainQueries();
        ClientWatchState.Query current = queries.stream().filter(query -> query.capturedEntity() == null).findFirst().orElseThrow();
        ClientWatchState.Query captured = queries.stream().filter(query -> pig.entity().uuid().equals(query.capturedEntity())).findFirst().orElseThrow();
        state.accept(2, current.requestId(), WatchResult.absent(WatchResult.Status.NO_EXECUTOR, ""));
        state.accept(2, captured.requestId(), value("5", entityKey(pig.entity().uuid())));

        ClientWatchState.Entry entry = onlyEntry(state);
        assertEquals("5", entry.displayedResult().value());
        assertEquals(pig.entity().uuid(), entry.displayedExecutor());
        assertEquals("Pig", entry.executorName());
    }

    @Test
    void debuggerStateKeepsSelectedSourceWhenAuthoritativeSourcesReorder() {
        PauseSource first = source("first", 1);
        PauseSource selected = source("selected", 2);
        ClientDebuggerState state = new ClientDebuggerState(() -> 0);
        state.applyPause(snapshot(50, List.of(first, selected)));
        state.selectSource(1);
        state.applyPause(snapshot(50, List.of(selected, first)));

        assertEquals(0, state.selectedSourceIndex());
        assertEquals(selected, state.selectedSource());
    }

    private static ClientWatchState.Query onlyQuery(ClientWatchState state) {
        return state.drainQueries().getFirst();
    }

    private static ClientWatchState.Query currentQuery(ClientWatchState state) {
        return state.drainQueries().stream().filter(query -> query.capturedEntity() == null).findFirst().orElseThrow();
    }

    private static ClientWatchState.Entry onlyEntry(ClientWatchState state) {
        return state.entries().getFirst();
    }

    private static ClientWatchState.Entry entry(ClientWatchState state, WatchSpec spec) {
        return state.entries().stream().filter(entry -> entry.spec().equals(spec)).findFirst().orElseThrow();
    }

    private static void captureAndStep(ClientWatchState state, long pauseId, int sourceIndex, String capturedValue, String target) {
        state.paused(pauseId, sourceIndex);
        ClientWatchState.Query query = onlyQuery(state);
        state.accept(pauseId, query.requestId(), value(capturedValue, target));
        state.stepping();
    }

    private static WatchResult value(String value, String target) {
        return new WatchResult(WatchResult.Status.VALUE, value, target);
    }

    private static String entityKey(UUID uuid) {
        return "entity:" + uuid;
    }

    private static PauseSnapshot snapshot(long pauseId, List<PauseSource> sources) {
        SourceLocation location = new SourceLocation.Block(new BlockLocation(0, 64, 0, "overworld"));
        return new PauseSnapshot(location, CommandSnippet.plain("say test"), 0,
            List.of(new CallFrame(0, location, CommandSnippet.plain("say test"))), sources, PauseReason.BREAKPOINT, pauseId);
    }

    private static PauseSource source(String name, int x) {
        return new PauseSource(new Vec3d(x, 64, 0), 0, 0,
            new EntityRef(UUID.nameUUIDFromBytes(name.getBytes()), name), "overworld");
    }
}
