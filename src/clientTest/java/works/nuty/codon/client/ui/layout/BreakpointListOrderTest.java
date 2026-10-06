package works.nuty.codon.client.ui.layout;

import java.util.List;
import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.*;

import static org.junit.jupiter.api.Assertions.*;

class BreakpointListOrderTest {
    @Test void functionLinesAndStagesSortNumericallyWithinTheirSource() {
        var source = new SourceLocation.Function(new FunctionLocation(new FunctionId("test", "main"), 2));
        var line2 = BreakpointTarget.whole(source);
        var line10 = BreakpointTarget.whole(new SourceLocation.Function(
            new FunctionLocation(new FunctionId("test", "main"), 10)));
        var stage2 = BreakpointTarget.stage(source, 1, "execute as @a run say test");
        var stage10 = BreakpointTarget.stage(source, 9, "execute as @a run say test");
        assertEquals(List.of(line2, stage2, stage10, line10),
            List.of(line10, stage10, line2, stage2).stream().sorted(BreakpointListOrder.TARGETS).toList());
    }

    @Test void blockCoordinatesSortNumericallyAndDimensionsStayGrouped() {
        var negative = block(-10, "minecraft:overworld");
        var two = block(2, "minecraft:overworld");
        var ten = block(10, "minecraft:overworld");
        var nether = block(20, "minecraft:the_nether");
        assertEquals(List.of(negative, two, ten, nether),
            List.of(ten, nether, two, negative).stream().sorted(BreakpointListOrder.TARGETS).toList());
    }

    private static BreakpointTarget block(int x, String dimension) {
        return BreakpointTarget.whole(new SourceLocation.Block(new BlockLocation(x, 64, 0, dimension)));
    }
}
