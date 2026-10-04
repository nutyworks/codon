package works.nuty.codon.client.ui;

import java.util.List;
import org.junit.jupiter.api.Test;
import works.nuty.codon.client.state.ClientBreakpointState;
import works.nuty.codon.client.ui.layout.VisibleWidgetCache;
import works.nuty.codon.core.model.*;

import static org.junit.jupiter.api.Assertions.*;

class BreakpointListCacheTest {
    @Test void successiveSnapshotsRetainOnlyVisibleWidgetsAndKeepStableIdentity() {
        var state = new ClientBreakpointState();
        var cache = new VisibleWidgetCache<BreakpointTarget, DebuggerButton>();
        var stable = definition(0);
        DebuggerButton selected = null;
        for (int id = 1; id <= 100; id++) {
            assertTrue(state.acceptPage(id, 0, true, List.of(stable, definition(id))));
            cache.begin();
            for (var definition : state.definitions()) cache.get(definition.target(), DebuggerButton::new);
            var row = cache.get(stable.target(), DebuggerButton::new);
            if (selected == null) { selected = row; selected.setFocused(true); }
            assertSame(selected, row);
            assertTrue(row.isFocused());
            cache.end();
            assertEquals(2, cache.size(), "Only this frame's targets retain widgets/callbacks");
        }
        cache.begin();
        cache.end();
        assertEquals(0, cache.size(), "Empty/disconnected snapshots evict all rows");
        cache.begin();
        assertNotSame(selected, cache.get(stable.target(), DebuggerButton::new));
        cache.end();
    }

    private static BreakpointDefinition definition(int x) {
        return BreakpointDefinition.plain(BreakpointTarget.whole(
            new SourceLocation.Block(new BlockLocation(x, 64, 0, "minecraft:overworld"))));
    }
}
