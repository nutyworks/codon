package works.nuty.codon.client.ui.layout;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchPickerLayoutTest {
    @Test void scrollViewportIncludesRowsGapsAndScrollbarButExcludesSurroundingControls() {
        for (int width : new int[]{320, 640}) {
            for (int count : new int[]{0, 2, 32}) {
                var layout = WatchPickerLayout.create(width, 240, count);
                var list = layout.list();
                assertTrue(list.contains(layout.contentX(), layout.listTop()));
                assertTrue(list.contains(layout.contentRight() - 1, layout.listBottom() - 1));
                assertTrue(list.contains(layout.scrollbarX(), layout.listTop() + 1));
                assertTrue(list.contains(layout.contentX() + 10, layout.listTop() + WatchPickerLayout.ROW_HEIGHT - 1), "Row gap remains part of the list");
                assertFalse(list.contains(layout.contentX() - 1, layout.listTop()));
                assertFalse(list.contains(layout.contentRight(), layout.listTop()));
                assertFalse(list.contains(layout.contentX(), layout.listTop() - 1));
                assertFalse(list.contains(layout.contentX(), layout.listBottom()));
                for (var control : new works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds[]{layout.search(), layout.close(),
                    layout.previous(), layout.next(), layout.up(), layout.retry()}) {
                    assertFalse(list.contains(control.x() + control.width() / 2.0, control.y() + control.height() / 2.0));
                }
            }
        }
    }

    @Test void shortResultsFitTheirRowsWithoutMovingTheSearchWhenPagesGrow() {
        for (int height : new int[]{240, 355, 400, 800}) {
            var shortPage = WatchPickerLayout.create(640, height, 2);
            var fullPage = WatchPickerLayout.create(640, height, 32);
            assertEquals(2, shortPage.visibleRows());
            assertEquals(158, shortPage.panel().height());
            assertEquals(shortPage.search(), fullPage.search());
            assertEquals(shortPage.close(), fullPage.close());
            assertEquals(8, shortPage.previous().y() - shortPage.listBottom());
            assertTrue(fullPage.panel().y() + fullPage.panel().height() <= height - 6);
            assertEquals(1, WatchPickerLayout.create(640, height, 0).visibleRows());
        }
    }

    @Test void searchRowsAndRightActionsShareEdgesAtSupportedViewportSizes() {
        for (int width : new int[]{320, 321, 569, 640, 1280}) {
            var layout = WatchPickerLayout.create(width, 240, 2);
            assertEquals(layout.contentX(), layout.search().x());
            assertEquals(layout.contentRight(), layout.search().x() + layout.search().width());
            assertEquals(layout.contentRight(), layout.row(0, false).x() + layout.row(0, false).width());
            assertEquals(layout.contentRight(), layout.close().x() + layout.close().width());
            assertEquals(layout.contentRight(), layout.retry().x() + layout.retry().width());
            assertEquals(layout.previous().y(), layout.next().y());
            assertEquals(layout.previous().y(), layout.up().y());
            assertEquals(layout.previous().y(), layout.retry().y());
            assertTrue(layout.up().x() + layout.up().width() + 4 <= layout.retry().x());
        }
    }

    @Test void textAndScrollbarLeaveRoomForTheExpansionHitboxAndRowGaps() {
        var layout = WatchPickerLayout.create(320, 240, 32);
        var row = layout.row(0, true);
        var arrow = layout.expand(0, true);
        assertTrue(row.x() + 5 + layout.textWidth(true, true) + 4 <= arrow.x());
        assertTrue(arrow.x() + arrow.width() <= row.x() + row.width());
        assertTrue(row.x() + row.width() + 4 <= layout.scrollbarX());
        assertTrue(arrow.contains(arrow.x() + 6, arrow.y() + 6));
        assertFalse(row.contains(row.x() + 10, row.y() + row.height()));
        assertEquals(row.y() + (row.height() - 9) / 2, layout.labelY(0, false, 9));
        assertEquals(row.y() + 3, layout.labelY(0, true, 9));
    }
}
