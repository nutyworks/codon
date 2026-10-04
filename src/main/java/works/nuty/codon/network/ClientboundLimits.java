package works.nuty.codon.network;

import works.nuty.codon.core.model.PauseSnapshot;

/** Receive ceilings for the live pause, independent of smaller historical-flow recording limits. */
public final class ClientboundLimits {
    // Live snapshots have no pagination. Keep generous room for ordinary deep/forked execution,
    // while bounding collection allocation before decoding any element. Never truncate indices.
    public static final int MAX_CALL_STACK = 1024;
    public static final int MAX_PAUSE_SOURCES = 4096;

    private ClientboundLimits() { }

    public static boolean supports(PauseSnapshot snapshot) {
        return snapshot.callStack().size() <= MAX_CALL_STACK && snapshot.pauseSources().size() <= MAX_PAUSE_SOURCES;
    }
}
