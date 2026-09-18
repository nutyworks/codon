package works.nuty.codon.core.service;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.FunctionId;
import works.nuty.codon.core.model.FunctionLocation;
import works.nuty.codon.core.model.SourceLocation;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreakpointRegistryTest {
    private final BreakpointRegistry registry = new BreakpointRegistry();

    private static BlockLocation block(int x, int y, int z) {
        return new BlockLocation(x, y, z, "minecraft:overworld");
    }

    private static FunctionLocation fn(String path, int line) {
        return new FunctionLocation(new FunctionId("test", path), line);
    }

    @Test
    void togglingAddsThenRemoves() {
        assertTrue(registry.toggleBlock(block(1, 2, 3)), "first toggle adds");
        assertFalse(registry.toggleBlock(block(1, 2, 3)), "second toggle removes");
        assertTrue(registry.isEmpty());
    }

    @Test
    void matchesBlockBreakpointByExactPositionAndDimension() {
        registry.toggleBlock(block(1, 2, 3));
        assertTrue(registry.matches(new SourceLocation.Block(block(1, 2, 3))));
        assertFalse(registry.matches(new SourceLocation.Block(block(1, 2, 4))));
    }

    @Test
    void blockBreakpointsAreDimensionAware() {
        registry.toggleBlock(new BlockLocation(0, 0, 0, "minecraft:overworld"));
        assertFalse(registry.matches(new SourceLocation.Block(new BlockLocation(0, 0, 0, "minecraft:the_nether"))));
    }

    @Test
    void matchesFunctionBreakpointByFunctionAndLine() {
        registry.toggleFunction(fn("tick", 3));
        assertTrue(registry.matches(new SourceLocation.Function(fn("tick", 3))));
        assertFalse(registry.matches(new SourceLocation.Function(fn("tick", 4))));
        assertFalse(registry.matches(new SourceLocation.Function(fn("load", 3))));
    }

    @Test
    void playerSourcesNeverMatch() {
        registry.toggleBlock(block(1, 2, 3));
        registry.toggleFunction(fn("tick", 1));
        assertFalse(registry.matches(new SourceLocation.Player(UUID.randomUUID(), "Steve")));
    }

    @Test
    void sizeCountsBothKindsAndClearEmpties() {
        registry.toggleBlock(block(1, 1, 1));
        registry.toggleFunction(fn("a", 1));
        registry.toggleFunction(fn("b", 2));
        assertEquals(3, registry.size());
        registry.clear();
        assertTrue(registry.isEmpty());
    }
}
