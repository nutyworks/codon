package works.nuty.codon.client.ui.layout;

import works.nuty.codon.client.state.DebuggerPreferences.InspectorTab;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;

import java.util.Set;

/** Content-aware allocation for the tall inspector. Collapsed headers never absorb spare space. */
public record InspectorLayout(Bounds flow, Bounds sources, Bounds stack) {
    public static InspectorLayout create(Bounds area, Set<InspectorTab> collapsed,
                                         int flowRows, int sourceRows, int detailHeight, int stackRows) {
        InspectorTab[] sections = { InspectorTab.FLOW, InspectorTab.SOURCES, InspectorTab.STACK };
        int normalizedDetailHeight = Math.max(20, detailHeight);
        int[] preferred = { 43 + Math.max(1, flowRows) * 19,
            30 + Math.max(1, sourceRows) * 19 + normalizedDetailHeight,
            31 + Math.max(1, stackRows) * 28 };
        int[] heights = { 62, 49 + Math.min(82, normalizedDetailHeight), 59 };
        boolean[] expanded = new boolean[3];
        int remaining = area.height();
        for (int i = 0; i < 3; i++) {
            expanded[i] = !collapsed.contains(sections[i]);
            if (!expanded[i]) preferred[i] = heights[i] = 20;
            remaining -= heights[i];
        }
        // The tall layout has >=360px, so every open section can retain at least one row.
        if (remaining < 0) throw new IllegalArgumentException("Inspector is too short for its headers and rows");
        while (remaining > 0) {
            int needy = 0;
            for (int i = 0; i < 3; i++) if (heights[i] < preferred[i]) needy++;
            if (needy == 0) break;
            int share = Math.max(1, remaining / needy);
            for (int i = 0; i < 3 && remaining > 0; i++) {
                int add = Math.min(remaining, Math.min(share, preferred[i] - heights[i]));
                heights[i] += add;
                remaining -= add;
            }
        }
        // Once all content fits, share the viewport among open sections.
        int receivers = 0;
        for (boolean isExpanded : expanded) if (isExpanded) receivers++;
        for (int i = 0; i < 3; i++) {
            if (!expanded[i]) continue;
            int add = remaining / receivers--;
            heights[i] += add;
            remaining -= add;
        }
        Bounds[] bounds = new Bounds[3];
        int y = area.y();
        for (int i = 0; i < 3; i++) {
            bounds[i] = new Bounds(area.x(), y, area.width(), heights[i]);
            y += heights[i];
        }
        return new InspectorLayout(bounds[0], bounds[1], bounds[2]);
    }
}
