package works.nuty.codon.client.ui.layout;

import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.BreakpointDefinition;

/** Source-specific pointer rules in Codon's logical GUI units. */
public final class SourceInteraction {
    public static final int MIN_TREE_WIDTH = 150;
    public static final int MIN_SOURCE_WIDTH = 300;
    private SourceInteraction() { }

    public static int treeWidth(int panelWidth, int requested) {
        return Math.clamp(requested, MIN_TREE_WIDTH, Math.max(MIN_TREE_WIDTH, panelWidth - MIN_SOURCE_WIDTH));
    }

    public static boolean markerVisible(boolean enabled, boolean hovered) { return enabled || hovered; }

    public static boolean markerVisible(boolean enabled, boolean hovered, boolean editing) {
        return markerVisible(enabled, hovered) || editing;
    }

    public record HitBox(int x, int y, int width, int height) {
        public boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }

    public static @Nullable HitBox clippedRowHit(int start, int end, int rowY, int rowHeight,
                                                int viewportX, int viewportY, int viewportWidth, int viewportHeight) {
        int left = Math.max(start, viewportX), right = Math.min(end, viewportX + viewportWidth);
        int top = Math.max(rowY, viewportY), bottom = Math.min(rowY + rowHeight, viewportY + viewportHeight);
        return right > left && bottom > top ? new HitBox(left, top, right - left, bottom - top) : null;
    }

    public static boolean stageNeedsReview(BreakpointDefinition definition, String currentFingerprint) {
        return !definition.target().wholeCommand() && (definition.staleSource()
            || definition.enabled() && !definition.target().commandFingerprint().equals(currentFingerprint));
    }

    public static double horizontalMovement(double nativeX, double wheelY, boolean shift) {
        // SDL/Minecraft X is positive to the right. Vertical wheel-down is negative;
        // Shift maps that separate axis to rightward movement. Never invert X twice.
        return (nativeX != 0 ? nativeX : shift ? -wheelY : 0) * 30;
    }
}
