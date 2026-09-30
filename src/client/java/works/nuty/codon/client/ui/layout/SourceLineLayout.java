package works.nuty.codon.client.ui.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/** Original source offsets plus small breakpoint slots; source characters are never replaced. */
public final class SourceLineLayout {
    public static final int MARKER_WIDTH = 20;
    public record Segment(int start, int end, int inset) { }
    private final int length;
    private final List<Integer> boundaries;
    private final IntUnaryOperator sourceX;
    private final List<Segment> segments;

    public SourceLineLayout(int length, List<Integer> boundaries, IntUnaryOperator sourceX) {
        this.length = length;
        this.boundaries = List.copyOf(boundaries);
        this.sourceX = sourceX;
        List<Segment> parts = new ArrayList<>();
        int from = 0, inset = 0, previous = -1;
        for (int boundary : boundaries) {
            if (boundary <= previous || boundary >= length) throw new IllegalArgumentException("Invalid source boundary");
            if (from < boundary) parts.add(new Segment(from, boundary, inset));
            from = boundary;
            inset += MARKER_WIDTH;
            previous = boundary;
        }
        if (from < length) parts.add(new Segment(from, length, inset));
        segments = List.copyOf(parts);
    }

    public List<Segment> segments() { return segments; }
    public int width() { return sourceX.applyAsInt(length) + boundaries.size() * MARKER_WIDTH; }
    public int markerX(int marker) { return sourceX.applyAsInt(boundaries.get(marker)) + marker * MARKER_WIDTH; }
    public int x(int index) { return position(index, true); }
    public int before(int index) { return position(index, false); }

    private int position(int index, boolean inclusive) {
        int inset = 0;
        for (int boundary : boundaries) {
            if (boundary > index || !inclusive && boundary == index) break;
            inset += MARKER_WIDTH;
        }
        return sourceX.applyAsInt(index) + inset;
    }
}
