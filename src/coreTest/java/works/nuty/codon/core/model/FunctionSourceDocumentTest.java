package works.nuty.codon.core.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FunctionSourceDocumentTest {
    @Test
    void copiesSourceLinesIntoAnImmutableSnapshot() {
        List<String> mutableLines = new ArrayList<>(List.of("say one", "say two"));
        FunctionSourceDocument document = new FunctionSourceDocument(
            new FunctionId("demo", "tick"), "file/demo", "a".repeat(64), mutableLines, false
        );

        mutableLines.clear();

        assertEquals(List.of("say one", "say two"), document.lines());
        assertThrows(UnsupportedOperationException.class, () -> document.lines().add("say three"));
    }
}
