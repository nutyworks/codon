package works.nuty.codon.client.ui.layout;

/** Source-specific pointer rules in Codon's logical GUI units. */
public final class SourceInteraction {
    public static final int MIN_TREE_WIDTH = 150;
    public static final int MIN_SOURCE_WIDTH = 300;
    private SourceInteraction() { }

    public static int treeWidth(int panelWidth, int requested) {
        return Math.clamp(requested, MIN_TREE_WIDTH, Math.max(MIN_TREE_WIDTH, panelWidth - MIN_SOURCE_WIDTH));
    }

    public static boolean markerVisible(boolean enabled, boolean hovered) { return enabled || hovered; }

    public static double horizontalMovement(double nativeX, double wheelY, boolean shift) {
        // SDL/Minecraft X is positive to the right. Vertical wheel-down is negative;
        // Shift maps that separate axis to rightward movement. Never invert X twice.
        return (nativeX != 0 ? nativeX : shift ? -wheelY : 0) * 30;
    }
}
