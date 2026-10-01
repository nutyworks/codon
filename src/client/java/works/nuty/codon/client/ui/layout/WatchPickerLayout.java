package works.nuty.codon.client.ui.layout;

import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;

/** Shared drawing and hit bounds for the paged Watch chooser. */
public record WatchPickerLayout(Bounds panel, int visibleRows) {
    public static final int ROW_HEIGHT = 26;

    public static WatchPickerLayout create(int width, int height, int optionCount) {
        int panelWidth = Math.max(1, Math.min(560, width - 16));
        int maximumHeight = Math.max(1, Math.min(360, height - 12));
        int capacity = Math.max(1, (maximumHeight - 106) / ROW_HEIGHT);
        int rows = Math.clamp(optionCount, 1, capacity);
        int panelHeight = Math.min(maximumHeight, 106 + rows * ROW_HEIGHT);
        // Keep the search and title stationary as responses, filters and pages change.
        return new WatchPickerLayout(new Bounds((width - panelWidth) / 2,
            (height - maximumHeight) / 2, panelWidth, panelHeight), rows);
    }

    public int contentX() { return panel.x() + 8; }
    public int contentRight() { return panel.x() + panel.width() - 8; }
    public int contentWidth() { return Math.max(1, panel.width() - 16); }
    public int listTop() { return panel.y() + 70; }
    public int listBottom() { return listTop() + visibleRows * ROW_HEIGHT; }
    public Bounds list() { return new Bounds(contentX(), listTop(), contentWidth(), listBottom() - listTop()); }
    public Bounds search() { return new Bounds(contentX(), panel.y() + 29, contentWidth(), 20); }
    public Bounds close() { return new Bounds(contentRight() - 54, panel.y() + 4, 54, 18); }
    public Bounds previous() { return footer(contentX()); }
    public Bounds next() { return footer(contentX() + 58); }
    public Bounds up() { return footer(contentX() + 116); }
    public Bounds retry() { return footer(contentRight() - 54); }
    private Bounds footer(int x) { return new Bounds(x, panel.y() + panel.height() - 28, 54, 20); }

    public Bounds row(int index, boolean scrollable) {
        return new Bounds(contentX(), listTop() + index * ROW_HEIGHT,
            Math.max(1, contentWidth() - (scrollable ? 6 : 0)), ROW_HEIGHT - 2);
    }
    public Bounds expand(int index, boolean scrollable) {
        Bounds row = row(index, scrollable);
        return new Bounds(row.x() + row.width() - 18, row.y() + 5, 13, 13);
    }
    public int textWidth(boolean expandable, boolean scrollable) {
        return Math.max(1, row(0, scrollable).width() - 10 - (expandable ? 20 : 0));
    }
    public int labelY(int index, boolean hasDetail, int lineHeight) {
        Bounds row = row(index, false);
        return row.y() + (hasDetail ? 3 : (row.height() - lineHeight) / 2);
    }
    public int scrollbarX() { return contentRight() - 2; }
}
