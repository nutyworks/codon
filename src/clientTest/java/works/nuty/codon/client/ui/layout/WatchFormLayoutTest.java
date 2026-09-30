package works.nuty.codon.client.ui.layout;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchFormLayoutTest {
    @Test void everyRowSharesColumnsAndTypeTabsReachTheSameRightEdge() {
        for (int width : new int[]{320, 321, 427, 640, 1280}) {
            var layout = WatchFormLayout.create(width, 400);
            assertEquals(layout.contentX(), layout.kind(0).x());
            assertEquals(layout.contentRight(), layout.kind(2).x() + layout.kind(2).width());
            for (int index = 0; index < 2; index++) {
                var field = layout.field(index);
                var browse = layout.browse(index);
                assertEquals(layout.contentX(), field.x());
                assertEquals(field.y(), browse.y());
                assertEquals(field.height(), browse.height());
                assertEquals(6, browse.x() - field.x() - field.width());
                assertEquals(layout.contentRight(), browse.x() + browse.width());
            }
            for (var button : new GizmoLabelLayout.Bounds[]{layout.retry(), layout.submit(), layout.close()})
                assertEquals(layout.contentRight(), button.x() + button.width());
        }
    }

    @Test void normalSuggestionsHaveSeparationFromTheNextLabelAndPreview() {
        var layout = WatchFormLayout.create(640, 400);
        assertTrue(layout.inlineSuggestions());
        var lastFirstChoice = layout.suggestion(0, 1);
        assertTrue(lastFirstChoice.y() + lastFirstChoice.height() + 4 <= layout.labelY(1));
        var lastSecondChoice = layout.suggestion(1, 1);
        assertTrue(lastSecondChoice.y() + lastSecondChoice.height() + 4 <= layout.previewY());
    }

    @Test void retryTextSharesThePreviewBaselineAtRegularAndCompactSizes() {
        for (int height : new int[]{240, 267, 400}) {
            var layout = WatchFormLayout.create(320, height);
            assertEquals(layout.previewValueY(), layout.retry().y() + (layout.retry().height() - 9) / 2 + 1);
            assertEquals(layout.browse(1).x(), layout.retry().x());
            assertTrue(layout.errorY(1) + 9 + 3 <= layout.previewY());
            assertTrue(layout.retry().y() + layout.retry().height() + 2 <= layout.feedbackY());
        }
    }

    @Test void compactControlsLabelsAndStatusStayWithinTheSupportedViewport() {
        var layout = WatchFormLayout.create(320, 240);
        assertFalse(layout.inlineSuggestions());
        for (var bounds : new GizmoLabelLayout.Bounds[]{layout.kind(0), layout.kind(1), layout.kind(2),
            layout.field(0), layout.field(1), layout.browse(0), layout.browse(1), layout.retry(), layout.submit(), layout.close()}) {
            assertTrue(bounds.x() >= 0 && bounds.y() >= 0);
            assertTrue(bounds.x() + bounds.width() <= 320 && bounds.y() + bounds.height() <= 240);
        }
        assertTrue(layout.keysY() + 9 <= 240);
    }
}
