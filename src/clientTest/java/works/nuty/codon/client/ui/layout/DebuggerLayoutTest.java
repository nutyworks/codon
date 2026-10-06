package works.nuty.codon.client.ui.layout;

import org.junit.jupiter.api.Test;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebuggerLayoutTest {
    @Test
    void recentChatRetainsTheCommandRowBeforeTheWorldViewport() {
        for (boolean inspectorOpen : List.of(false, true)) {
            for (int inset : List.of(89, 98, 134)) {
                var layout = DebuggerLayout.create(427, 240, inspectorOpen, 104, 190, inset);
                assertTrue(layout.command().height() >= (inset == 134 ? 40 : 58),
                    "Recent chat keeps a marker-sized command row, compressing the path when needed");
                assertTrue(layout.command().y() + layout.command().height() <= 240 - inset);
                assertFalse(overlaps(layout.command(), layout.controls()));
                assertFalse(overlaps(layout.command(), layout.world()));
                assertFalse(overlaps(layout.command(), layout.inspector()));
            }
        }
    }

    @Test
    void recentChatRetainsSelectableCommandsAcrossTheTallLayoutBoundary() {
        for (int height : List.of(299, 300)) {
            for (boolean inspectorOpen : List.of(false, true)) {
                for (int inset : List.of(71, 89)) {
                    var layout = DebuggerLayout.create(640, height, inspectorOpen, 104, 190, inset);
                    assertTrue(layout.command().height() >= 58,
                        "The 300-pixel boundary must not remove the selected clause or markers");
                    assertTrue(layout.command().y() + layout.command().height() <= height - inset);
                    assertFalse(overlaps(layout.command(), layout.inspector()));
                }
            }
        }
    }

    @Test
    void hudInsetKeepsContextDetailsAndNbtAlongsideASelectableCommand() {
        var layout = DebuggerLayout.create(640, 300, true, 104, 190, 60);
        assertTrue(layout.inspector().height() >= 116,
            "Context row, detail actions and NBT rows remain available");
        assertTrue(layout.command().height() >= 58, "The command keeps its marker-sized row");
        assertFalse(overlaps(layout.command(), layout.inspector()));
        assertTrue(layout.command().y() + layout.command().height() <= 240);
    }

    @Test
    void reservesVanillaHudAndRecentChatWithoutChangingRequestedSizes() {
        for (Size size : List.of(new Size(320, 240), new Size(427, 240), new Size(640, 360))) {
            for (int inset : List.of(60, 80, 134)) {
                var layout = DebuggerLayout.create(size.width(), size.height(), true, 112, 190, inset);
                assertInScreen(layout.command(), size);
                assertInScreen(layout.world(), size);
                assertInScreen(layout.inspector(), size);
                assertTrue(layout.command().y() + layout.command().height() <= size.height() - inset);
                assertTrue(layout.inspector().y() + layout.inspector().height() <= size.height() - inset);
                assertFalse(overlaps(layout.command(), layout.controls()));
                assertFalse(overlaps(layout.command(), layout.world()));
                if (inset <= 80) {
                    assertTrue(layout.command().height() >= 58,
                        "Compact recent chat retains room for a selectable command row");
                    assertTrue(layout.world().height() >= 42,
                        "Compact chat keeps the context inspector's existing viewport minimum");
                }
                assertEquals(DebuggerLayout.create(size.width(), size.height(), true, 112, 190),
                    DebuggerLayout.create(size.width(), size.height(), true, 112, 190, 0),
                    "Removing the inset restores the same requested layout");
            }
        }
    }

    @Test
    void keepsMajorRegionsOnScreenAndSeparateAtSupportedGuiSizes() {
        for (Size size : List.of(new Size(320, 180), new Size(480, 270), new Size(640, 360), new Size(1024, 576))) {
            for (boolean inspectorOpen : List.of(false, true)) {
                DebuggerLayout layout = DebuggerLayout.create(size.width(), size.height(), inspectorOpen);

                assertInScreen(layout.header(), size);
                assertInScreen(layout.controls(), size);
                assertInScreen(layout.world(), size);
                assertInScreen(layout.inspector(), size);
                assertInScreen(layout.command(), size);
                assertInScreen(layout.footer(), size);

                assertFalse(overlaps(layout.world(), layout.header()));
                assertFalse(overlaps(layout.world(), layout.controls()));
                assertFalse(overlaps(layout.world(), layout.command()));
                if (inspectorOpen) {
                    assertTrue(layout.inspector().width() > 0);
                    assertFalse(overlaps(layout.world(), layout.inspector()));
                } else {
                    assertEquals(0, layout.inspector().width());
                }
            }
        }
    }

    @Test
    void expandedCommandPanelReservesWorldSpaceAndNeverCoversItsControls() {
        for (Size size : List.of(new Size(200, 120), new Size(320, 180), new Size(640, 400), new Size(1024, 576))) {
            for (int requested : List.of(36, 106, 220, 1000)) {
                DebuggerLayout layout = DebuggerLayout.create(size.width(), size.height(), true, requested);
                assertInScreen(layout.command(), size);
                assertInScreen(layout.world(), size);
                assertFalse(overlaps(layout.command(), layout.controls()));
                assertFalse(overlaps(layout.command(), layout.inspector()));
                assertFalse(overlaps(layout.command(), layout.world()));
                assertTrue(layout.world().height() >= 16, "expansion must retain a world viewport");
            }
        }
    }

    @Test
    void keepsTheSameSafetyInAVerySmallWindow() {
        Size size = new Size(200, 120);
        for (boolean inspectorOpen : List.of(false, true)) {
            DebuggerLayout layout = DebuggerLayout.create(size.width(), size.height(), inspectorOpen);

            for (Bounds bounds : List.of(layout.header(), layout.controls(), layout.world(), layout.inspector(),
                    layout.command(), layout.footer())) {
                assertInScreen(bounds, size);
            }
            assertFalse(overlaps(layout.world(), layout.header()));
            assertFalse(overlaps(layout.world(), layout.controls()));
            assertFalse(overlaps(layout.world(), layout.command()));
            assertFalse(overlaps(layout.world(), layout.inspector()));
        }
    }

    private static void assertInScreen(Bounds bounds, Size size) {
        assertTrue(bounds.x() >= 0);
        assertTrue(bounds.y() >= 0);
        assertTrue(bounds.width() >= 0);
        assertTrue(bounds.height() >= 0);
        assertTrue(bounds.x() + bounds.width() <= size.width());
        assertTrue(bounds.y() + bounds.height() <= size.height());
    }

    private static boolean overlaps(Bounds left, Bounds right) {
        return left.width() > 0 && left.height() > 0 && right.width() > 0 && right.height() > 0
                && left.x() < right.x() + right.width() && left.x() + left.width() > right.x()
                && left.y() < right.y() + right.height() && left.y() + left.height() > right.y();
    }

    private record Size(int width, int height) {
    }
}
