package works.nuty.codon.client.ui.layout;

import org.junit.jupiter.api.Test;
import works.nuty.codon.client.state.DebuggerPreferences;
import static org.junit.jupiter.api.Assertions.*;

class UiScaleTest {
    @Test void firstCustomSelectionMatchesActualGameScaleIncludingHighResolutionAndFontRounding() {
        for (int gameScale : new int[]{1, 3, 4, 6, 9}) {
            var preferences = new DebuggerPreferences();
            int framebufferWidth = gameScale == 4 ? 1281 : 320 * gameScale + 1;
            int framebufferHeight = gameScale == 4 ? 801 : 240 * gameScale + 1;
            int width = (int) Math.ceil(framebufferWidth / (double) gameScale);
            int height = (int) Math.ceil(framebufferHeight / (double) gameScale);
            var before = UiScale.create(preferences, framebufferWidth, framebufferHeight, gameScale, width, height, gameScale == 4);
            preferences.selectCustomUiScale(gameScale);
            var after = UiScale.create(preferences, framebufferWidth, framebufferHeight, gameScale, width, height, gameScale == 4);
            assertEquals(before, after, "First custom selection must not shrink or move the layout");
            assertEquals(gameScale * 4, preferences.customUiScale());
            assertTrue(preferences.customUiScaleInitialized());
        }
    }

    @Test void modeRoundTripsKeepUserRequestAndResetCapturesTheNextGameScale() {
        var preferences = new DebuggerPreferences();
        preferences.selectCustomUiScale(3);
        preferences.setCustomUiScale(9);
        preferences.setUiScaleMode(DebuggerPreferences.UiScaleMode.FOLLOW_GAME);
        preferences.selectCustomUiScale(6);
        assertEquals(9, preferences.customUiScale());
        preferences.resetUiScale();
        assertFalse(preferences.customUiScaleInitialized());
        preferences.selectCustomUiScale(2);
        assertEquals(8, preferences.customUiScale());
    }

    @Test void highResolutionRequestsAreAvailableAndRetainedAfterWindowShrinks() {
        var preferences = new DebuggerPreferences();
        preferences.selectCustomUiScale(6);
        assertEquals(24, UiScale.maximumRequest(1920, 1440, false));
        assertEquals(16, UiScale.maximumRequest(640, 480, false));
        assertEquals(2, UiScale.create(preferences, 640, 480, 2, 320, 240).effective());
        assertEquals(24, preferences.customUiScale());
        assertEquals(6, UiScale.create(preferences, 1920, 1440, 6, 320, 240).effective());
    }

    @Test void fontRoundingDoesNotCoupleCustomSizeToTheGameGuiOption() {
        var preferences = new DebuggerPreferences();
        preferences.selectCustomUiScale(4);
        preferences.setCustomUiScale(14);
        for (int gameScale : new int[]{2, 4}) {
            var scale = UiScale.create(preferences, 1280, 800, gameScale, 640, 400, true);
            assertEquals(3.5, scale.effective());
            assertEquals(366, scale.width());
            assertEquals(229, scale.height());
        }
    }

    @Test void defaultFollowsActualGameScaleIncludingAutomaticScaleAndRoundedDimensions() {
        var preferences = new DebuggerPreferences();
        for (double gameScale : new double[]{1, 2, 3, 4}) {
            var scale = UiScale.create(preferences, 1281, 801, gameScale, 641, 401);
            assertEquals(gameScale, scale.effective());
            assertEquals(1, scale.renderFactor());
            assertEquals(641, scale.width());
            assertEquals(401, scale.height());
            assertEquals(53.25, scale.toLocal(53.25));
        }
    }

    @Test void customScaleKeepsPhysicalSizeAndPointerMappingWhenGameScaleChanges() {
        var preferences = new DebuggerPreferences();
        preferences.setUiScaleMode(DebuggerPreferences.UiScaleMode.CUSTOM);
        preferences.setCustomUiScale(9);
        for (double gameScale : new double[]{1, 2, 3, 4}) {
            var scale = UiScale.create(preferences, 1280, 800, gameScale, 1280, 800);
            assertEquals(2.25, scale.effective());
            assertEquals(569, scale.width());
            assertEquals(356, scale.height());
            double physicalX = 317.5;
            assertEquals(physicalX / 2.25, scale.toLocal(physicalX / gameScale), 1e-9);
            assertEquals(physicalX / gameScale, scale.toGame(scale.toLocal(physicalX / gameScale)), 1e-9);
        }
    }

    @Test void smallWindowLimitsAppliedQuarterStepWithoutDiscardingSavedRequest() {
        var preferences = new DebuggerPreferences();
        preferences.setUiScaleMode(DebuggerPreferences.UiScaleMode.CUSTOM);
        preferences.setCustomUiScale(16);
        var small = UiScale.create(preferences, 800, 600, 2, 400, 300);
        assertEquals(2.5, small.effective());
        assertEquals(320, small.width());
        assertEquals(240, small.height());
        assertEquals(16, preferences.customUiScale());
        var large = UiScale.create(preferences, 1920, 1080, 2, 960, 540);
        assertEquals(4, large.effective());
        var tiny = UiScale.create(preferences, 200, 120, 1, 200, 120);
        assertEquals(1, tiny.effective());
        assertEquals(200, tiny.width());
        assertEquals(120, tiny.height());
    }
}
