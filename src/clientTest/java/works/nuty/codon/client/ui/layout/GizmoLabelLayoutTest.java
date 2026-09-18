package works.nuty.codon.client.ui.layout;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GizmoLabelLayoutTest {
    private static final GizmoLabelLayout.Bounds VIEWPORT = new GizmoLabelLayout.Bounds(10, 20, 320, 200);

    @Test
    void placesEveryValidSourceOnceInsideViewportWithoutLabelCollisions() {
        List<GizmoLabelLayout.Anchor> anchors = List.of(
                anchor(3, 30, 40), anchor(1, 90, 42), anchor(9, 315, 25),
                anchor(-1, 50, 50), anchor(4, Double.NaN, 50), anchor(5, 50, Double.POSITIVE_INFINITY));

        List<GizmoLabelLayout.Label> labels = GizmoLabelLayout.layout(anchors, VIEWPORT, -1, false);

        assertEquals(Set.of(1, 3, 9), displayed(labels));
        assertEquals(3, labels.stream().mapToInt(label -> label.sourceIndices().size()).sum());
        assertReadableAndInBounds(labels, VIEWPORT);
    }

    @Test
    void groupsTwoScreenOverlapsRegardlessOfTheirWorldIdentity() {
        List<GizmoLabelLayout.Label> labels = GizmoLabelLayout.layout(
                List.of(anchor(7, 140, 120), anchor(2, 140, 120), anchor(8, 290, 50)),
                VIEWPORT, 7, true);

        assertEquals(2, labels.size());
        GizmoLabelLayout.Label group = labels.stream()
                .filter(label -> label.sourceIndices().contains(7)).findFirst().orElseThrow();
        assertEquals(List.of(7, 2), group.sourceIndices());
        assertEquals(Set.of(2, 7, 8), displayed(labels));
        assertReadableAndInBounds(labels, VIEWPORT);
    }

    @Test
    void keepsOverlappingAnchorsSeparateWhenThereIsRoomAndGroupingIsDisabled() {
        List<GizmoLabelLayout.Label> labels = GizmoLabelLayout.layout(
                List.of(anchor(0, 160, 110), anchor(1, 160, 110)), VIEWPORT, 1, false);

        assertEquals(2, labels.size());
        assertEquals(Set.of(0, 1), displayed(labels));
        assertTrue(labels.stream().allMatch(label -> label.sourceIndices().size() == 1));
        assertReadableAndInBounds(labels, VIEWPORT);
    }

    @Test
    void groupsAsFallbackWhenViewportCannotFitSeparateLabelsAndKeepsSelectionFirst() {
        GizmoLabelLayout.Bounds cramped = new GizmoLabelLayout.Bounds(0, 0, 20, 18);
        List<GizmoLabelLayout.Label> labels = GizmoLabelLayout.layout(
                List.of(anchor(0, 10, 10), anchor(1, 10, 10), anchor(2, 10, 10)), cramped, 2, false);

        assertEquals(1, labels.size());
        assertEquals(List.of(2, 0, 1), labels.getFirst().sourceIndices());
        assertReadableAndInBounds(labels, cramped);
    }

    @Test
    void selectedMemberKeepsItsLongLabelWidthAndAnchorWhenGrouped() {
        GizmoLabelLayout.Anchor selected = new GizmoLabelLayout.Anchor(3, 75, 125, 240);
        List<GizmoLabelLayout.Label> labels = GizmoLabelLayout.layout(
                List.of(selected, new GizmoLabelLayout.Anchor(7, 150, 125, 24)), VIEWPORT, 3, true);

        assertEquals(1, labels.size());
        GizmoLabelLayout.Label group = labels.getFirst();
        assertEquals(List.of(3, 7), group.sourceIndices());
        assertEquals(selected.x(), group.anchorX());
        assertEquals(selected.y(), group.anchorY());
        assertTrue(group.bounds().width() > selected.width(),
                "The selected name and the remaining-member count both need room");
        assertReadableAndInBounds(labels, VIEWPORT);
    }

    @Test
    void returnsEmptyWhenTheViewportCannotContainALabel() {
        assertTrue(GizmoLabelLayout.layout(List.of(anchor(0, 0, 0)), new GizmoLabelLayout.Bounds(0, 0, 50, 17), 0, true).isEmpty());
        assertTrue(GizmoLabelLayout.layout(List.of(anchor(0, 0, 0)), new GizmoLabelLayout.Bounds(0, 0, 0, 18), 0, true).isEmpty());
    }

    @Test
    void largeSameScreenClusterIsOneStableClickableGroup() {
        List<GizmoLabelLayout.Anchor> anchors = IntStream.range(0, 256)
                .mapToObj(index -> anchor(index, 160, 100)).collect(Collectors.toList());

        List<GizmoLabelLayout.Label> labels = GizmoLabelLayout.layout(anchors, VIEWPORT, 173, true);

        assertEquals(1, labels.size());
        assertEquals(256, labels.getFirst().sourceIndices().size());
        assertEquals(173, labels.getFirst().sourceIndices().getFirst());
        assertEquals(IntStream.range(0, 256).boxed().collect(Collectors.toSet()), displayed(labels));
        assertReadableAndInBounds(labels, VIEWPORT);
    }

    @Test
    void groupsTenThousandDenseAnchorsWithoutPairwiseCollisionWork() {
        List<GizmoLabelLayout.Anchor> anchors = IntStream.range(0, 10_000)
                .mapToObj(index -> anchor(index, 160, 100)).collect(Collectors.toList());

        List<GizmoLabelLayout.Label> labels = GizmoLabelLayout.layout(anchors, VIEWPORT, 9_999, true);

        assertEquals(1, labels.size());
        assertEquals(10_000, labels.getFirst().sourceIndices().size());
        assertEquals(9_999, labels.getFirst().sourceIndices().getFirst());
        assertEquals(10_000, displayed(labels).size());
        assertReadableAndInBounds(labels, VIEWPORT);
    }

    @Test
    void retainsTenThousandSeparatedAnchorsAsSeparateLabels() {
        GizmoLabelLayout.Bounds largeViewport = new GizmoLabelLayout.Bounds(0, 0, 20_000, 20_000);
        List<GizmoLabelLayout.Anchor> anchors = IntStream.range(0, 10_000)
                .mapToObj(index -> anchor(index, 100 + (index % 100) * 100, 100 + (index / 100) * 100))
                .collect(Collectors.toList());

        List<GizmoLabelLayout.Label> labels = GizmoLabelLayout.layout(anchors, largeViewport, -1, true);

        assertEquals(10_000, labels.size());
        assertEquals(10_000, displayed(labels).size());
        assertTrue(labels.stream().allMatch(label -> label.sourceIndices().size() == 1));
    }

    @Test
    void outputDoesNotDependOnInputOrderAndDoesNotExposeMutableMemberLists() {
        List<GizmoLabelLayout.Anchor> anchors = new ArrayList<>(List.of(
                anchor(3, 110, 90), anchor(1, 110, 90), anchor(8, 220, 70)));
        List<GizmoLabelLayout.Label> first = GizmoLabelLayout.layout(anchors, VIEWPORT, 3, true);
        Collections.reverse(anchors);
        List<GizmoLabelLayout.Label> reordered = GizmoLabelLayout.layout(anchors, VIEWPORT, 3, true);

        assertEquals(first, reordered);
        assertThrows(UnsupportedOperationException.class, () -> first.getFirst().sourceIndices().add(99));
    }

    private static GizmoLabelLayout.Anchor anchor(int index, double x, double y) {
        return new GizmoLabelLayout.Anchor(index, x, y, 56);
    }

    private static Set<Integer> displayed(List<GizmoLabelLayout.Label> labels) {
        return labels.stream().flatMap(label -> label.sourceIndices().stream()).collect(Collectors.toSet());
    }

    private static void assertReadableAndInBounds(List<GizmoLabelLayout.Label> labels, GizmoLabelLayout.Bounds viewport) {
        for (GizmoLabelLayout.Label label : labels) {
            GizmoLabelLayout.Bounds bounds = label.bounds();
            assertEquals(18, bounds.height());
            assertTrue(bounds.x() >= viewport.x());
            assertTrue(bounds.y() >= viewport.y());
            assertTrue(bounds.x() + bounds.width() <= viewport.x() + viewport.width());
            assertTrue(bounds.y() + bounds.height() <= viewport.y() + viewport.height());
        }
        for (int i = 0; i < labels.size(); i++) {
            for (int j = 0; j < i; j++) {
                assertFalse(overlaps(labels.get(i).bounds(), labels.get(j).bounds()));
            }
        }
    }

    private static boolean overlaps(GizmoLabelLayout.Bounds left, GizmoLabelLayout.Bounds right) {
        return left.x() < right.x() + right.width() && left.x() + left.width() > right.x()
                && left.y() < right.y() + right.height() && left.y() + left.height() > right.y();
    }
}
