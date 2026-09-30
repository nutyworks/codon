package works.nuty.codon.client.ui.layout;

import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;

/** Full-text viewport and footer share the same responsive bounds as native widgets. */
public record WatchDetailsLayout(Bounds panel, boolean expanded, boolean splitFooter) {
    public static WatchDetailsLayout create(int width, int height, boolean expanded) {
        int panelWidth = Math.max(1, Math.min(expanded ? 440 : 340, width - 12));
        int panelHeight = Math.max(1, Math.min(expanded ? 300 : 206, height - 12));
        return new WatchDetailsLayout(new Bounds((width - panelWidth) / 2, (height - panelHeight) / 2,
            panelWidth, panelHeight), expanded, panelWidth < (expanded ? 394 : 340));
    }

    public int footerTop() { return panel.y() + panel.height() - (splitFooter ? 52 : 28); }
    public int textBottom() { return footerTop() - 6; }
    public Bounds copyValue() { return button(8, footerTop(), 78); }
    public Bounds copyPath() { return button(90, footerTop(), 56); }
    public Bounds more() { return button(150, footerTop(), 50); }
    public Bounds edit() { return button(204, footerTop(), 50); }
    public Bounds retry() { return button(panel.width() - 136, panel.y() + panel.height() - 28, 62); }
    public Bounds close() { return button(panel.width() - 70, panel.y() + panel.height() - 28, 62); }
    private Bounds button(int x, int y, int width) { return new Bounds(panel.x() + x, y, width, 20); }
}
