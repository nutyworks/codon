package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import works.nuty.codon.client.config.ClientSettingsStore;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BackgroundOpacitySliderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void releaseRestoresPersistedStartValueAfterAnotherSettingSavedThePreview() {
        Path file = temporaryDirectory.resolve("opacity.json");
        var preferences = ClientSettingsStore.open(file, error -> { throw new AssertionError(error); });
        var slider = new BackgroundOpacitySlider(preferences);
        slider.position(10, 10, 108);

        assertTrue(slider.mouseClicked(mouse(114), false));
        assertTrue(slider.mouseDragged(mouse(51), -63, 0));
        assertEquals(37, preferences.backgroundOpacity());
        assertFalse(Files.exists(file), "Drag previews must not write settings every frame");

        preferences.setCommandVisible(false);
        assertEquals(37, ClientSettingsStore.open(file,
            error -> { throw new AssertionError(error); }).backgroundOpacity());
        assertTrue(slider.mouseDragged(mouse(114), 63, 0));
        assertTrue(slider.mouseReleased(mouse(114)));
        assertEquals(100, preferences.backgroundOpacity());

        var reloaded = ClientSettingsStore.open(file, error -> { throw new AssertionError(error); });
        assertEquals(100, reloaded.backgroundOpacity(), "Release must persist the final value even when it equals the start");
        assertFalse(reloaded.commandVisible(), "The setting changed during the drag must survive");
        assertFalse(slider.mouseReleased(mouse(114)), "The gesture is already committed");
    }

    private static MouseButtonEvent mouse(double x) {
        return new MouseButtonEvent(x, 18, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
    }
}
