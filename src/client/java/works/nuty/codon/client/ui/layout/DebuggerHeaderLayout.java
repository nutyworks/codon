package works.nuty.codon.client.ui.layout;

import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;

/**
 * Reserve header controls first, then give live execution state priority over the brand.
 * From the right edge the title row holds the opacity percentage, the opacity slider and the menu key.
 */
public record DebuggerHeaderLayout(int prefixWidth, int statusX, int statusWidth, int menuKeyX,
                                   int sliderX, int percentX) {
    /** A 26-pixel track between 4-pixel hit and focus insets. */
    public static final int SLIDER_WIDTH = 34;
    private static final int EDGE = 5;

    public static DebuggerHeaderLayout create(Bounds header, int prefixWidth, int statusWidth,
                                              int menuKeyWidth, int percentWidth, int gap) {
        int left = header.x() + 7;
        int percentX = header.x() + header.width() - EDGE - percentWidth;
        int sliderX = percentX - SLIDER_WIDTH;
        int menuX = sliderX - gap - menuKeyWidth;
        int available = Math.max(0, menuX - gap - left);
        int prefix = prefixWidth + statusWidth <= available ? prefixWidth : 0;
        return new DebuggerHeaderLayout(prefix, left + prefix, Math.max(0, available - prefix), menuX,
            sliderX, percentX);
    }
}
