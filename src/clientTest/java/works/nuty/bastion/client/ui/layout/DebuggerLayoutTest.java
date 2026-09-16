package works.nuty.bastion.client.ui.layout;

import org.junit.jupiter.api.Test;
import works.nuty.bastion.client.ui.layout.GizmoLabelLayout.Bounds;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebuggerLayoutTest {
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
