package works.nuty.codon.client.ui;

import works.nuty.codon.client.state.ClientDebuggerState;

/** Live execution status, independent of retained inspection history. */
final class DebuggerStatus {
    private DebuggerStatus() { }

    static String translationKey(ClientDebuggerState state) {
        if (state.displayControlWaiting()) return "codon.ui.waiting";
        var snapshot = state.snapshot();
        if (!state.isPaused() || snapshot == null) return "codon.ui.running";
        return switch (snapshot.reason()) {
            case BREAKPOINT -> "codon.ui.breakpoint_hit";
            case STEP -> "codon.ui.step_complete";
            case EXECUTION_COMPLETE -> "codon.ui.execution_complete";
        };
    }
}
