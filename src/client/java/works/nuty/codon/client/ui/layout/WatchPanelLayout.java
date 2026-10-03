package works.nuty.codon.client.ui.layout;

import java.util.List;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import static works.nuty.codon.client.state.DebuggerPreferences.*;

/** Watches occupy the free upper-right area, down to the command panel, without covering controls. */
public final class WatchPanelLayout {
    private WatchPanelLayout() { }

    public static boolean stackedValues(int panelWidth) { return panelWidth < 260; }
    public static int valueWidth(int panelWidth) { return Math.max(0, panelWidth - 18); }

    /** Both name and value inspection use the full row; management lives in the context menu. */
    public static List<Bounds> inspectionBounds(Bounds panel, int rowY, int rowHeight) {
        int nameWidth = Math.max(1, panel.width() - 10);
        if (!stackedValues(panel.width()))
            return List.of(new Bounds(panel.x() + 4, rowY, nameWidth, rowHeight - 1));
        return List.of(new Bounds(panel.x() + 4, rowY, nameWidth, 17),
            new Bounds(panel.x() + 7, rowY + 17, valueWidth(panel.width()), rowHeight - 18));
    }

    /** Rows reserve extra height only when their scope line is visible. */
    public static final class Rows {
        private final int[] boundaries;

        public Rows(List<Boolean> singleLine) {
            this(singleLine, java.util.Collections.nCopies(singleLine.size(), 0));
        }

        public Rows(List<Boolean> singleLine, List<Integer> topMargins) {
            this(singleLine, topMargins, java.util.Collections.nCopies(singleLine.size(), false));
        }

        /** Compact entries use a separate value line; passive group headings stay short. */
        public Rows(List<Boolean> singleLine, List<Integer> topMargins, List<Boolean> stackedValues) {
            boundaries = new int[singleLine.size() + 1];
            for (int row = 0; row < singleLine.size(); row++)
                boundaries[row + 1] = boundaries[row] + topMargins.get(row)
                    + (stackedValues.get(row) ? 32 : singleLine.get(row) ? 18 : 28);
        }

        public int height(int from, int to) { return boundaries[to] - boundaries[from]; }

        public int visibleEnd(int offset, int viewportHeight) {
            int end = offset;
            while (end < boundaries.length - 1 && height(offset, end + 1) <= viewportHeight) end++;
            return end;
        }

        private int startShowing(int row, int viewportHeight) {
            int start = row;
            while (start > 0 && height(start - 1, row + 1) <= viewportHeight) start--;
            return start;
        }

        public int maximumOffset(int viewportHeight) {
            return boundaries.length == 1 ? 0 : startShowing(boundaries.length - 2, viewportHeight);
        }

        public int reveal(int row, int offset, int viewportHeight) {
            if (row < offset) return row;
            if (row >= visibleEnd(offset, viewportHeight)) return startShowing(row, viewportHeight);
            return offset;
        }
    }

    public static Bounds available(DebuggerLayout layout, int guiWidth) {
        return available(layout, guiWidth, DEFAULT_WATCH_WIDTH);
    }

    public static int maximumWidth(DebuggerLayout layout) {
        return Math.max(0, Math.min(MAX_PANEL_WIDTH, layout.world().width() - 4));
    }

    public static Bounds available(DebuggerLayout layout, int guiWidth, int requestedWidth) {
        int margin = layout.header().x();
        int right = Math.max(margin, guiWidth - margin);
        int width = Math.min(Math.max(MIN_WATCH_WIDTH, requestedWidth), maximumWidth(layout));
        int x = Math.max(layout.world().x(), right - width);
        int headerRight = layout.header().x() + Math.max(layout.header().width(), layout.controls().width());
        int y = x >= headerRight + 4 ? layout.header().y() : layout.world().y();
        int bottom = layout.command().y() - 4;
        return new Bounds(x, y, Math.max(0, right - x), Math.max(0, bottom - y));
    }
}
