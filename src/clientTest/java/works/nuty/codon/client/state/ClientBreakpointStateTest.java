package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.service.BreakpointRegistry;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ClientBreakpointStateTest {
    @Test
    void inlineRejectionsNotifyWithoutChangingAcknowledgedDefinitions() {
        var notices = new ArrayList<ClientBreakpointState.Result>();
        var state = new ClientBreakpointState(System::nanoTime, notices::add);
        var definition = definition();
        assertTrue(state.acceptPage(1, 0, true, List.of(definition)));
        for (var result : List.of(ClientBreakpointState.Result.STALE_SOURCE,
            ClientBreakpointState.Result.NO_PERMISSION, ClientBreakpointState.Result.FAILED)) {
            var edit = state.begin(ClientBreakpointState.Action.TOGGLE, definition);
            assertNull(state.begin(ClientBreakpointState.Action.TOGGLE, definition));
            state.finish(edit.requestId(), result);
            assertEquals(result, notices.getLast());
            assertEquals(result, state.error(definition.target()));
            assertFalse(state.pending(definition.target()));
            assertSame(definition, state.get(definition.target()));
            state.finish(edit.requestId(), result);
        }
        assertEquals(3, notices.size(), "duplicate acknowledgements do not replay notices");
    }

    @Test
    void hiddenInlineEditExpiresOnceAndLateRepliesDoNotReleaseItsReplacement() {
        var now = new AtomicLong();
        var notices = new ArrayList<ClientBreakpointState.Result>();
        var state = new ClientBreakpointState(now::get, notices::add);
        var definition = definition();
        var old = state.begin(ClientBreakpointState.Action.TOGGLE, definition);
        now.set(9_999_999_999L);
        state.expirePending();
        assertTrue(notices.isEmpty());
        now.incrementAndGet();
        state.finish(old.requestId(), ClientBreakpointState.Result.APPLIED);
        assertEquals(List.of(ClientBreakpointState.Result.TIMED_OUT), notices);
        assertNull(state.get(definition.target()), "expiry/rejection never supplies an optimistic definition");
        state.expirePending();
        assertEquals(1, notices.size());
        var replacement = state.begin(ClientBreakpointState.Action.TOGGLE, definition);
        state.finish(old.requestId(), ClientBreakpointState.Result.STALE_SOURCE);
        assertTrue(state.pending(definition.target()));
        assertNull(state.error(definition.target()));
        assertEquals(1, notices.size());
        state.finish(replacement.requestId(), ClientBreakpointState.Result.APPLIED);
        assertFalse(state.pending(definition.target()));
        assertEquals(1, notices.size());
        assertNull(state.get(definition.target()), "only the separate authoritative snapshot changes definitions");
    }

    @Test
    void modalSaveAndDeleteRetainTheirErrorsWithoutDuplicateNoticesAndResetRejectsOldIds() {
        var notices = new ArrayList<ClientBreakpointState.Result>();
        var state = new ClientBreakpointState(System::nanoTime, notices::add);
        var definition = definition();
        for (var action : List.of(ClientBreakpointState.Action.SAVE, ClientBreakpointState.Action.DELETE)) {
            var edit = state.begin(action, definition);
            state.finish(edit.requestId(), ClientBreakpointState.Result.NO_PERMISSION);
            assertEquals(ClientBreakpointState.Result.NO_PERMISSION, state.error(definition.target()));
        }
        assertTrue(notices.isEmpty());
        var old = state.begin(ClientBreakpointState.Action.TOGGLE, definition);
        state.reset();
        var current = state.begin(ClientBreakpointState.Action.TOGGLE, definition);
        assertNotEquals(old.requestId(), current.requestId());
        state.finish(old.requestId(), ClientBreakpointState.Result.NO_PERMISSION);
        assertTrue(state.pending(definition.target()));
        assertTrue(notices.isEmpty());
    }

    private static BreakpointDefinition definition() {
        return BreakpointDefinition.plain(BreakpointTarget.whole(
            new SourceLocation.Block(new BlockLocation(0, 64, 0, "minecraft:overworld"))));
    }

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
