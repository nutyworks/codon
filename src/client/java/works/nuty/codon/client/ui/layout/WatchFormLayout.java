package works.nuty.codon.client.ui.layout;

import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;

/** Shared columns and vertical slots for all three Watch add/edit forms. */
public record WatchFormLayout(Bounds panel) {
    public static WatchFormLayout create(int width, int height) {
        int panelWidth = Math.max(1, Math.min(460, width - 16));
        int panelHeight = Math.max(1, Math.min(296, height - 12));
        return new WatchFormLayout(new Bounds((width - panelWidth) / 2, (height - panelHeight) / 2,
            panelWidth, panelHeight));
    }

    public int contentX() { return panel.x() + 8; }
    public int contentRight() { return panel.x() + panel.width() - 8; }
    public int contentWidth() { return Math.max(1, panel.width() - 16); }
    public boolean inlineSuggestions() { return panel.height() >= 280; }

    public Bounds kind(int index) {
        int available = contentWidth() - 4;
        int start = index * available / 3;
        int end = (index + 1) * available / 3;
        return new Bounds(contentX() + start + index * 2, panel.y() + 28, Math.max(1, end - start), 20);
    }

    public Bounds field(int index) {
        int y = panel.y() + (index == 0 ? 66 : inlineSuggestions() ? 136 : 112);
        return new Bounds(contentX(), y, Math.max(1, panel.width() - 76), 20);
    }

    public Bounds browse(int index) {
        return new Bounds(contentRight() - 54, field(index).y(), 54, 20);
    }

    public int labelY(int index) { return field(index).y() - 11; }
    public int errorY(int index) { return field(index).y() + 23; }
    public Bounds suggestion(int fieldIndex, int choiceIndex) {
        Bounds field = field(fieldIndex);
        return new Bounds(field.x(), field.y() + field.height() + 2 + choiceIndex * 17, field.width(), 16);
    }

    public int previewY() {
        return Math.max(panel.y() + panel.height() - 84, field(1).y() + field(1).height() + 15);
    }
    public int previewValueY() { return previewY() + 16; }
    public Bounds retry() { return new Bounds(contentRight() - 54, previewValueY() - 6, 54, 20); }
    public Bounds submit() { return new Bounds(contentRight() - 72, panel.y() + panel.height() - 35, 72, 20); }
    public Bounds close() { return new Bounds(contentRight() - 46, panel.y() + 4, 46, 18); }
    public int feedbackY() { return panel.y() + panel.height() - 49; }
    public int keysY() { return panel.y() + panel.height() - 11; }
}
