package works.nuty.codon.client.ui.layout;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import static org.junit.jupiter.api.Assertions.*;

class WatchDetailsLayoutTest {
    @Test void footerButtonsAndTextNeverOverlapAtMinimumReportedAndRegularWidths() {
        for (int width : new int[]{320, 427, 640}) for (boolean expanded : new boolean[]{false, true}) {
            var layout = WatchDetailsLayout.create(width, 240, expanded);
            var buttons = new ArrayList<>(List.of(layout.copyValue(), layout.copyPath(), layout.more(), layout.retry(), layout.close()));
            if (expanded) buttons.add(layout.edit());
            for (Bounds button : buttons) {
                assertTrue(button.x() >= layout.panel().x() + 8);
                assertTrue(button.x() + button.width() <= layout.panel().x() + layout.panel().width() - 8);
                assertTrue(button.y() >= layout.textBottom() + 6);
                assertTrue(button.y() + button.height() <= layout.panel().y() + layout.panel().height() - 8);
                assertEquals(20, button.height());
                for (Bounds other : buttons) if (button != other) assertFalse(overlaps(button, other));
            }
            assertTrue(layout.textBottom() > layout.panel().y() + 31 + 9, "Long values retain a scrollable text viewport");
        }
    }

    @Test void wrapsExactlyWhenTheSingleFooterRowRunsOutOfRoom() {
        assertTrue(WatchDetailsLayout.create(351, 240, false).splitFooter());
        assertFalse(WatchDetailsLayout.create(352, 240, false).splitFooter());
        assertTrue(WatchDetailsLayout.create(405, 240, true).splitFooter());
        assertFalse(WatchDetailsLayout.create(406, 240, true).splitFooter());
        var compact = WatchDetailsLayout.create(320, 240, true);
        assertEquals(compact.more().y(), compact.edit().y());
        assertEquals(compact.more().y() + 24, compact.retry().y());
    }

    private static boolean overlaps(Bounds a, Bounds b) {
        return a.x() < b.x() + b.width() && b.x() < a.x() + a.width()
            && a.y() < b.y() + b.height() && b.y() < a.y() + a.height();
    }
}
