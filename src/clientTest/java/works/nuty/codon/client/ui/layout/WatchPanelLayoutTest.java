package works.nuty.codon.client.ui.layout;

import java.util.List;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchPanelLayoutTest {
    @Test void nameAndRightAlignedValueShareOneInspectionSurfaceWithoutInlineActions() {
        for (int width : new int[]{144, 220, 240, 280, 332}) {
            var panel = new Bounds(200, 30, width, 150);
            var surfaces = WatchPanelLayout.inspectionBounds(panel, 70, 28);
            assertEquals(1, surfaces.size(), "No separate lower value line or reserved action column");
            Bounds row = surfaces.getFirst();
            assertTrue(row.contains(panel.x() + 7, 75), "Name remains inspectable");
            assertTrue(row.contains(panel.x() + width - 7, 75), "Value right edge opens the same inspection");
            assertFalse(row.contains(panel.x() + width, 75), "Inspection cannot escape the panel");
            assertFalse(row.contains(panel.x() + 7, 98), "Inspection cannot reach the next row");
            assertFalse(WatchPanelLayout.stackedValues(width), "Keys stay left and values right at every supported width");
        }
    }

    @Test void compactRowsRetainScopeHeightAndRevealOverflowWithoutStackingValues() {
        var rows = new WatchPanelLayout.Rows(List.of(true, true, true, false, true), List.of(3, 0, 0, 0, 0));
        assertEquals(21, rows.height(0, 1), "Group heading includes its margin");
        assertEquals(18, rows.height(1, 2));
        assertEquals(28, rows.height(3, 4), "Standalone scope keeps its own line");
        assertEquals(3, rows.visibleEnd(0, 64));
        assertEquals(2, rows.maximumOffset(64));
        assertEquals(2, rows.reveal(4, 0, 64));
        assertEquals(5, rows.visibleEnd(2, 64));
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
