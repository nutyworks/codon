package works.nuty.codon.client.ui.layout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Places screen-space gizmo labels without depending on the game client. */
public final class GizmoLabelLayout {
    private static final int LABEL_HEIGHT = 18;
    private static final int GAP = 4;
    private static final int PADDING = 3;
    private static final int CELL_SIZE = 32;
    private static final int MAX_EXACT_CELL_MEMBERS = 128;

    private GizmoLabelLayout() {
    }

    public record Bounds(int x, int y, int width, int height) {
        public boolean contains(double x, double y) {
            return x >= this.x && x < (double) this.x + width
                    && y >= this.y && y < (double) this.y + height;
        }
    }

    public record Anchor(int sourceIndex, double x, double y, int width) {
    }

    public record Label(List<Integer> sourceIndices, Bounds bounds, double anchorX, double anchorY) {
        public Label {
            sourceIndices = List.copyOf(sourceIndices);
        }
    }

    public static List<Label> layout(
            List<Anchor> anchors, Bounds viewport, int selectedSourceIndex, boolean groupOverlaps) {
        if (anchors == null || viewport == null || viewport.width() <= 0 || viewport.height() < LABEL_HEIGHT) {
            return List.of();
        }

        Map<Integer, Anchor> byIndex = new HashMap<>();
        for (Anchor anchor : anchors) {
            if (anchor == null || anchor.sourceIndex() < 0 || anchor.width() < 0
                    || !Double.isFinite(anchor.x()) || !Double.isFinite(anchor.y())) {
                continue;
            }
            Anchor existing = byIndex.get(anchor.sourceIndex());
            if (existing == null || anchorOrder(anchor, existing) < 0) {
                byIndex.put(anchor.sourceIndex(), anchor);
            }
        }
        if (byIndex.isEmpty()) {
            return List.of();
        }

        List<Anchor> valid = new ArrayList<>(byIndex.values());
        valid.sort(Comparator.comparingInt(Anchor::sourceIndex));
        List<Unit> units = new ArrayList<>(valid.size());
        for (Anchor anchor : valid) {
            units.add(Unit.single(anchor));
        }

        if (groupOverlaps) {
            units = mergeIdealOverlaps(units, viewport);
        }
        return place(units, viewport, selectedSourceIndex);
    }

    private static int anchorOrder(Anchor left, Anchor right) {
        int result = Double.compare(left.x(), right.x());
        if (result == 0) {
            result = Double.compare(left.y(), right.y());
        }
        if (result == 0) {
            result = Integer.compare(left.width(), right.width());
        }
        return result;
    }

    private static List<Unit> mergeIdealOverlaps(List<Unit> initial, Bounds viewport) {
        List<Unit> units = initial;
        // Group widths can introduce new collisions.  Bound the pathological case by
        // collapsing to one readable group, instead of repeatedly doing pair scans.
        for (int pass = 0; pass < 12 && units.size() > 1; pass++) {
            DisjointSet sets = new DisjointSet(units.size());
            List<Bounds> idealBounds = new ArrayList<>(units.size());
            for (int i = 0; i < units.size(); i++) {
                idealBounds.add(idealBounds(units.get(i), viewport));
            }
            boolean merged = mergeIntersectingBounds(idealBounds, sets);
            if (!merged) {
                return units;
            }
            Map<Integer, List<Anchor>> members = new HashMap<>();
            for (int i = 0; i < units.size(); i++) {
                members.computeIfAbsent(sets.find(i), ignored -> new ArrayList<>()).addAll(units.get(i).anchors);
            }
            List<Unit> next = new ArrayList<>(members.size());
            for (List<Anchor> memberAnchors : members.values()) {
                next.add(new Unit(memberAnchors));
            }
            next.sort(unitOrder());
            if (next.size() == units.size()) {
                return units;
            }
            units = next;
        }
        return units.size() > 1 ? List.of(join(units)) : units;
    }

    /**
     * Finds connected overlap components with a screen-cell index.  When many
     * members share one common screen rectangle, that common rectangle represents
     * the whole component and prevents repeated pairwise checks for dense forks.
     */
    private static boolean mergeIntersectingBounds(List<Bounds> bounds, DisjointSet sets) {
        Map<Long, CollisionCell> cells = new HashMap<>();
        boolean merged = false;
        for (int index = 0; index < bounds.size(); index++) {
            Bounds current = bounds.get(index);
            List<CollisionCell> touched = new ArrayList<>();
            for (int cellX = Math.floorDiv(current.x(), CELL_SIZE);
                    cellX <= Math.floorDiv(current.x() + current.width() - 1, CELL_SIZE); cellX++) {
                for (int cellY = Math.floorDiv(current.y(), CELL_SIZE);
                        cellY <= Math.floorDiv(current.y() + current.height() - 1, CELL_SIZE); cellY++) {
                    CollisionCell cell = cells.computeIfAbsent(SpatialIndex.cellKey(cellX, cellY), ignored -> new CollisionCell());
                    touched.add(cell);
                    if (cell.coarseMember >= 0 && !cell.commonBoundsEmpty && overlaps(current, cell.commonBounds)) {
                        sets.union(index, cell.coarseMember);
                        merged = true;
                    } else {
                        for (int candidate : cell.members) {
                            if (overlaps(current, bounds.get(candidate))) {
                                sets.union(index, candidate);
                                merged = true;
                            }
                        }
                    }
                }
            }
            for (CollisionCell cell : touched) {
                cell.members.add(index);
                if (!cell.commonBoundsEmpty) {
                    cell.commonBounds = intersection(cell.commonBounds, current);
                    cell.commonBoundsEmpty = cell.commonBounds == null;
                }
                if (cell.coarseMember < 0 && cell.members.size() > MAX_EXACT_CELL_MEMBERS && !cell.commonBoundsEmpty) {
                    cell.coarseMember = cell.members.get(0);
                    for (int candidate : cell.members) {
                        sets.union(cell.coarseMember, candidate);
                    }
                    merged = true;
                }
            }
        }
        return merged;
    }

    private static Bounds intersection(Bounds first, Bounds second) {
        if (first == null) {
            return second;
        }
        int left = Math.max(first.x(), second.x());
        int top = Math.max(first.y(), second.y());
        int right = Math.min(first.x() + first.width(), second.x() + second.width());
        int bottom = Math.min(first.y() + first.height(), second.y() + second.height());
        return left < right && top < bottom ? new Bounds(left, top, right - left, bottom - top) : null;
    }

    private static List<Label> place(List<Unit> units, Bounds viewport, int selected) {
        List<Unit> ordered = new ArrayList<>(units);
        // Give each source its numbered slot; selection only affects label content and styling.
        ordered.sort(unitOrder());
        SpatialIndex occupied = new SpatialIndex();
        List<Unit> placed = new ArrayList<>();

        for (Unit unit : ordered) {
            Bounds chosen = findOpenBounds(unit, viewport, occupied);
            if (chosen == null) {
                // There is no readable free slot within the viewport.  A single
                // aggregate remains clickable and satisfies the no-overlap rule.
                Unit all = join(ordered);
                Bounds aggregate = idealBounds(all, viewport);
                return List.of(toLabel(all, aggregate, selected));
            }
            unit.bounds = chosen;
            placed.add(unit);
            occupied.add(unit);
        }

        List<Label> result = new ArrayList<>(placed.size());
        for (Unit unit : placed) {
            result.add(toLabel(unit, unit.bounds, selected));
        }
        return List.copyOf(result);
    }

    private static Bounds findOpenBounds(Unit unit, Bounds viewport, SpatialIndex occupied) {
        int width = labelWidth(unit, viewport);
        int idealX = clamp((int) Math.round(unit.anchorX - width / 2.0), viewport.x(), viewport.x() + viewport.width() - width);
        int idealY = clamp((int) Math.round(unit.anchorY - LABEL_HEIGHT - PADDING), viewport.y(), viewport.y() + viewport.height() - LABEL_HEIGHT);

        Set<Long> seen = new HashSet<>();
        for (int ring = 0; ring <= 24; ring++) {
            int vertical = ring * (LABEL_HEIGHT + GAP);
            int horizontal = ring * Math.max(24, width / 2 + GAP);
            int[][] offsets = ring == 0
                    ? new int[][] {{0, 0}}
                    : new int[][] {{0, -vertical}, {horizontal, -vertical}, {horizontal, 0}, {horizontal, vertical},
                            {0, vertical}, {-horizontal, vertical}, {-horizontal, 0}, {-horizontal, -vertical}};
            for (int[] offset : offsets) {
                int x = clamp(idealX + offset[0], viewport.x(), viewport.x() + viewport.width() - width);
                int y = clamp(idealY + offset[1], viewport.y(), viewport.y() + viewport.height() - LABEL_HEIGHT);
                long key = ((long) x << 32) ^ (y & 0xffffffffL);
                if (!seen.add(key)) {
                    continue;
                }
                Bounds candidate = new Bounds(x, y, width, LABEL_HEIGHT);
                if (!occupied.collides(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static Bounds idealBounds(Unit unit, Bounds viewport) {
        int width = labelWidth(unit, viewport);
        int x = clamp((int) Math.round(unit.anchorX - width / 2.0), viewport.x(), viewport.x() + viewport.width() - width);
        int y = clamp((int) Math.round(unit.anchorY - LABEL_HEIGHT - PADDING), viewport.y(), viewport.y() + viewport.height() - LABEL_HEIGHT);
        return new Bounds(x, y, width, LABEL_HEIGHT);
    }

    private static int labelWidth(Unit unit, Bounds viewport) {
        int raw = unit.anchors.size() == 1
                ? unit.anchors.get(0).width() + 2 * PADDING
                : 76 + Integer.toString(unit.anchors.size()).length() * 6;
        if (unit.anchors.size() > 1) {
            // Reserve every member's name plus "  +N" so selection cannot resize or merge groups.
            int countWidth = 18 + Integer.toString(unit.anchors.size() - 1).length() * 6;
            for (Anchor anchor : unit.anchors) {
                raw = Math.max(raw, anchor.width() + 2 * PADDING + countWidth);
            }
        }
        return Math.min(viewport.width(), Math.max(1, raw));
    }

    private static Label toLabel(Unit unit, Bounds bounds, int selected) {
        List<Integer> indices = new ArrayList<>(unit.anchors.size());
        double anchorX = unit.anchorX;
        double anchorY = unit.anchorY;
        for (Anchor anchor : unit.anchors) {
            indices.add(anchor.sourceIndex());
            if (anchor.sourceIndex() == selected) {
                anchorX = anchor.x();
                anchorY = anchor.y();
            }
        }
        indices.sort((left, right) -> {
            if (left == selected) return -1;
            if (right == selected) return 1;
            return Integer.compare(left, right);
        });
        return new Label(indices, bounds, anchorX, anchorY);
    }

    private static Unit join(List<Unit> units) {
        List<Anchor> anchors = new ArrayList<>();
        for (Unit unit : units) {
            anchors.addAll(unit.anchors);
        }
        return new Unit(anchors);
    }

    private static Comparator<Unit> unitOrder() {
        return Comparator.comparingInt(Unit::lowestIndex);
    }

    private static boolean overlaps(Bounds first, Bounds second) {
        return first.x() < second.x() + second.width() && first.x() + first.width() > second.x()
                && first.y() < second.y() + second.height() && first.y() + first.height() > second.y();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Unit {
        private final List<Anchor> anchors;
        private final double anchorX;
        private final double anchorY;
        private Bounds bounds;

        private Unit(List<Anchor> anchors) {
            this.anchors = List.copyOf(anchors);
            double totalX = 0;
            double totalY = 0;
            for (Anchor anchor : anchors) {
                totalX += anchor.x();
                totalY += anchor.y();
            }
            anchorX = totalX / anchors.size();
            anchorY = totalY / anchors.size();
        }

        private static Unit single(Anchor anchor) {
            return new Unit(List.of(anchor));
        }

        private int lowestIndex() {
            return anchors.stream().mapToInt(Anchor::sourceIndex).min().orElse(Integer.MAX_VALUE);
        }
    }

    private static final class SpatialIndex {
        private final Map<Long, List<Unit>> cells = new HashMap<>();

        private boolean collides(Bounds bounds) {
            Set<Unit> checked = new HashSet<>();
            for (int cellX = Math.floorDiv(bounds.x(), CELL_SIZE); cellX <= Math.floorDiv(bounds.x() + bounds.width() - 1, CELL_SIZE); cellX++) {
                for (int cellY = Math.floorDiv(bounds.y(), CELL_SIZE); cellY <= Math.floorDiv(bounds.y() + bounds.height() - 1, CELL_SIZE); cellY++) {
                    for (Unit unit : cells.getOrDefault(cellKey(cellX, cellY), List.of())) {
                        if (checked.add(unit) && overlaps(bounds, unit.bounds)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private void add(Unit unit) {
            Bounds bounds = unit.bounds;
            for (int cellX = Math.floorDiv(bounds.x(), CELL_SIZE); cellX <= Math.floorDiv(bounds.x() + bounds.width() - 1, CELL_SIZE); cellX++) {
                for (int cellY = Math.floorDiv(bounds.y(), CELL_SIZE); cellY <= Math.floorDiv(bounds.y() + bounds.height() - 1, CELL_SIZE); cellY++) {
                    cells.computeIfAbsent(cellKey(cellX, cellY), ignored -> new ArrayList<>()).add(unit);
                }
            }
        }

        private static long cellKey(int x, int y) {
            return ((long) x << 32) ^ (y & 0xffffffffL);
        }
    }

    private static final class CollisionCell {
        private final List<Integer> members = new ArrayList<>();
        private int coarseMember = -1;
        private Bounds commonBounds;
        private boolean commonBoundsEmpty;
    }

    private static final class DisjointSet {
        private final int[] parent;

        private DisjointSet(int size) {
            parent = new int[size];
            for (int i = 0; i < size; i++) parent[i] = i;
        }

        private int find(int value) {
            while (parent[value] != value) {
                parent[value] = parent[parent[value]];
                value = parent[value];
            }
            return value;
        }

        private void union(int left, int right) {
            int leftRoot = find(left);
            int rightRoot = find(right);
            if (leftRoot != rightRoot) parent[rightRoot] = leftRoot;
        }
    }
}
