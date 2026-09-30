package works.nuty.codon.client.ui.layout;

import works.nuty.codon.client.state.DebuggerPreferences;

/** Absolute framebuffer scale, using Minecraft's GUI scale units without changing its options. */
public record UiScale(double effective, double gameScale, int width, int height) {
    public static UiScale create(DebuggerPreferences preferences, int framebufferWidth, int framebufferHeight,
                                 double gameScale, int gameWidth, int gameHeight) {
        if (preferences.uiScaleMode() == DebuggerPreferences.UiScaleMode.FOLLOW_GAME) {
            return new UiScale(gameScale, gameScale, gameWidth, gameHeight);
        }
        // Keep the settings reachable at the existing minimum supported layout. A saved request
        // is retained when a smaller window temporarily limits the applied quarter-step scale.
        int fit = Math.max(4, (int) Math.floor(Math.min(framebufferWidth / 320.0, framebufferHeight / 240.0) * 4));
        double effective = Math.min(preferences.customUiScale(), fit) / 4.0;
        return new UiScale(effective, gameScale, Math.max(1, (int) Math.floor(framebufferWidth / effective)),
            Math.max(1, (int) Math.floor(framebufferHeight / effective)));
    }

    public double renderFactor() { return effective / gameScale; }
    public double toLocal(double gameCoordinate) { return gameCoordinate / renderFactor(); }
    public double toGame(double localCoordinate) { return localCoordinate * renderFactor(); }
}
