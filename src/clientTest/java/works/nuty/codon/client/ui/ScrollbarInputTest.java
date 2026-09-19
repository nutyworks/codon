package works.nuty.codon.client.ui;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ScrollbarInputTest {
    @Test void trackClickCentersThumbAndDraggingClampsOutsideTrack() {
        var input = new ScrollbarInput();
        var offset = new AtomicInteger();
        input.add("list", false, 100, 20, 100, 2, 20, 0, 40, offset::set);
        assertTrue(input.click(101, 70));
        assertEquals(20, offset.get());
        assertTrue(input.drag(300, 500));
        assertEquals(40, offset.get());
        assertTrue(input.drag(-100, -100));
        assertEquals(0, offset.get());
        assertTrue(input.release());
        assertFalse(input.drag(101, 70));
    }

    @Test void thumbGrabDoesNotJumpAndSurvivesRenderFrames() {
        var input = new ScrollbarInput();
        var offset = new AtomicInteger(20);
        input.add("path", true, 10, 20, 100, 1, 20, 20, 40, offset::set);
        assertTrue(input.click(65, 20));
        assertEquals(20, offset.get());
        input.beginFrame();
        input.add("path", true, 10, 20, 100, 1, 20, offset.get(), 40, offset::set);
        input.endFrame();
        assertTrue(input.drag(85, 100));
        assertEquals(30, offset.get());
    }

    @Test void disappearingTrackCancelsCaptureAndNonScrollableTracksIgnoreClicks() {
        var input = new ScrollbarInput();
        input.add("list", false, 100, 20, 100, 2, 20, 0, 40, ignored -> fail());
        assertFalse(input.click(50, 20));
        assertTrue(input.click(101, 25));
        input.beginFrame();
        input.endFrame();
        assertFalse(input.drag(101, 80));
        input.add("list", false, 100, 20, 100, 2, 100, 0, 0, ignored -> fail());
        assertFalse(input.click(101, 25));
    }
}
