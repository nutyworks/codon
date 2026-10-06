package works.nuty.codon.adapter;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchEditorCompactTest {
    @Test void neverSplitsASurrogatePairAtTheEllipsisBoundary() throws Exception {
        Method compact = WatchEditorReader.class.getDeclaredMethod("compact", String.class, int.class);
        compact.setAccessible(true);
        assertEquals("a".repeat(254) + "…", compact.invoke(null, "a".repeat(254) + "😀z", 256));
        assertEquals("a".repeat(255) + "…", compact.invoke(null, "a".repeat(255) + "😀z", 256));
        assertEquals("a".repeat(253) + "😀", compact.invoke(null, "a".repeat(253) + "😀", 256));
    }
}
