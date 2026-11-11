package works.nuty.codon.client.ui.layout;

import java.util.List;

/**
 * Height allocation for the dock's stacked sections, in dock order. Every section that fits gets its
 * minimum; a section's reserve (such as an expanded NBT tree's first rows) is then taken from what is
 * left, and remaining height fills sections toward their preferred heights in equal shares. A section
 * whose minimum no longer fits gets zero, so a short dock drops trailing sections instead of overlapping.
 */
public final class DockLayout {
    public record Section(int minimum, int reserve, int preferred) {
        public Section {
            if (minimum < 0 || reserve < 0 || preferred < minimum) throw new IllegalArgumentException("Invalid dock section heights");
        }

        public Section(int minimum, int preferred) { this(minimum, 0, preferred); }
    }

    private DockLayout() { }

    public static int[] heights(int available, int gap, List<Section> sections) {
        int[] heights = new int[sections.size()];
        int remaining = Math.max(0, available);
        int placed = 0;
        for (int i = 0; i < sections.size(); i++) {
            int need = sections.get(i).minimum() + (placed > 0 ? gap : 0);
            if (need > remaining) break;
            heights[i] = sections.get(i).minimum();
            remaining -= need;
            placed++;
        }
        for (int i = 0; i < placed && remaining > 0; i++) {
            Section section = sections.get(i);
            int add = Math.min(remaining, Math.max(0, Math.min(section.reserve(), section.preferred() - heights[i])));
            heights[i] += add;
            remaining -= add;
        }
        while (remaining > 0) {
            int needy = 0;
            for (int i = 0; i < placed; i++) if (heights[i] < sections.get(i).preferred()) needy++;
            if (needy == 0) break;
            int share = Math.max(1, remaining / needy);
            for (int i = 0; i < placed && remaining > 0; i++) {
                int add = Math.min(remaining, Math.min(share, sections.get(i).preferred() - heights[i]));
                heights[i] += add;
                remaining -= add;
            }
        }
        return heights;
    }
}
