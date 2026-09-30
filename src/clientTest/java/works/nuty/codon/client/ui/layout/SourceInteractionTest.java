package works.nuty.codon.client.ui.layout;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceInteractionTest {
    @Test void nativeHorizontalAndShiftWheelKeepTheirOwnDirection() {
        assertEquals(60, SourceInteraction.horizontalMovement(2, 0, false));
        assertEquals(-60, SourceInteraction.horizontalMovement(-2, 0, false));
        assertEquals(60, SourceInteraction.horizontalMovement(0, -2, true));
        assertEquals(-60, SourceInteraction.horizontalMovement(0, 2, true));
        assertEquals(60, SourceInteraction.horizontalMovement(2, 4, true));
        assertEquals(.3, SourceInteraction.horizontalMovement(.01, 0, false), .0001);
        assertEquals(0, SourceInteraction.horizontalMovement(0, -2, false));
    }

    @Test void splitterKeepsBothPanesUsableAndClampsOnlyTheCurrentViewport() {
        assertEquals(150, SourceInteraction.treeWidth(760, -10));
        assertEquals(460, SourceInteraction.treeWidth(760, 900));
        assertEquals(292, SourceInteraction.treeWidth(592, 410));
        assertEquals(410, SourceInteraction.treeWidth(760, 410));
    }

    @Test void onlyEnabledOrHoveredMarkersReserveSpaceAndLeavingRestoresEveryAdvance() {
        for (boolean enabled : new boolean[]{false, true}) {
            for (boolean hover : new boolean[]{false, true}) {
                boolean visible = SourceInteraction.markerVisible(enabled, hover);
                assertEquals(enabled || hover, visible);
                var layout = new SourceLineLayout(10, visible ? List.of(3) : List.of(), i -> i * 6);
                assertEquals(60 + (visible ? 20 : 0), layout.width());
                var left = new SourceLineLayout(10, enabled ? List.of(3) : List.of(), i -> i * 6);
                for (int i = 0; i <= 10; i++)
                    assertEquals(i * 6 + (enabled && i >= 3 ? 20 : 0), left.x(i));
            }
        }
    }
}
