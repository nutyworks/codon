package works.nuty.codon.client.ui.layout;

import org.junit.jupiter.api.Test;
import works.nuty.codon.client.ui.layout.DockLayout.Section;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockLayoutTest {
    private static final List<Section> SECTIONS = List.of(
        new Section(43, 260), new Section(53, 80), new Section(44, 62), new Section(56, 116));

    @Test
    void sectionsThatFitGetTheirPreferredHeights() {
        assertArrayEquals(new int[]{260, 80, 62, 116}, DockLayout.heights(1_000, 4, SECTIONS));
    }

    @Test
    void spareHeightFillsSectionsInEqualSharesWithoutExceedingTheDock() {
        int available = 300;
        int[] heights = DockLayout.heights(available, 4, SECTIONS);
        for (int i = 0; i < heights.length; i++) {
            assertTrue(heights[i] >= SECTIONS.get(i).minimum(), "every section keeps its header and one row");
            assertTrue(heights[i] <= SECTIONS.get(i).preferred(), "no section grows past its content");
        }
        assertTrue(Arrays.stream(heights).sum() + 3 * 4 <= available, "stacked sections and gaps fit the dock");
        assertTrue(heights[2] == 62, "a short list reaches its content height");
        assertTrue(Arrays.stream(heights).sum() + 3 * 4 == available, "no height is left unused while content needs it");
    }

    @Test
    void aShortDockDropsTrailingSectionsInsteadOfOverlapping() {
        int[] heights = DockLayout.heights(150, 4, SECTIONS);
        assertArrayEquals(new int[]{44, 54, 44, 0}, heights,
            "Breakpoints, last in dock order, is dropped once its minimum no longer fits");
        assertArrayEquals(new int[]{0, 0, 0, 0}, DockLayout.heights(20, 4, SECTIONS));
    }

    @Test
    void aReserveComesFromHeightLeftAfterEveryMinimum() {
        List<Section> sections = List.of(new Section(43, 54, 260), new Section(53, 80), new Section(44, 62));
        assertArrayEquals(new int[]{43, 53, 44}, DockLayout.heights(148, 4, sections),
            "a reserve never drops a later section's minimum");
        assertArrayEquals(new int[]{43 + 54, 53, 44}, DockLayout.heights(202, 4, sections),
            "the reserve is filled before equal shares");
    }
}
