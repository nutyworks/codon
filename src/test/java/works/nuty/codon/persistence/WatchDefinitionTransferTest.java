package works.nuty.codon.persistence;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.WatchSpec;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WatchDefinitionTransferTest {
    private static final WatchSpec A = new WatchSpec(WatchSpec.Kind.SCORE, "a", "");
    private static final WatchSpec B = new WatchSpec(WatchSpec.Kind.SCORE, "b", "");
    private static final WatchSpec C = new WatchSpec(WatchSpec.Kind.SCORE, "c", "");

    @Test void acceptsEmptyAndMultipleOrderedPages() {
        var empty = new WatchDefinitionTransfer();
        assertEquals(Optional.of(List.of()), empty.accept(1, 0, true, List.of()));

        var transfer = new WatchDefinitionTransfer();
        assertEquals(Optional.empty(), transfer.accept(2, 0, false, List.of(A, B)));
        assertEquals(Optional.of(List.of(A, B, C)), transfer.accept(2, 2, true, List.of(C)));
        assertFalse(transfer.isActive());
    }

    @Test void pagesPreserveEveryDefinitionWithinTheTransportBound() {
        List<WatchSpec> definitions = java.util.stream.IntStream.range(0, 100)
            .mapToObj(index -> new WatchSpec(WatchSpec.Kind.SCORE, "watch-" + index + "-" + "x".repeat(110), ""))
            .toList();
        List<List<WatchSpec>> pages = WatchDefinitions.pages(definitions);
        assertTrue(pages.size() > 1);
        assertTrue(pages.stream().allMatch(page -> WatchDefinitions.toPageJson(page).length() <= WatchDefinitions.MAX_JSON_LENGTH));
        assertEquals(definitions, pages.stream().flatMap(List::stream).toList());
    }

    @Test void rejectsBadOrderAndDuplicatePagesWithoutReturningPartialDefinitions() {
        var transfer = new WatchDefinitionTransfer();
        assertEquals(Optional.empty(), transfer.accept(3, 0, false, List.of(A)));
        assertEquals(Optional.empty(), transfer.accept(3, 2, true, List.of(B)));
        assertFalse(transfer.isActive());

        assertEquals(Optional.empty(), transfer.accept(4, 0, false, List.of(A)));
        assertEquals(Optional.empty(), transfer.accept(4, 1, true, List.of(A)));
        assertFalse(transfer.isActive());
    }

    @Test void offsetZeroReplacesAnIncompleteTransfer() {
        var transfer = new WatchDefinitionTransfer();
        assertEquals(Optional.empty(), transfer.accept(8, 0, false, List.of(A)));
        assertEquals(Optional.of(List.of(B)), transfer.accept(9, 0, true, List.of(B)));
    }

    @Test void replacementAndIncompleteTransfersNeverProduceSaves() {
        var transfer = new WatchDefinitionTransfer();
        assertEquals(Optional.empty(), transfer.accept(5, 0, false, List.of(A)));
        assertEquals(Optional.empty(), transfer.accept(6, 0, false, List.of(B)));
        assertEquals(Optional.of(List.of(B, C)), transfer.accept(6, 1, true, List.of(C)));

        assertEquals(Optional.empty(), transfer.accept(7, 0, false, List.of(A)));
        transfer.reset();
        assertFalse(transfer.isActive());
    }
}
