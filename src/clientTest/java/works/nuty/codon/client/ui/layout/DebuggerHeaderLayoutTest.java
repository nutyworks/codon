package works.nuty.codon.client.ui.layout;

import org.junit.jupiter.api.Test;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import static org.junit.jupiter.api.Assertions.*;

class DebuggerHeaderLayoutTest {
    @Test void givesLongLocalizedStatusTheBrandSpaceAndKeepsMenuAndOpacitySeparate() {
        Bounds header = new Bounds(6, 6, 240, 18);
        var shortStatus = DebuggerHeaderLayout.create(header, 46, 42, 18, 4);
        var longStatus = DebuggerHeaderLayout.create(header, 46, 150, 18, 4);
        assertEquals(46, shortStatus.prefixWidth());
        assertEquals(0, longStatus.prefixWidth());
        assertTrue(longStatus.statusWidth() >= 150);
        assertEquals(shortStatus.statusWidth() + 46, longStatus.statusWidth());
        assertEquals(shortStatus.menuKeyX(), longStatus.menuKeyX());
        assertEquals(longStatus.menuKeyX() - 4, longStatus.statusX() + longStatus.statusWidth());
        assertTrue(longStatus.menuKeyX() + 18 < header.x() + header.width() - 39);
    }
}
