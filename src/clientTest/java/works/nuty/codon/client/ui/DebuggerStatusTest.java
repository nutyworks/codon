package works.nuty.codon.client.ui;

import org.junit.jupiter.api.Test;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.PendingDisplay;
import works.nuty.codon.core.model.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DebuggerStatusTest {
    @Test
    void controlPendingDisablesImmediatelyButDelaysTheWaitingLabelUntilTheGraceBoundary() {
        AtomicLong now = new AtomicLong();
        ClientDebuggerState state = new ClientDebuggerState(now::get);
        assertEquals("codon.ui.running", DebuggerStatus.translationKey(state));
        PauseSnapshot finished = snapshot(PauseReason.EXECUTION_COMPLETE);
        state.applyPause(finished);
        assertEquals("codon.ui.execution_complete", DebuggerStatus.translationKey(state));
        assertTrue(state.beginControlRequest());
        assertTrue(state.controlPending(), "actions are disabled as soon as the request is sent");
        assertFalse(state.beginControlRequest(), "a repeated control action is disabled during the display grace period");
        assertFalse(state.displayControlWaiting());
        assertEquals("codon.ui.execution_complete", DebuggerStatus.translationKey(state));

        now.set(PendingDisplay.GRACE_NANOS - 1);
        assertEquals("codon.ui.execution_complete", DebuggerStatus.translationKey(state));
        state.applyPause(snapshot(PauseReason.BREAKPOINT));
        assertEquals("codon.ui.breakpoint_hit", DebuggerStatus.translationKey(state),
            "a response before the boundary replaces the old status directly");

        assertTrue(state.beginControlRequest());
        now.addAndGet(PendingDisplay.GRACE_NANOS);
        assertTrue(state.displayControlWaiting());
        assertEquals("codon.ui.waiting", DebuggerStatus.translationKey(state));
        state.applyPause(snapshot(PauseReason.STEP));
        assertEquals("codon.ui.step_complete", DebuggerStatus.translationKey(state),
            "a response after the boundary also displays its fresh status immediately");
    }

    @Test
    void resumeAndResetNeverRetainAnOldPauseLabel() {
        AtomicLong now = new AtomicLong();
        ClientDebuggerState state = new ClientDebuggerState(now::get);
        PauseSnapshot finished = snapshot(PauseReason.EXECUTION_COMPLETE);
        state.applyPause(finished);
        assertTrue(state.beginControlRequest());

        state.applyResume();
        assertSame(finished, state.inspectionSnapshot());
        assertEquals("codon.ui.running", DebuggerStatus.translationKey(state));

        state.applyPause(snapshot(PauseReason.BREAKPOINT));
        assertTrue(state.beginControlRequest());
        state.reset();
        assertEquals("codon.ui.running", DebuggerStatus.translationKey(state));
    }

    @Test
    void advancingDoesNotDisplayHistoricalPauseReasons() {
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(snapshot(PauseReason.BREAKPOINT));
        assertEquals("codon.ui.breakpoint_hit", DebuggerStatus.translationKey(state));
        state.applyContinue();
        assertEquals("codon.ui.running", DebuggerStatus.translationKey(state));
        state.applyPause(snapshot(PauseReason.STEP));
        assertEquals("codon.ui.step_complete", DebuggerStatus.translationKey(state));
        state.applyStep();
        assertEquals("codon.ui.running", DebuggerStatus.translationKey(state));
    }

    @Test
    void failedWatchReadIsVisibleUntilServerConfirmsResume() {
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(snapshot(PauseReason.BREAKPOINT));
        state.failWatchReads();
        assertEquals("codon.ui.watch_reads_failed", DebuggerStatus.translationKey(state));
        assertTrue(state.beginControlRequest());
        state.controlSent();
        assertEquals("codon.ui.watch_reads_failed", DebuggerStatus.translationKey(state));
        state.applyResume();
        assertEquals("codon.ui.running", DebuggerStatus.translationKey(state));
    }

    private static PauseSnapshot snapshot(PauseReason reason) {
        return new PauseSnapshot(new SourceLocation.Block(new BlockLocation(0, 64, 0, "overworld")),
            CommandSnippet.plain("say done"), 0, List.of(), List.of(), reason);
    }
}
