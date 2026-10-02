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
}
