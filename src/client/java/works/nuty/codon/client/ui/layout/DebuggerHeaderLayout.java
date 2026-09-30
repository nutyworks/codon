package works.nuty.codon.client.ui.layout;

import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;

/** Reserve header controls first, then give live execution state priority over the brand. */
public record DebuggerHeaderLayout(int prefixWidth, int statusX, int statusWidth, int menuKeyX) {
    public static DebuggerHeaderLayout create(Bounds header, int prefixWidth, int statusWidth,
                                              int menuKeyWidth, int gap) {
        int left = header.x() + 7;
        int menuX = header.x() + header.width() - 45 - gap - menuKeyWidth;
        int available = Math.max(0, menuX - gap - left);
        int prefix = prefixWidth + statusWidth <= available ? prefixWidth : 0;
        return new DebuggerHeaderLayout(prefix, left + prefix, Math.max(0, available - prefix), menuX);
    }
}
