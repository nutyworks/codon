package works.nuty.codon.client.ui.layout;

import works.nuty.codon.client.state.DebuggerPreferences;

/** Absolute framebuffer scale, using Minecraft's GUI scale units without changing its options. */
public record UiScale(double effective, double gameScale, int width, int height) {
    public static UiScale create(DebuggerPreferences preferences, int framebufferWidth, int framebufferHeight,
                                 double gameScale, int gameWidth, int gameHeight) {
        return create(preferences, framebufferWidth, framebufferHeight, gameScale, gameWidth, gameHeight, false);
    }

    public static UiScale create(DebuggerPreferences preferences, int framebufferWidth, int framebufferHeight,
                                 double gameScale, int gameWidth, int gameHeight, boolean enforceUnicode) {
        if (preferences.uiScaleMode() == DebuggerPreferences.UiScaleMode.FOLLOW_GAME || !preferences.customUiScaleInitialized()) {
            return new UiScale(gameScale, gameScale, gameWidth, gameHeight);
        }
        // Keep the settings reachable at the existing minimum supported layout. A saved request
        // is retained when a smaller window temporarily limits the applied quarter-step scale.
        int fit = viewportLimit(framebufferWidth, framebufferHeight, enforceUnicode);
        double effective = Math.min(preferences.customUiScale(), fit) / 4.0;
        // Use Minecraft's ceiling convention so first-use mode switching preserves dimensions.
        return new UiScale(effective, gameScale, Math.max(1, (int) Math.ceil(framebufferWidth / effective)),
            Math.max(1, (int) Math.ceil(framebufferHeight / effective)));
    }

    private static int viewportLimit(int framebufferWidth, int framebufferHeight, boolean enforceUnicode) {
        int fit = (int) Math.min(DebuggerPreferences.MAX_UI_SCALE,
            Math.floor(Math.min(framebufferWidth / 320.0, framebufferHeight / 240.0) * 4));
        // Mirror Minecraft's font rounding independently of the selected game GUI scale.
        int automatic = Math.max(1, fit / 4);
        if (enforceUnicode && automatic % 2 != 0) automatic++;
        return Math.max(DebuggerPreferences.gameUiScaleRequest(automatic), fit);
    }

    /** Keep the familiar 1–4x range, expanding for the current framebuffer/game scale. */
    public static int maximumRequest(int framebufferWidth, int framebufferHeight, boolean enforceUnicode) {
        return Math.max(DebuggerPreferences.STANDARD_MAX_UI_SCALE, viewportLimit(framebufferWidth, framebufferHeight, enforceUnicode));
    }

    public double renderFactor() { return effective / gameScale; }
    public double toLocal(double gameCoordinate) { return gameCoordinate / renderFactor(); }
    public double toGame(double localCoordinate) { return localCoordinate * renderFactor(); }
}
