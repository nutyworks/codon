package works.nuty.codon.client.ui.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    /**
     * A Functions-tree search normalized once: terms are separated by Java whitespace (the same
     * code points {@link String#isBlank()} accepts), folded with {@link Locale#ROOT} and matched
     * literally, never as a pattern. A function matches only when every term occurs in its
     * {@code namespace:path}; no terms means no filter.
     */
    public record FunctionQuery(List<String> terms) {
        public static final FunctionQuery NONE = new FunctionQuery(List.of());

        public FunctionQuery { terms = List.copyOf(terms); }

        public static FunctionQuery parse(String text) {
            List<String> terms = new ArrayList<>();
            int start = -1;
            for (int index = 0; index < text.length(); ) {
                int codePoint = text.codePointAt(index);
                if (Character.isWhitespace(codePoint)) {
                    if (start >= 0) terms.add(text.substring(start, index).toLowerCase(Locale.ROOT));
                    start = -1;
                } else if (start < 0) start = index;
                index += Character.charCount(codePoint);
            }
            if (start >= 0) terms.add(text.substring(start).toLowerCase(Locale.ROOT));
            return terms.isEmpty() ? NONE : new FunctionQuery(terms);
        }

        public boolean active() { return !terms.isEmpty(); }

        public boolean matches(String functionId) {
            if (terms.isEmpty()) return true;
            String folded = functionId.toLowerCase(Locale.ROOT);
            for (String term : terms) if (!folded.contains(term)) return false;
            return true;
        }
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
