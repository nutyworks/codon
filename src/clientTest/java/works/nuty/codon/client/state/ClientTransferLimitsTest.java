package works.nuty.codon.client.state;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientTransferLimitsTest {
    private static final FunctionId FUNCTION = new FunctionId("demo", "한글/😀");

    @Test void boundsFunctionListsAndCannotPublishAnAbortedPartialTransfer() {
        var state = new ClientFunctionSourceState(() -> 0);
        state.open();
        long id = state.drainRequests().getFirst().requestId();
        var page = IntStream.range(0, 128).mapToObj(i -> new FunctionId("demo", "f" + i)).toList();
        for (int offset = 0; offset < TransferBudget.FUNCTION_LIST.entries(); offset += page.size())
            state.accept(new ClientFunctionSourceState.ListPage(id, ClientFunctionSourceState.Status.READY, offset, false, page));
        state.accept(new ClientFunctionSourceState.ListPage(id, ClientFunctionSourceState.Status.READY,
            TransferBudget.FUNCTION_LIST.entries(), true, List.of(FUNCTION)));
        assertEquals(ClientFunctionSourceState.Status.ERROR, state.listStatus());
        assertTrue(state.functions().isEmpty());
        state.accept(new ClientFunctionSourceState.ListPage(id, ClientFunctionSourceState.Status.READY, 0, true, List.of(FUNCTION)));
        assertTrue(state.functions().isEmpty());
        state.refreshList();
        id = state.drainRequests().getFirst().requestId();
        state.accept(new ClientFunctionSourceState.ListPage(id, ClientFunctionSourceState.Status.READY, 0, true, List.of(FUNCTION)));
        assertEquals(List.of(FUNCTION), state.functions());
    }

    @Test void preservesMaximumUnicodeSourceAndRejectsExtraTextOrLines() {
        var state = new ClientFunctionSourceState(() -> 0);
        List<String> lines = IntStream.range(0, 20_000).mapToObj(i -> "한😀".repeat(11) + "가나").toList();
        assertEquals(700_000, lines.stream().mapToInt(String::length).sum());
        state.select(FUNCTION);
        long id = state.drainRequests().getFirst().requestId();
        for (int offset = 0; offset < lines.size(); offset += 64) {
            int end = Math.min(lines.size(), offset + 64);
            state.accept(source(id, offset, end == lines.size(), lines.subList(offset, end)));
        }
        assertEquals(lines, state.document().lines());
        state.refreshSource();
        id = state.drainRequests().getFirst().requestId();
        for (int offset = 0; offset < lines.size(); offset += 64)
            state.accept(source(id, offset, false, lines.subList(offset, Math.min(lines.size(), offset + 64))));
        state.accept(source(id, lines.size(), true, List.of("")));
        assertEquals(ClientFunctionSourceState.Status.ERROR, state.sourceStatus());
        assertNull(state.document());

        state.refreshSource();
        id = state.drainRequests().getFirst().requestId();
        var longLines = java.util.Collections.nCopies(16, "x".repeat(16_384));
        state.accept(source(id, 0, false, longLines));
        state.accept(source(id, 16, false, longLines));
        state.accept(source(id, 32, true, longLines));
        assertEquals(ClientFunctionSourceState.Status.ERROR, state.sourceStatus());
        assertNull(state.document());
    }

    @Test void expiresTrickledSourcePagesAndRejectsZeroProgressPages() {
        AtomicLong now = new AtomicLong();
        var state = new ClientFunctionSourceState(now::get);
        state.select(FUNCTION);
        long id = state.drainRequests().getFirst().requestId();
        state.accept(source(id, 0, false, List.of("first")));
        now.set(TransferBudget.TIMEOUT_NANOS);
        state.accept(source(id, 1, true, List.of("late")));
        assertEquals(ClientFunctionSourceState.Status.ERROR, state.sourceStatus());
        assertNull(state.document());
        state.refreshSource();
        id = state.drainRequests().getFirst().requestId();
        state.accept(source(id, 0, false, List.of()));
        assertEquals(ClientFunctionSourceState.Status.ERROR, state.sourceStatus());
    }

    @Test void chargesRepeatedWatchRowsAndRetiresTheRejectedPauseTransfer() {
        var state = new ClientWatchState(() -> 0);
        var delta = change("field", "before", "after");
        var page = java.util.Collections.nCopies(32, delta);
        state.paused(1, 0);
        for (int offset = 0; offset < TransferBudget.WATCH_CHANGES.entries(); offset += 32)
            state.acceptChanges(1, offset, false, page);
        assertEquals(1, state.displayedEntries().size());
        state.acceptChanges(1, TransferBudget.WATCH_CHANGES.entries(), true, List.of(delta));
        assertTrue(state.changesRejected());
        assertTrue(state.displayedEntries().isEmpty());
        state.acceptChanges(1, 0, true, List.of(delta));
        assertTrue(state.displayedEntries().isEmpty());
        state.paused(2, 0);
        state.acceptChanges(2, 0, true, List.of(delta));
        assertEquals(1, state.displayedEntries().size());
    }

    @Test void boundsWatchCharactersAndExpiresIncompleteChanges() {
        AtomicLong now = new AtomicLong();
        var state = new ClientWatchState(now::get);
        state.paused(1, 0);
        var longDelta = change("field", "한".repeat(2048), "😀".repeat(1024));
        for (int offset = 0; offset < 1024; offset += 32)
            state.acceptChanges(1, offset, false, java.util.Collections.nCopies(32, longDelta));
        assertTrue(state.changesRejected());
        assertTrue(state.displayedEntries().isEmpty());
        state.paused(2, 0);
        state.acceptChanges(2, 0, false, List.of(change("valid", "1", "2")));
        now.set(TransferBudget.TIMEOUT_NANOS);
        assertTrue(state.displayedEntries().isEmpty());
        assertTrue(state.changesRejected());
    }

    @Test void restoresThousandsOfLongPathsWithoutPairwiseCanonicalization() {
        var state = new ClientWatchState(() -> 0);
        var definitions = IntStream.range(0, 8000).mapToObj(i ->
            new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "prefix" + i + "." + "x".repeat(95))).toList();
        assertTimeout(Duration.ofSeconds(2), () -> state.restoreDefinitions(definitions));
        assertEquals(definitions, state.definitions());
        var first = definitions.getFirst();
        var equivalent = new WatchSpec(first.kind(), first.target(), "\"prefix0\".\"" + "x".repeat(95) + "\"");
        long id = state.findId(first);
        assertEquals(id, state.findId(equivalent));
        var removed = state.removeForUndo(id);
        assertEquals(-1, state.findId(equivalent));
        assertTrue(state.restore(removed));
        assertEquals(id, state.findId(equivalent));
    }

    @Test void rebuildingTheIdentityIndexForUndoRetainsOtherWatchesLiveResults() {
        var state = new ClientWatchState(() -> 0);
        var first = new WatchSpec(WatchSpec.Kind.SCORE, "first", "");
        var second = new WatchSpec(WatchSpec.Kind.SCORE, "second", "");
        state.add(first);
        state.add(second);
        state.paused(1, 0);
        var query = state.drainQueries().stream().filter(q -> q.spec().equals(second)).findFirst().orElseThrow();
        var result = new WatchResult(WatchResult.Status.VALUE, "7", "holder");
        state.accept(1, query.requestId(), result);
        var removed = state.removeForUndo(state.findId(first));
        assertTrue(state.restore(removed));
        assertEquals(result, state.entries().getLast().result());
        assertEquals(List.of(first, second), state.definitions());
    }

    @Test void chosenHashCollisionsCannotRestorePairwiseIdentitySearches() {
        var definitions = IntStream.range(0, 8192).mapToObj(i -> {
            StringBuilder path = new StringBuilder();
            for (int bit = 0; bit < 13; bit++) path.append((i & (1 << bit)) == 0 ? "Aa" : "BB");
            return new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", path.toString());
        }).toList();
        assertEquals(1, definitions.stream().mapToInt(WatchSpec::hashCode).distinct().count());
        assertEquals(1, definitions.stream().map(WatchIdentity::key).mapToInt(WatchIdentity.Key::hashCode).distinct().count());
        var state = new ClientWatchState(() -> 0);
        assertTimeout(Duration.ofSeconds(2), () -> {
            state.restoreDefinitions(definitions);
            for (var spec : definitions) assertTrue(state.findId(spec) > 0);
            state.addAll(definitions);
            assertEquals(definitions, state.definitions());
            state.toggleAll(definitions);
            assertTrue(state.definitions().isEmpty());
        });
    }

    private static ClientFunctionSourceState.SourcePage source(long id, int offset, boolean last, List<String> lines) {
        return new ClientFunctionSourceState.SourcePage(id, ClientFunctionSourceState.Status.READY,
            FUNCTION, "file/demo", "revision", false, offset, last, lines);
    }

    private static WatchChange change(String path, String before, String after) {
        var spec = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", path);
        return new WatchChange(spec, new WatchResult(WatchResult.Status.VALUE, before, "target"),
            new WatchResult(WatchResult.Status.VALUE, after, "target"));
    }
}
