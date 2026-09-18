package works.nuty.codon.client.ui.layout;

import org.junit.jupiter.api.Test;
import works.nuty.codon.client.state.DebuggerPreferences.InspectorTab;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InspectorLayoutTest {
    private static final Bounds AREA_360 = new Bounds(31, 47, 280, 360);

    @Test
    void laysOutEveryCollapseCombinationContiguouslyAtSupportedHeights() {
        for (int height : List.of(360, 600, 1_000)) {
            Bounds area = new Bounds(31, 47, 280, height);
            for (int mask = 0; mask < 8; mask++) {
                Set<InspectorTab> collapsed = collapsed(mask);
                InspectorLayout layout = InspectorLayout.create(area, collapsed, 4, 3, 120, 2);

                assertGeometry(layout, area, collapsed);
            }
        }
    }

    @Test
    void givesEachCollapsedSectionExactlyItsHeaderAndLeavesBottomUnusedWhenAllAreCollapsed() {
        InspectorLayout layout = InspectorLayout.create(AREA_360, EnumSet.allOf(InspectorTab.class), 1, 1, 20, 1);

        assertEquals(List.of(20, 20, 20), heights(layout));
        assertEquals(AREA_360.y() + 40, layout.stack().y());
        assertEquals(60, totalHeight(layout));
    }

    @Test
    void givesTheOnlyOpenSectionEverythingExceptTheOtherTwoHeaders() {
        for (InspectorTab open : activeSections()) {
            Set<InspectorTab> collapsed = EnumSet.allOf(InspectorTab.class);
            collapsed.remove(open);

            InspectorLayout layout = InspectorLayout.create(AREA_360, collapsed, 40, 30, 250, 20);

            assertEquals(AREA_360.height() - 40, section(layout, open).height(), open.name());
            assertGeometry(layout, AREA_360, collapsed);
        }
    }

    @Test
    void meetsContentNeedsBeforeGivingAlreadySatisfiedSectionsExtraHeight() {
        InspectorLayout layout = InspectorLayout.create(AREA_360, Set.of(), 10, 8, 140, 4);
        List<Integer> needs = List.of(flowNeed(10), sourceNeed(8, 140), stackNeed(4));

        for (int index = 0; index < 3; index++) {
            int actual = heights(layout).get(index);
            if (actual < needs.get(index)) {
                for (int other = 0; other < 3; other++) {
                    assertTrue(heights(layout).get(other) <= needs.get(other),
                            "a satisfied section must not receive spare height while section " + index + " is short");
                }
            }
        }
    }

    @Test
    void tallInspectorDoesNotRetainTheOldFlowAndSourcesHeightCaps() {
        InspectorLayout layout = InspectorLayout.create(new Bounds(0, 0, 300, 1_000), Set.of(), 16, 12, 82, 1);

        assertTrue(layout.flow().height() >= flowNeed(16));
        assertTrue(layout.sources().height() >= sourceNeed(12, 82));
        assertTrue(layout.flow().height() > 110);
        assertTrue(layout.sources().height() > 174);
    }

    @Test
    void collapsingStackReturnsItsEntireFormerRegionToTheOtherSections() {
        Bounds area = new Bounds(0, 0, 300, 600);
        InspectorLayout expanded = InspectorLayout.create(area, Set.of(), 3, 2, 120, 8);
        InspectorLayout collapsed = InspectorLayout.create(area, Set.of(InspectorTab.STACK), 3, 2, 120, 8);

        assertEquals(20, collapsed.stack().height());
        assertEquals(area.height() - 20, collapsed.flow().height() + collapsed.sources().height());
        assertTrue(collapsed.flow().height() + collapsed.sources().height()
                > expanded.flow().height() + expanded.sources().height());
        assertGeometry(collapsed, area, Set.of(InspectorTab.STACK));
    }

    @Test
    void afterShortSectionsReachTheirContentNeedSpareHeightGoesToLongFlow() {
        InspectorLayout layout = InspectorLayout.create(new Bounds(0, 0, 300, 600), Set.of(), 20, 1, 82, 1);

        assertEquals(410, layout.flow().height());
        assertEquals(sourceNeed(1, 82), layout.sources().height());
        assertEquals(stackNeed(1), layout.stack().height());
    }

    @Test
    void mergesDetailsHeightIntoTheSourcesBudget() {
        InspectorLayout layout = InspectorLayout.create(new Bounds(0, 0, 300, 600), Set.of(), 2, 2, 200, 2);

        assertTrue(layout.sources().height() >= sourceNeed(2, 200));
        assertTrue(layout.flow().height() >= flowNeed(2));
        assertTrue(layout.sources().height() >= sourceNeed(2, 200));
        assertTrue(layout.stack().height() >= stackNeed(2));
    }

    @Test
    void floorsMergedDetailsHeightAndIsDeterministic() {
        InspectorLayout first = InspectorLayout.create(AREA_360, Set.of(), 1, 1, 5, 1);
        InspectorLayout second = InspectorLayout.create(AREA_360, Set.of(), 1, 1, 5, 1);

        assertTrue(first.sources().height() >= sourceNeed(1, 20));
        assertEquals(first, second);
    }

    private static Set<InspectorTab> collapsed(int mask) {
        EnumSet<InspectorTab> result = EnumSet.noneOf(InspectorTab.class);
        InspectorTab[] sections = {InspectorTab.FLOW, InspectorTab.SOURCES, InspectorTab.STACK};
        for (int index = 0; index < sections.length; index++) {
            if ((mask & (1 << index)) != 0) {
                result.add(sections[index]);
            }
        }
        return result;
    }

    private static void assertGeometry(InspectorLayout layout, Bounds area, Set<InspectorTab> collapsed) {
        List<Bounds> sections = List.of(layout.flow(), layout.sources(), layout.stack());
        List<InspectorTab> tabs = activeSections();
        int expectedY = area.y();
        for (int index = 0; index < sections.size(); index++) {
            Bounds bounds = sections.get(index);
            assertEquals(area.x(), bounds.x());
            assertEquals(area.width(), bounds.width());
            assertEquals(expectedY, bounds.y());
            assertTrue(bounds.height() >= 0);
            if (collapsed.contains(tabs.get(index))) {
                assertEquals(20, bounds.height());
            }
            expectedY += bounds.height();
        }
        if (!collapsed.containsAll(activeSections())) {
            assertEquals(area.y() + area.height(), expectedY);
        }
    }

    private static Bounds section(InspectorLayout layout, InspectorTab tab) {
        return switch (tab) {
            case FLOW -> layout.flow();
            case SOURCES -> layout.sources();
            case DETAILS -> throw new IllegalArgumentException("DETAILS is merged into SOURCES in the tall inspector");
            case STACK -> layout.stack();
        };
    }

    private static List<Integer> heights(InspectorLayout layout) {
        return List.of(layout.flow().height(), layout.sources().height(), layout.stack().height());
    }

    private static int totalHeight(InspectorLayout layout) {
        return heights(layout).stream().mapToInt(Integer::intValue).sum();
    }

    private static int flowNeed(int rows) {
        return 43 + Math.max(1, rows) * 19;
    }

    private static int sourceNeed(int rows, int detailHeight) {
        return 30 + Math.max(1, rows) * 19 + Math.max(20, detailHeight);
    }

    private static int stackNeed(int rows) {
        return 31 + Math.max(1, rows) * 28;
    }

    private static List<InspectorTab> activeSections() {
        return List.of(InspectorTab.FLOW, InspectorTab.SOURCES, InspectorTab.STACK);
    }
}
