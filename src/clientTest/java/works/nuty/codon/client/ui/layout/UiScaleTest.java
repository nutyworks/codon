package works.nuty.codon.client.ui.layout;

import org.junit.jupiter.api.Test;
import works.nuty.codon.client.state.DebuggerPreferences;
import static org.junit.jupiter.api.Assertions.*;

class UiScaleTest {
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
            assertEquals(568, scale.width());
            assertEquals(355, scale.height());
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
