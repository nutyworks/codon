package works.nuty.codon.client.ui;

import org.junit.jupiter.api.Test;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.core.model.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DebuggerStatusTest {
    @Test
    void resumeShowsRunningWhileFinishedSnapshotRemainsInspectable() {
        ClientDebuggerState state = new ClientDebuggerState();
        assertEquals("codon.ui.running", DebuggerStatus.translationKey(state));
        PauseSnapshot finished = snapshot(PauseReason.EXECUTION_COMPLETE);
        state.applyPause(finished);
        assertEquals("codon.ui.execution_complete", DebuggerStatus.translationKey(state));
        assertTrue(state.beginControlRequest());
        assertEquals("codon.ui.waiting", DebuggerStatus.translationKey(state));

        state.applyResume();
        assertSame(finished, state.inspectionSnapshot());
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

    private static PauseSnapshot snapshot(PauseReason reason) {
        return new PauseSnapshot(new SourceLocation.Block(new BlockLocation(0, 64, 0, "overworld")),
            CommandSnippet.plain("say done"), 0, List.of(), List.of(), reason);
    }
}
