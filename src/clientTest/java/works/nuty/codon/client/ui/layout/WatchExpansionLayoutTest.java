package works.nuty.codon.client.ui.layout;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchExpansionLayoutTest {
    @Test void actionsWrapInsideTheRowAndFollowTheText() {
        var layout = WatchExpansionLayout.create(10, 100, 120, 3, List.of(70, 60, 40), 500);
        assertEquals(3, layout.visibleLines());
        var actions = layout.actions();
        int textBottom = 100 + WatchExpansionLayout.PADDING + 3 * WatchExpansionLayout.LINE_HEIGHT;
        assertEquals(textBottom + WatchExpansionLayout.GAP, actions.get(0).y(), "Actions start below the text");
        assertEquals(actions.get(0).y() + WatchExpansionLayout.BUTTON_HEIGHT + WatchExpansionLayout.GAP, actions.get(1).y(),
            "An action that does not fit wraps to the next line");
        assertEquals(actions.get(1).y(), actions.get(2).y(), "Wrapped actions share their line");
        for (var action : actions) assertTrue(action.x() >= 10 && action.x() + action.width() <= 130, "Actions stay in the row");
        for (int i = 0; i < actions.size(); i++) for (int j = i + 1; j < actions.size(); j++) {
            var a = actions.get(i);
            var b = actions.get(j);
            assertFalse(a.x() < b.x() + b.width() && b.x() < a.x() + a.width() && a.y() < b.y() + b.height() && b.y() < a.y() + a.height(),
                "Actions are disjoint");
        }
        assertEquals(actions.getLast().y() + WatchExpansionLayout.BUTTON_HEIGHT + WatchExpansionLayout.PADDING - 100, layout.height());
    }

    @Test void longTextScrollsInsideTheViewportBudget() {
        var layout = WatchExpansionLayout.create(0, 0, 200, 40, List.of(60, 60), 80);
        assertTrue(layout.height() <= 80, "The expansion never outgrows the list viewport");
        assertTrue(layout.visibleLines() >= 1 && layout.visibleLines() < 40, "Long values keep a scrolling text window");
        assertEquals(2, layout.actions().size(), "Actions remain reachable");
    }

    @Test void noRoomShowsNothingRatherThanOverflowing() {
        var layout = WatchExpansionLayout.create(0, 0, 200, 4, List.of(60), 20);
        assertEquals(0, layout.height());
        assertTrue(layout.actions().isEmpty());
    }
}
