package works.nuty.codon.client.ui.layout;

import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import static works.nuty.codon.client.state.DebuggerPreferences.*;

/** Layout in Minecraft GUI pixels (already scaled by the user's GUI scale setting). */
public record DebuggerLayout(Bounds toolbar, Bounds world, Bounds inspector,
                             Bounds command, Bounds footer, boolean compact) {
    public static final int ICON_BUTTON_SIZE = 20;
    public static final int TOOLBAR_HEIGHT = ICON_BUTTON_SIZE + 4;
    /** The running idle badge is a single text row at the toolbar's corner. */
    public static final int IDLE_BADGE_HEIGHT = 18;
    public static final int ICON_BUTTON_GAP = 3;
    public static final int ICON_GROUP_GAP = 7;

    public static DebuggerLayout create(int width, int height, boolean showInspector) {
        return create(width, height, showInspector, height < 240 ? 76 : 90);
    }

    public static DebuggerLayout create(int width, int height, boolean showInspector, int requestedCommandHeight) {
        return create(width, height, showInspector, requestedCommandHeight, DEFAULT_INSPECTOR_WIDTH);
    }

    public static int maximumInspectorWidth(int width, boolean showWatches) {
        return Math.max(MIN_INSPECTOR_WIDTH, Math.min(MAX_PANEL_WIDTH,
            width - 12 - (showWatches ? MIN_WATCH_WIDTH + 8 : 164)));
    }

    public static DebuggerLayout create(int width, int height, boolean showInspector, int requestedCommandHeight,
                                        int requestedInspectorWidth) {
        return create(width, height, showInspector, requestedCommandHeight, requestedInspectorWidth, 0);
    }

    /** Keep the workspace above vanilla HUD/chat without changing retained panel sizes. */
    public static DebuggerLayout create(int width, int height, boolean showInspector, int requestedCommandHeight,
                                        int requestedInspectorWidth, int bottomInset) {
        int margin = width < 360 ? 3 : 6;
        int usableWidth = Math.max(1, width - margin * 2);
        boolean compact = width < 480 || height < 300;
        int footerHeight = 0;
        int worldY = margin + TOOLBAR_HEIGHT + 3;
        int workspaceBottom = Math.max(worldY, height - margin - Math.max(0, bottomInset));
        int minimumWorldHeight = height < 180 ? 16 : height < 300 ? 42 : 80;
        // Retain a context row, detail actions and NBT rows when the HUD takes space.
        if (bottomInset > 0) {
            if (showInspector && height >= 300) minimumWorldHeight = 116;
            // Chat may consume most of the workspace. Prefer one selectable Command
            // row over world/detail space, without extending into the vanilla HUD.
            int availableHeight = Math.max(0, workspaceBottom - worldY - 3);
            minimumWorldHeight = Math.min(minimumWorldHeight,
                Math.max(0, availableHeight - Math.min(requestedCommandHeight, 58)));
        }
        int commandHeight = Math.clamp(requestedCommandHeight, 0,
            Math.max(0, workspaceBottom - footerHeight - worldY - minimumWorldHeight - 3));
        int commandY = Math.max(worldY, workspaceBottom - footerHeight - commandHeight);
        int worldHeight = Math.max(0, commandY - worldY - 3);
        int panelWidth = showInspector ? width < 600 ? Math.min(DEFAULT_INSPECTOR_WIDTH, Math.max(144, usableWidth / 3))
            : Math.clamp(requestedInspectorWidth, MIN_INSPECTOR_WIDTH, maximumInspectorWidth(width, false)) : 0;
        // Extremely small windows use a full-width information drawer, never negative world space.
        panelWidth = Math.min(panelWidth, usableWidth);
        int inspectorSpace = panelWidth == 0 ? 0 : Math.min(usableWidth, panelWidth + 4);
        int worldWidth = usableWidth - inspectorSpace;
        return new DebuggerLayout(
            new Bounds(margin, margin, usableWidth, TOOLBAR_HEIGHT),
            new Bounds(margin + inspectorSpace, worldY, worldWidth, worldHeight),
            new Bounds(margin, worldY, panelWidth, worldHeight),
            new Bounds(margin, commandY, usableWidth, Math.max(0, Math.min(commandHeight, height - margin - commandY))),
            new Bounds(margin, height - margin - footerHeight, usableWidth, footerHeight),
            compact
        );
    }

    public Bounds idleBadgeRow() {
        return new Bounds(toolbar.x(), toolbar.y(), toolbar.width(), IDLE_BADGE_HEIGHT);
    }
}
