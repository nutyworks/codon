package works.nuty.codon.client.ui;

import org.junit.jupiter.api.Test;
import works.nuty.codon.client.state.DebuggerPreferences;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DebuggerThemeTest {
    @Test
    void modalReadingSurfacesKeepContrastWhileHudHonorsOpacity() {
        var preferences = new DebuggerPreferences();
        DebuggerTheme.usePreferences(preferences);
        try {
            for (int opacity : new int[]{0, 50, 100}) {
                preferences.setBackgroundOpacity(opacity);
                assertEquals(Math.round(255 * opacity / 100f), DebuggerTheme.color(DebuggerTheme.SURFACE) >>> 24);
                assertEquals(DebuggerTheme.WORKSPACE, DebuggerTheme.modalColor(DebuggerTheme.PANEL));
                assertEquals(DebuggerTheme.SURFACE, DebuggerTheme.modalColor(DebuggerTheme.SURFACE));
                assertEquals(0x70000000, DebuggerTheme.modalColor(0x70000000));
                assertEquals(DebuggerTheme.TEXT, DebuggerTheme.foreground(DebuggerTheme.TEXT));
            }
        } finally {
            DebuggerTheme.usePreferences(new DebuggerPreferences());
        }
    }
}
