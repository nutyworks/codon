package works.nuty.codon.client.ui.layout;

import java.util.List;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchPanelLayoutTest {
    @Test void compactInspectionCoversTheValueRightEdgeWithoutCoveringNameLineActions() {
        for (int width : new int[]{144, 240, 259}) {
            var panel = new Bounds(200, 30, width, 150);
            int rowY = 70;
            var surfaces = WatchPanelLayout.inspectionBounds(panel, rowY, 32);
            assertEquals(2, surfaces.size());
            Bounds name = surfaces.getFirst(), value = surfaces.getLast();
            assertTrue(name.contains(panel.x() + 7, rowY + 5));
            assertEquals(panel.x() + 7, value.x());
            assertEquals(WatchPanelLayout.valueWidth(width), value.width());
            assertTrue(value.contains(panel.x() + width - 12, rowY + 21), "Last value pixel accepts inspection and tooltip hover");
            assertFalse(value.contains(panel.x() + width - 11, rowY + 21), "Do not extend past the value area");
            for (int action = 0; action < 4; action++) {
                var button = new Bounds(panel.x() + width - 76 + 17 * action, rowY + 1, 16, 16);
                for (Bounds surface : surfaces) assertFalse(overlaps(surface, button), "Management buttons retain distinct hitboxes");
            }
            assertFalse(overlaps(name, value));
            assertTrue(value.y() + value.height() < rowY + 32, "No overlap with the next row");
        }
        var regular = new Bounds(200, 30, 332, 150);
        assertEquals(List.of(new Bounds(204, 70, 249, 27)), WatchPanelLayout.inspectionBounds(regular, 70, 28));
    }

    private static boolean overlaps(Bounds a, Bounds b) {
        return a.x() < b.x() + b.width() && b.x() < a.x() + a.width()
            && a.y() < b.y() + b.height() && b.y() < a.y() + a.height();
    }

    @Test void compactValuesUseTheFullLowerLineAndKeepOverflowRowsReachable() {
        var reported = DebuggerLayout.create(427, 240, true);
        assertEquals(144, reported.inspector().width(), "Reproduce the reported side panel geometry");
        assertTrue(WatchPanelLayout.stackedValues(144));
        assertTrue(WatchPanelLayout.stackedValues(240));
        assertFalse(WatchPanelLayout.stackedValues(260));
        assertFalse(WatchPanelLayout.stackedValues(332));
        assertEquals(126, WatchPanelLayout.valueWidth(144), "Previously the value shared half of a 44px line");
        var rows = new WatchPanelLayout.Rows(List.of(true, true, true, false, true),
            List.of(3, 0, 0, 0, 0), List.of(false, true, true, true, true));
        assertEquals(21, rows.height(0, 1), "Group heading remains short");
        assertEquals(32, rows.height(1, 2));
        assertEquals(2, rows.visibleEnd(0, 64));
        assertEquals(3, rows.maximumOffset(64));
        assertEquals(3, rows.reveal(4, 0, 64));
        assertEquals(5, rows.visibleEnd(3, 64));
        assertEquals(0, rows.reveal(0, 3, 64));
    }

    @Test void standaloneEntriesDoNotAddASecondLineToGroupedRows() {
        // Heading, two group members, then one standalone watch.
        var rows = new WatchPanelLayout.Rows(List.of(true, true, true, false));
        assertEquals(18, rows.height(0, 1));
        assertEquals(18, rows.height(1, 2));
        assertEquals(18, rows.height(2, 3));
        assertEquals(28, rows.height(3, 4));
        assertEquals(82, rows.height(0, 4));
        assertEquals(3, rows.visibleEnd(0, 64));
        assertEquals(1, rows.maximumOffset(64));
        assertEquals(1, rows.reveal(3, 0, 64));
        assertEquals(4, rows.visibleEnd(1, 64));
        assertEquals(0, rows.reveal(0, 1, 64));
    }

    @Test void noGroupUsesTwoLinesAndShortListsDoNotScroll() {
        var rows = new WatchPanelLayout.Rows(List.of(false, false, false));
        assertEquals(84, rows.height(0, 3));
        assertEquals(2, rows.visibleEnd(0, 64));
        assertEquals(1, rows.maximumOffset(64));
        assertEquals(0, rows.maximumOffset(84));
        assertEquals(0, new WatchPanelLayout.Rows(List.of()).maximumOffset(64));
    }

    @Test void usesTopWhitespaceWithoutCoveringHeaderOrCommands() {
        for (int width : new int[]{320, 640, 1024}) {
            var layout = DebuggerLayout.create(width, 400, true);
            var watch = WatchPanelLayout.available(layout, width);
            assertTrue(watch.x() + watch.width() <= width);
            assertTrue(watch.y() + watch.height() < layout.command().y());
            if (width >= 640) assertEquals(layout.header().y(), watch.y());
            else assertTrue(watch.y() >= layout.controls().y() + layout.controls().height());
        }
    }
}
