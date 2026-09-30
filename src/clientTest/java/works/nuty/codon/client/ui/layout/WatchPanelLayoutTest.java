package works.nuty.codon.client.ui.layout;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchPanelLayoutTest {
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
