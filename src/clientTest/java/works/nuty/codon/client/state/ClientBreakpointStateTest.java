package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.service.BreakpointRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientBreakpointStateTest {
    @Test
    void rejectsAnOversizedPagedSnapshotWithoutPublishingIt() {
        ClientBreakpointState state = new ClientBreakpointState();
        BreakpointDefinition definition = BreakpointDefinition.plain(BreakpointTarget.whole(
            new SourceLocation.Block(new BlockLocation(0, 64, 0, "minecraft:overworld"))));
        List<BreakpointDefinition> page = java.util.Collections.nCopies(48, definition);
        for (int offset = 0; offset < BreakpointRegistry.MAX_DEFINITIONS - 16; offset += 48)
            assertFalse(state.acceptPage(1, offset, false, page));
        assertFalse(state.acceptPage(1, BreakpointRegistry.MAX_DEFINITIONS - 16, false,
            java.util.Collections.nCopies(16, definition)));
        assertFalse(state.acceptPage(1, BreakpointRegistry.MAX_DEFINITIONS, true, List.of(definition)));
        assertFalse(state.ready());
        assertTrue(state.definitions().isEmpty());

        assertTrue(state.acceptPage(2, 0, true, List.of(definition)));
        assertEquals(List.of(definition), state.definitions());
    }
}
