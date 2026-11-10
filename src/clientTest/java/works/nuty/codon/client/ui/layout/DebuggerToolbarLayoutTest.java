package works.nuty.codon.client.ui.layout;

import org.junit.jupiter.api.Test;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebuggerToolbarLayoutTest {
    private static final int PILL = 120;
    private static final int TOGGLE = 50;
    private static final int TOGGLE_COMPACT = 26;
    private static final int VIEW = 40;

    @Test
    void wideToolbarKeepsTheFullPillAndToggleLabelAndEndsWithHelp() {
        var toolbar = DebuggerLayout.create(640, 360, true).toolbar();
        var layout = DebuggerToolbarLayout.create(toolbar, 4, PILL, TOGGLE, TOGGLE_COMPACT, VIEW);
        assertEquals(PILL, layout.pill().width());
        assertEquals(TOGGLE, layout.breakpoints().width());
        assertFalse(layout.breakpointsCompact());
        assertEquals(toolbar.x() + toolbar.width() - DebuggerToolbarLayout.INSET, right(layout.help()));
        // Left to right: controls, pill, Breakpoints; then Keep freecam, Source, View, Help.
        assertTrue(right(layout.controls().getLast()) < layout.pill().x());
        assertTrue(right(layout.pill()) < layout.breakpoints().x());
        assertTrue(right(layout.breakpoints()) < layout.freecam().x());
        assertTrue(right(layout.freecam()) < layout.source().x());
        assertTrue(right(layout.source()) < layout.view().x());
        assertTrue(right(layout.view()) < layout.help().x());
    }

    @Test
    void narrowToolbarDropsTheToggleLabelBeforeThePillAndNeverOverlaps() {
        // 320 GUI pixels is the smallest window Minecraft and the Codon scale limit allow.
        for (int width : List.of(320, 360, 427, 480, 640, 1024)) {
            var toolbar = DebuggerLayout.create(width, 240, true).toolbar();
            var layout = DebuggerToolbarLayout.create(toolbar, 4, PILL, TOGGLE, TOGGLE_COMPACT, VIEW);
            List<Bounds> all = new ArrayList<>(layout.controls());
            all.addAll(List.of(layout.pill(), layout.breakpoints(), layout.freecam(), layout.source(),
                layout.view(), layout.help()));
            for (Bounds bounds : all) {
                if (bounds.width() == 0) continue;
                assertTrue(bounds.x() >= toolbar.x() && right(bounds) <= toolbar.x() + toolbar.width(), "inside at " + width);
                assertTrue(bounds.y() >= toolbar.y() && bounds.y() + bounds.height() <= toolbar.y() + toolbar.height());
            }
            for (int a = 0; a < all.size(); a++)
                for (int b = a + 1; b < all.size(); b++)
                    if (all.get(a).width() > 0 && all.get(b).width() > 0)
                        assertFalse(overlaps(all.get(a), all.get(b)), "separate at " + width);
            if (layout.breakpointsCompact()) assertTrue(layout.breakpoints().width() <= TOGGLE_COMPACT);
            else assertTrue(layout.pill().width() >= DebuggerToolbarLayout.MIN_PILL_WIDTH, "label only with a readable pill");
        }
        var compact = DebuggerToolbarLayout.create(DebuggerLayout.create(320, 240, true).toolbar(), 4, PILL, TOGGLE,
            TOGGLE_COMPACT, VIEW);
        assertTrue(compact.breakpointsCompact(), "320 pixels keep the count without its label");
        var full = DebuggerToolbarLayout.create(DebuggerLayout.create(320, 240, true).toolbar(), 4, PILL, TOGGLE,
            TOGGLE, VIEW);
        assertTrue(compact.pill().width() > full.pill().width(), "the compact toggle returns its room to the pill");
    }

    private static int right(Bounds bounds) { return bounds.x() + bounds.width(); }

    private static boolean overlaps(Bounds a, Bounds b) {
        return a.x() < right(b) && b.x() < right(a) && a.y() < b.y() + b.height() && b.y() < a.y() + a.height();
    }
}
