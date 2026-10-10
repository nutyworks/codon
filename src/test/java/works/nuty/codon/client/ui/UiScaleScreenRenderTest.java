package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.Window;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.Test;
import works.nuty.codon.client.state.DebuggerPreferences;
import works.nuty.codon.client.ui.layout.UiScale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class UiScaleScreenRenderTest {
    @Test
    void scrimCoversTheViewportBeforeTheOpaquePanelAtEitherHudOpacity() throws Exception {
        var preferences = new DebuggerPreferences();
        var client = mock(Minecraft.class);
        var window = mock(Window.class);
        when(client.getWindow()).thenReturn(window);
        when(window.getWidth()).thenReturn(1280);
        when(window.getHeight()).thenReturn(800);
        when(window.getGuiScale()).thenReturn(2);
        // Run the production render method without starting Minecraft's screen/font singleton.
        var screen = mock(UiScaleScreen.class, CALLS_REAL_METHODS);
        doReturn(preferences).when(screen).uiPreferences();
        doReturn(UiScale.create(preferences, 1280, 800, 2, 640, 400, false)).when(screen).uiScale();
        screen.width = 640;
        screen.height = 400;
        field(Screen.class, screen, "minecraft", client);
        field(Screen.class, screen, "font", mock(Font.class));
        field(Screen.class, screen, "renderables", List.of());
        field(UiScaleScreen.class, screen, "left", 165);
        field(UiScaleScreen.class, screen, "top", 91);
        field(UiScaleScreen.class, screen, "panelWidth", 310);
        field(UiScaleScreen.class, screen, "buttons", Map.of("follow", mock(DebuggerButton.class),
            "custom", mock(DebuggerButton.class), "minus", mock(DebuggerButton.class), "plus", mock(DebuggerButton.class)));
        DebuggerTheme.usePreferences(preferences);
        try {
            for (int opacity : new int[]{0, 100}) {
                preferences.setBackgroundOpacity(opacity);
                var graphics = mock(GuiGraphicsExtractor.class);
                screen.extractRenderState(graphics, -1, -1, 0);
                var order = inOrder(graphics);
                order.verify(graphics).fill(0, 0, 640, 400, 0x70000000);
                order.verify(graphics).fill(165, 91, 475, 309, DebuggerTheme.WORKSPACE);
                assertEquals(opacity, preferences.backgroundOpacity());
            }
        } finally { DebuggerTheme.usePreferences(new DebuggerPreferences()); }
    }

    private static void field(Class<?> owner, Object target, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
