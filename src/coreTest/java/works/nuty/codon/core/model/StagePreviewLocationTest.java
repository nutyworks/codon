package works.nuty.codon.core.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StagePreviewLocationTest {
    @Test void checksRepresentabilityWithoutChangingTheObservedLocation() {
        for (int line : new int[]{Integer.MIN_VALUE, -1, 0})
            assertFalse(StagePreviewLocation.supported(function("demo", "function", line)));
        assertFalse(StagePreviewLocation.supported(function("n".repeat(65), "p", 1)));
        assertFalse(StagePreviewLocation.supported(function("n", "p".repeat(257), 1)));
        assertFalse(StagePreviewLocation.supported(function("n", "p\n", 1)));
        assertFalse(StagePreviewLocation.supported(new SourceLocation.Block(new BlockLocation(0, 0, 0, "d".repeat(129)))));
        assertFalse(StagePreviewLocation.supported(null));
        assertTrue(StagePreviewLocation.supported(function("n".repeat(64), "한😀".repeat(85) + "x", 1)));
        assertTrue(StagePreviewLocation.supported(function("n", "p", Integer.MAX_VALUE)));
        assertTrue(StagePreviewLocation.supported(new SourceLocation.Block(new BlockLocation(0, 0, 0, "d".repeat(128)))));
    }

    private SourceLocation function(String namespace, String path, int line) {
        return new SourceLocation.Function(new FunctionLocation(new FunctionId(namespace, path), line));
    }
}
