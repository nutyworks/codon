package works.nuty.codon.client.ui.layout;

import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;

import java.util.ArrayList;
import java.util.List;

/**
 * One toolbar row: execution controls, the pause-reason pill and the Breakpoints toggle on the left;
 * Keep freecam, Source, View and Help on the right. The right group and the controls keep their size;
 * the pill and the Breakpoints toggle share what is left, the toggle first giving up its label.
 */
public record DebuggerToolbarLayout(List<Bounds> controls, Bounds pill, Bounds breakpoints, boolean breakpointsCompact,
                                    Bounds freecam, Bounds source, Bounds view, Bounds help) {
    public static final int INSET = 3;
    /** Below this the pill keeps too little of its reason, so the Breakpoints label gives way first. */
    public static final int MIN_PILL_WIDTH = 72;

    public static DebuggerToolbarLayout create(Bounds toolbar, int controlCount, int pillWidth,
                                               int breakpointsWidth, int breakpointsCompactWidth, int viewWidth) {
        int size = DebuggerLayout.ICON_BUTTON_SIZE;
        int gap = DebuggerLayout.ICON_BUTTON_GAP;
        int group = DebuggerLayout.ICON_GROUP_GAP;
        int y = toolbar.y() + (toolbar.height() - size) / 2;
        int x = toolbar.x() + INSET;
        List<Bounds> controls = new ArrayList<>(controlCount);
        for (int index = 0; index < controlCount; index++) {
            controls.add(new Bounds(x, y, size, size));
            x += size + gap;
        }
        int start = x - gap + group;
        int right = toolbar.x() + toolbar.width() - INSET;
        Bounds help = new Bounds(right - size, y, size, size);
        Bounds view = new Bounds(help.x() - gap - viewWidth, y, viewWidth, size);
        Bounds source = new Bounds(view.x() - gap - size, y, size, size);
        Bounds freecam = new Bounds(source.x() - gap - size, y, size, size);
        int available = Math.max(0, freecam.x() - group - start);
        boolean compact = Math.min(pillWidth, MIN_PILL_WIDTH) + gap + breakpointsWidth > available;
        int toggle = Math.min(compact ? breakpointsCompactWidth : breakpointsWidth, available);
        int pill = Math.clamp(available - toggle - gap, 0, pillWidth);
        Bounds pillBounds = new Bounds(start, y, pill, size);
        int toggleX = pill == 0 ? start : start + pill + gap;
        return new DebuggerToolbarLayout(List.copyOf(controls), pillBounds, new Bounds(toggleX, y, toggle, size), compact,
            freecam, source, view, help);
    }
}
