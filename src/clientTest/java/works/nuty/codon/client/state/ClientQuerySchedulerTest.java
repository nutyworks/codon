package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ClientQuerySchedulerTest {
    @Test void largeWatchPauseUsesCreditsAndStepWaitsForEveryRead() {
        var clock = new AtomicLong();
        var state = paused(clock, 33, false);
        var scheduler = new ClientQueryScheduler(clock::get);
        var sender = new Sent();

        scheduler.pump(state, sender);
        assertEquals(12, sender.watches.size());
        assertTrue(state.beginControlRequest());
        scheduler.requestControl(state, 1, "stepinto", true, sender);
        assertTrue(sender.controls.isEmpty());
        clock.set(3_000_000_000L);
        assertTrue(state.controlPending(), "a deferred step stays disabled beyond the ordinary control timeout");

        int replied = 0;
        while (replied < 33) {
            int sent = sender.watches.size();
            for (int i = replied; i < sent; i++) {
                var query = sender.watches.get(i);
                scheduler.watchReply(query.pauseId(), query.requestId());
                state.watches().accept(query.pauseId(), query.requestId(),
                    new WatchResult(WatchResult.Status.VALUE, "1", "entity:target"));
            }
            replied = sent;
            scheduler.pump(state, sender);
            assertTrue(sender.watches.size() - replied <= 12);
            if (replied < 33) assertTrue(sender.controls.isEmpty());
        }
        assertEquals(33, sender.watches.size());
        assertEquals(List.of("1:stepinto"), sender.controls);
        assertTrue(state.watches().entries().stream().allMatch(entry -> entry.result().status() == WatchResult.Status.VALUE));
    }

    @Test void editorAndNbtKeepReservedCreditsDuringWatchBurst() {
        var clock = new AtomicLong();
        var state = paused(clock, 33, true);
        state.watchEditor().request(1, 0,
            new WatchEditorQuery(WatchEditorQuery.Mode.OBJECTIVES, WatchSpec.Kind.SCORE, "", "", null, "", 0));
        var scheduler = new ClientQueryScheduler(clock::get);
        var sender = new Sent();

        scheduler.pump(state, sender);
        assertEquals(1, sender.editors.size());
        assertEquals(12, sender.watches.size());
        assertEquals(1, sender.nbt.size());
        assertEquals("editor", sender.order.getFirst());
        scheduler.pump(state, sender);
        assertEquals(14, sender.order.size(), "unanswered reads retain their credits");
    }

    @Test void lostRunningEditorReplyCannotLeaveExplicitRetryLoadingForever() {
        var clock = new AtomicLong();
        var state = new ClientDebuggerState(clock::get);
        var scheduler = new ClientQueryScheduler(clock::get);
        var sender = new Sent();
        state.watchEditor().request(0, -1,
            new WatchEditorQuery(WatchEditorQuery.Mode.OBJECTIVES, WatchSpec.Kind.SCORE, "", "", null, "", 0));
        scheduler.pump(state, sender);
        var old = sender.editors.getFirst();
        clock.set(5_000_000_000L);
        assertTrue(state.watchEditor().timedOut());
        state.watchEditor().retry();
        scheduler.pump(state, sender);
        assertEquals(1, sender.editors.size(), "no credit is recycled without a reply");
        clock.set(10_000_000_000L);
        scheduler.pump(state, sender);
        assertFalse(state.watchEditor().waiting());
        assertTrue(state.watchEditor().timedOut(), "unsent Retry also has a bounded visible failure");
        scheduler.editorReply(old.pauseId(), old.requestId());
        state.watchEditor().retry();
        scheduler.pump(state, sender);
        assertEquals(2, sender.editors.size(), "an actual late reply permits an explicit fresh attempt");
    }

    @Test void lostWatchReplyCancelsStepAndQuarantinesThisPause() {
        var clock = new AtomicLong();
        var state = paused(clock, 33, false);
        var scheduler = new ClientQueryScheduler(clock::get);
        var sender = new Sent();
        scheduler.pump(state, sender);
        assertTrue(state.beginControlRequest());
        scheduler.requestControl(state, 1, "stepinto", true, sender);

        clock.set(5_000_000_000L);
        scheduler.pump(state, sender);
        assertTrue(sender.controls.isEmpty());
        assertEquals(12, sender.watches.size(), "a timed-out client request may still occupy a server slot");
        assertTrue(state.watchReadsFailed());
        assertFalse(state.controlPending(), "the queued step ends with an explicit failure");
        assertTrue(state.watches().entries().stream().allMatch(entry ->
            entry.result().status() == WatchResult.Status.UNAVAILABLE));
        clock.set(15_000_000_000L);
        scheduler.pump(state, sender);
        assertEquals(12, sender.watches.size(), "server credit requires an actual reply");
        assertTrue(sender.controls.isEmpty(), "step cannot skip unsent pre-step reads");

        assertTrue(state.beginControlRequest());
        scheduler.requestControl(state, 1, "stepinto", true, sender);
        assertFalse(state.controlPending(), "repeated step fails immediately during the same failed pause");
        assertTrue(sender.controls.isEmpty());

        assertTrue(state.beginControlRequest());
        scheduler.requestControl(state, 1, "resume", false, sender);
        assertEquals(List.of("1:resume"), sender.controls);
        assertTrue(state.watchReadsFailed(), "the error remains visible until server acknowledgement");
        state.applyResume();
        scheduler.reset();
        assertFalse(state.watchReadsFailed());
    }

    @Test void lateReplyAfterDisplayTimeoutCannotSendStep() {
        var clock = new AtomicLong();
        var state = paused(clock, 1, false);
        var scheduler = new ClientQueryScheduler(clock::get);
        var sender = new Sent();
        scheduler.pump(state, sender);
        assertTrue(state.beginControlRequest());
        scheduler.requestControl(state, 1, "stepover", true, sender);

        clock.set(5_000_000_000L);
        assertEquals(WatchResult.Status.UNAVAILABLE, state.watches().entries().getFirst().result().status());
        var query = sender.watches.getFirst();
        scheduler.watchReply(query.pauseId(), query.requestId());
        state.watches().accept(query.pauseId(), query.requestId(),
            new WatchResult(WatchResult.Status.VALUE, "late", "entity:target"));
        scheduler.pump(state, sender);
        assertTrue(sender.controls.isEmpty());
        assertTrue(state.watchReadsFailed());
    }

    @Test void otherRequestSaturationFailsWithoutRetryAndNewPauseRecovers() {
        var clock = new AtomicLong();
        var state = paused(clock, 33, false);
        var scheduler = new ClientQueryScheduler(clock::get);
        var sender = new Sent();
        // The same 32-slot mailbox also admits source browse, saves, previews and edits.
        // Model a full mailbox by withholding every Watch reply; admission is unknowable here.
        scheduler.pump(state, sender);
        assertTrue(state.beginControlRequest());
        scheduler.requestControl(state, 1, "stepinto", true, sender);
        clock.set(5_000_000_000L);
        scheduler.pump(state, sender);
        assertTrue(sender.controls.isEmpty());
        assertEquals(12, sender.watches.size());
        assertTrue(state.watchReadsFailed());

        state.applyPause(snapshot(2));
        scheduler.pump(state, sender);
        assertFalse(state.watchReadsFailed());
        assertEquals(24, sender.watches.size(), "new pause resets the quarantined read window");
    }

    @Test void lateReplyBeforeAnyTickOrRenderCannotReleaseStep() {
        var clock = new AtomicLong();
        var state = paused(clock, 1, false);
        var scheduler = new ClientQueryScheduler(clock::get);
        var sender = new Sent();
        scheduler.pump(state, sender);
        assertTrue(state.beginControlRequest());
        scheduler.requestControl(state, 1, "stepout", true, sender);

        clock.set(5_000_000_000L);
        var query = sender.watches.getFirst();
        scheduler.watchReply(query.pauseId(), query.requestId());
        state.watches().accept(query.pauseId(), query.requestId(),
            new WatchResult(WatchResult.Status.VALUE, "late", "entity:target"));
        scheduler.pump(state, sender);
        assertTrue(sender.controls.isEmpty());
        assertTrue(state.watchReadsFailed());
        assertEquals(WatchResult.Status.UNAVAILABLE, state.watches().entries().getFirst().result().status());
    }

    @Test void resumeCancelsWaitingStepAndOldRepliesCannotReviveItAfterNewPauseOrDisconnect() {
        var clock = new AtomicLong();
        var state = paused(clock, 1, false);
        var scheduler = new ClientQueryScheduler(clock::get);
        var sender = new Sent();
        scheduler.pump(state, sender);
        var old = sender.watches.getFirst();
        assertTrue(state.beginControlRequest());
        scheduler.requestControl(state, 1, "stepover", true, sender);
        assertFalse(state.beginControlRequest(), "repeated controls remain blocked during capture");
        scheduler.requestControl(state, 1, "resume", false, sender);
        scheduler.watchReply(old.pauseId(), old.requestId());
        state.watches().accept(old.pauseId(), old.requestId(),
            new WatchResult(WatchResult.Status.VALUE, "1", "entity:target"));
        scheduler.pump(state, sender);
        assertEquals(List.of("1:resume"), sender.controls);

        state.applyPause(snapshot(2));
        scheduler.pump(state, sender);
        assertTrue(state.beginControlRequest());
        scheduler.requestControl(state, 2, "stepinto", true, sender);
        scheduler.reset();
        state.reset();
        scheduler.watchReply(old.pauseId(), old.requestId());
        scheduler.pump(state, sender);
        assertEquals(List.of("1:resume"), sender.controls);
        assertFalse(state.watchReadsFailed());
    }

    @Test void lostReadWithoutQueuedStepFailsVisiblyAndStopsFurtherSends() {
        var clock = new AtomicLong();
        var state = paused(clock, 33, false);
        var scheduler = new ClientQueryScheduler(clock::get);
        var sender = new Sent();
        scheduler.pump(state, sender);
        clock.set(5_000_000_000L);
        scheduler.pump(state, sender);
        assertTrue(state.watchReadsFailed());
        assertEquals(12, sender.watches.size());
        assertTrue(state.watches().entries().stream().allMatch(entry ->
            entry.result().status() == WatchResult.Status.UNAVAILABLE));
        scheduler.pump(state, sender);
        assertEquals(12, sender.watches.size());
    }

    @Test void wholeCaptureDeadlineCancelsStepEvenWhileWavesMakeProgress() {
        var clock = new AtomicLong();
        var state = paused(clock, 33, false);
        var scheduler = new ClientQueryScheduler(clock::get);
        var sender = new Sent();
        scheduler.pump(state, sender);
        assertTrue(state.beginControlRequest());
        scheduler.requestControl(state, 1, "stepinto", true, sender);
        for (int wave = 0; wave < 2; wave++) {
            clock.set((wave + 1) * 4_000_000_000L);
            int end = sender.watches.size();
            for (int i = wave * 12; i < end; i++) {
                var query = sender.watches.get(i);
                scheduler.watchReply(query.pauseId(), query.requestId());
                state.watches().accept(query.pauseId(), query.requestId(),
                    new WatchResult(WatchResult.Status.VALUE, "1", "entity:target"));
            }
            scheduler.pump(state, sender);
        }
        assertEquals(33, sender.watches.size());
        clock.set(10_000_000_000L);
        scheduler.pump(state, sender);
        assertTrue(state.watchReadsFailed());
        assertTrue(sender.controls.isEmpty());
        assertFalse(state.controlPending());
    }

    @Test void repeatedSamePausePacketCannotReviveDeferredStep() {
        var clock = new AtomicLong();
        var state = paused(clock, 1, false);
        var scheduler = new ClientQueryScheduler(clock::get);
        var sender = new Sent();
        scheduler.pump(state, sender);
        assertTrue(state.beginControlRequest());
        scheduler.requestControl(state, 1, "stepinto", true, sender);
        state.applyPause(snapshot(1));
        scheduler.pump(state, sender);
        var query = sender.watches.getFirst();
        scheduler.watchReply(query.pauseId(), query.requestId());
        state.watches().accept(query.pauseId(), query.requestId(),
            new WatchResult(WatchResult.Status.VALUE, "1", "entity:target"));
        scheduler.pump(state, sender);
        assertTrue(sender.controls.isEmpty());
        assertTrue(state.watchReadsFailed());
    }

    private static ClientDebuggerState paused(AtomicLong clock, int watchCount, boolean entitySource) {
        var state = new ClientDebuggerState(clock::get);
        for (int i = 0; i < watchCount; i++)
            assertTrue(state.watches().add(new WatchSpec(WatchSpec.Kind.SCORE, "objective" + i, "")));
        SourceLocation location = new SourceLocation.Block(new BlockLocation(0, 64, 0, "minecraft:overworld"));
        EntityRef entity = entitySource ? new EntityRef(UUID.randomUUID(), "Player") : null;
        var source = new PauseSource(new Vec3d(0, 64, 0), 0, 0, entity, "minecraft:overworld");
        state.applyPause(new PauseSnapshot(location, CommandSnippet.plain("say test"), 0, List.of(),
            List.of(source), PauseReason.BREAKPOINT, 1));
        return state;
    }

    private static PauseSnapshot snapshot(long id) {
        SourceLocation location = new SourceLocation.Block(new BlockLocation(0, 64, 0, "minecraft:overworld"));
        var source = new PauseSource(new Vec3d(0, 64, 0), 0, 0, null, "minecraft:overworld");
        return new PauseSnapshot(location, CommandSnippet.plain("say test"), 0, List.of(),
            List.of(source), PauseReason.BREAKPOINT, id);
    }

    private static final class Sent implements ClientQueryScheduler.Sender {
        final List<ClientWatchEditorState.Query> editors = new ArrayList<>();
        final List<ClientWatchState.Query> watches = new ArrayList<>();
        final List<ClientNbtState.Query> nbt = new ArrayList<>();
        final List<String> controls = new ArrayList<>();
        final List<String> order = new ArrayList<>();
        @Override public void editor(ClientWatchEditorState.Query query) { editors.add(query); order.add("editor"); }
        @Override public void watch(ClientWatchState.Query query) { watches.add(query); order.add("watch"); }
        @Override public void nbt(ClientNbtState.Query query) { nbt.add(query); order.add("nbt"); }
        @Override public void control(long pauseId, String command) { controls.add(pauseId + ":" + command); }
    }
}
