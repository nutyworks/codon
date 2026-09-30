package works.nuty.codon.client.input;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UiHideGestureTest {
    @Test void pressHidesImmediatelyAndShortReleasesToggleInBothDirections() {
        var gesture = new UiHideGesture();
        gesture.press(0);
        assertTrue(gesture.isHidden());
        gesture.release(100_000_000);
        assertTrue(gesture.isHidden());
        gesture.press(200_000_000);
        assertTrue(gesture.isHidden());
        gesture.release(300_000_000);
        assertFalse(gesture.isHidden());
    }

    @Test void thresholdRestoresVisibleStateAndShortSideStillToggles() {
        var gesture = new UiHideGesture();
        gesture.press(0);
        gesture.release(UiHideGesture.HOLD_NANOS);
        assertFalse(gesture.isHidden());
        gesture.press(1_000_000_000);
        gesture.release(1_000_000_000 + UiHideGesture.HOLD_NANOS - 1);
        assertTrue(gesture.isHidden());
    }

    @Test void longHoldRestoresAlreadyHiddenState() {
        var gesture = new UiHideGesture();
        gesture.press(0);
        gesture.release(1);
        gesture.press(1_000_000_000);
        gesture.release(2_000_000_000);
        assertTrue(gesture.isHidden());
        gesture.press(3_000_000_000L);
        gesture.release(3_000_000_001L);
        assertFalse(gesture.isHidden());
    }

    @Test void repeatAndDuplicateReleaseCannotRestartTheTimerOrToggleTwice() {
        var gesture = new UiHideGesture();
        gesture.press(0);
        gesture.press(UiHideGesture.HOLD_NANOS - 1);
        gesture.release(UiHideGesture.HOLD_NANOS);
        gesture.release(UiHideGesture.HOLD_NANOS + 1);
        assertFalse(gesture.isHidden());
        gesture.press(1_000_000_000);
        gesture.press(1_000_000_001);
        gesture.release(1_000_000_002);
        gesture.release(1_000_000_003);
        assertTrue(gesture.isHidden());
    }

    @Test void resetCancelsHeldGestureAndBlocksRepeatsUntilRelease() {
        var gesture = new UiHideGesture();
        gesture.press(0);
        gesture.reset();
        assertFalse(gesture.isHidden());
        assertTrue(gesture.awaitingRelease());
        gesture.press(10);
        assertFalse(gesture.isHidden());
        gesture.release(20);
        assertFalse(gesture.isHidden());
        assertFalse(gesture.awaitingRelease());
        gesture.press(30);
        gesture.release(40);
        assertTrue(gesture.isHidden());
    }

    @Test void resetClearsLatchedVisibilityAndUnmatchedReleaseDoesNothing() {
        var gesture = new UiHideGesture();
        gesture.release(0);
        assertFalse(gesture.isHidden());
        gesture.press(10);
        gesture.release(20);
        gesture.reset();
        gesture.release(30);
        assertFalse(gesture.isHidden());
        assertFalse(gesture.awaitingRelease());
    }
}
