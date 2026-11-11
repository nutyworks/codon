package works.nuty.codon.client.ui.layout;

import java.util.ArrayList;
import java.util.List;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;

/** An expanded Watch row: wrapped detail text above a wrapping row of actions, sized to fit the list viewport. */
public record WatchExpansionLayout(int visibleLines, List<Bounds> actions, int height) {
    public static final int LINE_HEIGHT = 11;
    public static final int BUTTON_HEIGHT = 15;
    public static final int GAP = 2;
    public static final int PADDING = 3;
    private static final WatchExpansionLayout NONE = new WatchExpansionLayout(0, List.of(), 0);

    /**
     * Actions keep their width and wrap left to right. Text keeps at least one line when anything is shown,
     * and the whole expansion never exceeds {@code maxHeight}, so the expanded row stays reachable in the
     * list; longer text scrolls inside the expansion.
     */
    public static WatchExpansionLayout create(int x, int y, int width, int lines, List<Integer> actionWidths, int maxHeight) {
        List<Bounds> actions = new ArrayList<>();
        int rowX = 0, rowY = 0;
        for (int requested : actionWidths) {
            int actionWidth = Math.max(1, Math.min(width, requested));
            if (rowX > 0 && rowX + actionWidth > width) {
                rowX = 0;
                rowY += BUTTON_HEIGHT + GAP;
            }
            actions.add(new Bounds(x + rowX, rowY, actionWidth, BUTTON_HEIGHT));
            rowX += actionWidth + GAP;
        }
        int actionsHeight = actions.isEmpty() ? 0 : rowY + BUTTON_HEIGHT;
        int fixed = PADDING * 2 + (actions.isEmpty() ? 0 : actionsHeight + GAP);
        int visible = Math.min(lines, (maxHeight - fixed) / LINE_HEIGHT);
        if (visible < Math.min(1, lines)) return NONE;
        int textHeight = visible * LINE_HEIGHT;
        int actionsTop = y + PADDING + textHeight + GAP;
        List<Bounds> placed = actions.stream()
            .map(b -> new Bounds(b.x(), actionsTop + b.y(), b.width(), b.height())).toList();
        return new WatchExpansionLayout(visible, placed, fixed + textHeight);
    }

    public int textTop(int y) { return y + PADDING; }
}
