package works.nuty.bastion.client.ui.layout;

import works.nuty.bastion.client.ui.layout.GizmoLabelLayout.Bounds;

/** Layout in Minecraft GUI pixels (already scaled by the user's GUI scale setting). */
public record DebuggerLayout(Bounds header, Bounds controls, Bounds world, Bounds inspector,
                             Bounds command, Bounds footer, boolean compact) {
    public static DebuggerLayout create(int width, int height, boolean showInspector) {
        int margin = width < 360 ? 3 : 6;
        int usableWidth = Math.max(1, width - margin * 2);
        boolean compact = width < 480 || height < 300;
        int headerHeight = 18;
        int controlHeight = 23;
        int footerHeight = height < 220 ? 0 : 12;
        int commandHeight = height < 240 ? 32 : 52;
        int worldY = margin + headerHeight + controlHeight + 3;
        int commandY = Math.max(worldY, height - margin - footerHeight - commandHeight);
        int worldHeight = Math.max(0, commandY - worldY - 3);
        int panelWidth = showInspector ? Math.min(190, Math.max(144, usableWidth / 3)) : 0;
        // Extremely small windows use a full-width information drawer, never negative world space.
        panelWidth = Math.min(panelWidth, usableWidth);
        int worldWidth = Math.max(0, usableWidth - (panelWidth == 0 ? 0 : panelWidth + 4));
        return new DebuggerLayout(
            new Bounds(margin, margin, usableWidth, headerHeight),
            new Bounds(margin, margin + headerHeight, usableWidth, controlHeight),
            new Bounds(margin, worldY, worldWidth, worldHeight),
            new Bounds(width - margin - panelWidth, worldY, panelWidth, worldHeight),
            new Bounds(margin, commandY, usableWidth, Math.max(0, Math.min(commandHeight, height - margin - commandY))),
            new Bounds(margin, height - margin - footerHeight, usableWidth, footerHeight),
            compact
        );
    }
}
