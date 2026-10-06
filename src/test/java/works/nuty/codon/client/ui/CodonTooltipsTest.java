package works.nuty.codon.client.ui;

import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.BelowOrAboveWidgetTooltipPositioner;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodonTooltipsTest {
    @Test void mousePlacementFitsAllViewportEdges() {
        var positioner = CodonTooltips.withinViewport(DefaultTooltipPositioner.INSTANCE);
        for (int[] viewport : new int[][]{{320, 240}, {853, 640}, {200, 160}})
            for (int x : new int[]{0, viewport[0] - 1}) for (int y : new int[]{0, viewport[1] - 1}) {
                int width = CodonTooltips.wrapWidth(viewport[0]), height = 72;
                var point = positioner.positionTooltip(viewport[0], viewport[1], x, y, width, height);
                assertTrue(point.x() >= 4 && point.x() + width <= viewport[0] - 4);
                assertTrue(point.y() >= 4 && point.y() + height <= viewport[1] - 4);
            }
    }
    @Test void keyboardTooltipAboveTallNearTopWidgetCannotEscapeTheViewport() {
        var preferred = new BelowOrAboveWidgetTooltipPositioner(new ScreenRectangle(280, 5, 32, 215));
        var point = CodonTooltips.withinViewport(preferred).positionTooltip(320, 240, 280, 220, 240, 80);
        assertEquals(4, point.y());
        assertTrue(point.x() >= 4 && point.x() + 240 <= 316);
    }
    @Test void narrowTooltipReservesBorderSpace() {
        assertEquals(184, CodonTooltips.wrapWidth(200));
        assertEquals(240, CodonTooltips.wrapWidth(320));
        assertEquals(240, CodonTooltips.wrapWidth(1920));
    }
    private static final long MS = 1_000_000L;

    /** Asks once per 16 ms frame, as a render loop would while the pointer rests on the region. */
    private static boolean hover(HoverDelay delay, Object region, long fromMs, long toMs) {
        boolean shown = false;
        for (long at = fromMs; at <= toMs; at += 16) shown = delay.elapsed(region, at * MS);
        return shown;
    }
    @Test void directTooltipWaitsForTheButtonDelayAndRestartsAfterTheRegionIsLeft() {
        var delay = new HoverDelay();
        assertFalse(hover(delay, "region", 0, 320), "Still inside the delay");
        assertTrue(hover(delay, "region", 336, 400), "Shown once the delay has passed");
        // No frame asked for longer than the frame gap: the pointer left, so the next hover starts over.
        assertFalse(hover(delay, "region", 900, 1_000));
        assertTrue(hover(delay, "region", 1_016, 1_300));
    }
    @Test void eachDirectTooltipRegionHasItsOwnTimer() {
        var delay = new HoverDelay();
        assertTrue(hover(delay, "first", 0, 400));
        assertFalse(hover(delay, "second", 416, 416), "A different region starts its own delay");
        assertTrue(hover(delay, "first", 432, 432), "Another region does not disturb the first");
    }
}
