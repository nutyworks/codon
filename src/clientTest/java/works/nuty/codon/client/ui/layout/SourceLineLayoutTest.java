package works.nuty.codon.client.ui.layout;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceLineLayoutTest {
    @Test void variableGlyphWidthsAndMarkerSlotsShareSourceCoordinates() {
        int[] positions = {0, 2, 8, 12, 20, 22};
        var layout = new SourceLineLayout(5, List.of(1, 3), index -> positions[index]);
        assertEquals(2, layout.markerX(0));
        assertEquals(32, layout.markerX(1));
        assertEquals(22, layout.x(1));
        assertEquals(2, layout.before(1));
        assertEquals(52, layout.x(3));
        assertEquals(32, layout.before(3));
        assertEquals(62, layout.width());
        String source = "iW 한글";
        assertEquals(source, layout.segments().stream().map(part -> source.substring(part.start(), part.end()))
            .collect(java.util.stream.Collectors.joining()));
        assertEquals(List.of(new SourceLineLayout.Segment(0, 1, 0),
            new SourceLineLayout.Segment(1, 3, 20), new SourceLineLayout.Segment(3, 5, 40)), layout.segments());
    }

    @Test void plainAndFirstCharacterBoundariesPreserveTextAndRejectUnorderedOffsets() {
        assertEquals(List.of(new SourceLineLayout.Segment(0, 4, 0)),
            new SourceLineLayout(4, List.of(), index -> index * 3).segments());
        var first = new SourceLineLayout(4, List.of(0), index -> index * 3);
        assertEquals(0, first.markerX(0));
        assertEquals(20, first.x(0));
        assertEquals(32, first.width());
        assertThrows(IllegalArgumentException.class, () -> new SourceLineLayout(4, List.of(2, 1), index -> index));
        assertThrows(IllegalArgumentException.class, () -> new SourceLineLayout(4, List.of(1, 1), index -> index));
    }
}
