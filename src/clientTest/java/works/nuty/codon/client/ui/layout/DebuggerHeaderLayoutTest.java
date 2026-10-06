package works.nuty.codon.client.ui.layout;

import org.junit.jupiter.api.Test;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import static org.junit.jupiter.api.Assertions.*;

class DebuggerHeaderLayoutTest {
    /** Width of "100%" in Minecraft's default font. */
    private static final int PERCENT = 24;

    @Test void givesLongLocalizedStatusTheBrandSpaceAndKeepsMenuOpacityAndPercentSeparate() {
        // The title row spans the 283-pixel header panel at default and compact widths.
        Bounds header = new Bounds(6, 6, 283, 18);
        var shortStatus = DebuggerHeaderLayout.create(header, 46, 42, 18, PERCENT, 4);
        var longStatus = DebuggerHeaderLayout.create(header, 46, 150, 18, PERCENT, 4);
        assertEquals(46, shortStatus.prefixWidth());
        assertEquals(0, longStatus.prefixWidth());
        assertTrue(longStatus.statusWidth() >= 150);
        assertEquals(shortStatus.statusWidth() + 46, longStatus.statusWidth());
        assertEquals(shortStatus.menuKeyX(), longStatus.menuKeyX());
        assertEquals(longStatus.menuKeyX() - 4, longStatus.statusX() + longStatus.statusWidth());
        assertEquals(longStatus.menuKeyX() + 18 + 4, longStatus.sliderX());
        assertEquals(longStatus.sliderX() + DebuggerHeaderLayout.SLIDER_WIDTH, longStatus.percentX());
        assertTrue(longStatus.percentX() + PERCENT < header.x() + header.width());
    }

    @Test void reservesThePercentageWithoutShrinkingTheStatusRoomOfTheFormerHeader() {
        // The former 240-pixel row left 162 pixels for the brand and status beside a 34-pixel slider.
        var widened = DebuggerHeaderLayout.create(new Bounds(6, 6, 283, 18), 46, 162, 18, PERCENT, 4);
        assertEquals(0, widened.prefixWidth());
        assertTrue(widened.statusWidth() >= 162);
    }

    @Test void keepsPercentSliderMenuKeyAndStatusOrderedAcrossHeaderWidths() {
        for (int width = 120; width <= 283; width++) {
            Bounds header = new Bounds(3, 3, width, 18);
            var layout = DebuggerHeaderLayout.create(header, 46, 100, 14, PERCENT, 4);
            String at = " at header width " + width;
            assertEquals(layout.sliderX() + DebuggerHeaderLayout.SLIDER_WIDTH, layout.percentX(), "slider meets percent" + at);
            assertTrue(layout.percentX() + PERCENT < header.x() + header.width(), "percent stays inside" + at);
            assertEquals(layout.sliderX(), layout.menuKeyX() + 14 + 4, "menu key clears slider" + at);
            assertEquals(layout.menuKeyX() - 4, layout.statusX() + layout.statusWidth(), "status clears menu key" + at);
            assertTrue(layout.statusX() >= header.x() + 7 && layout.statusWidth() > 0, "status keeps room" + at);
        }
    }
}
