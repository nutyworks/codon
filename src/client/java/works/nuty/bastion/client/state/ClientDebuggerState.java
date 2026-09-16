package works.nuty.bastion.client.state;

import org.jspecify.annotations.Nullable;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.PauseSnapshot;
import works.nuty.bastion.core.model.PauseSource;

import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The client-side mirror of the debugger, updated purely from server sync packets. The UI, HUD,
 * and in-world renderers read from here instead of reaching into server state — which is what lets
 * Bastion work the same on a dedicated server as in singleplayer.
 *
 * <p>Fields are {@code volatile}: network handlers schedule updates onto the client thread, and the
 * render thread reads them.
 */
public final class ClientDebuggerState {
    private volatile boolean paused;
    private volatile boolean stepping;
    private volatile @Nullable PauseSnapshot snapshot;
    private volatile List<BlockLocation> blockBreakpoints = List.of();
    private int selectedSourceIndex = -1;
    private int selectedFrameIndex;
    private boolean controlPending;
    private long controlRequestedAt;
    private final LongSupplier clock;
    private @Nullable PauseSource selectionHint;
    private GizmoMode gizmoMode = GizmoMode.GROUPED;

    public ClientDebuggerState() {
        this(System::nanoTime);
    }

    /** Injectable monotonic clock keeps acknowledgement timeouts deterministic in unit tests. */
    public ClientDebuggerState(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public enum GizmoMode {
        LABELS, FOCUS, GROUPED;

        public GizmoMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public void applyPause(PauseSnapshot snapshot) {
        PauseSource previous = selectedSource() != null ? selectedSource() : selectionHint;
        selectionHint = null;
        this.snapshot = snapshot;
        this.paused = true;
        this.stepping = false;
        this.controlPending = false;
        this.selectedFrameIndex = 0;
        this.selectedSourceIndex = snapshot.pauseSources().isEmpty() ? -1 : 0;
        // A repeated source retains selection when possible; numeric badges are snapshot-local.
        if (previous != null) {
            int exact = snapshot.pauseSources().indexOf(previous);
            if (exact >= 0) {
                this.selectedSourceIndex = exact;
            } else if (previous.entity() != null) {
                int match = -1;
                for (int i = 0; i < snapshot.pauseSources().size(); i++) {
                    PauseSource candidate = snapshot.pauseSources().get(i);
                    if (candidate.entity() != null
                        && candidate.entity().uuid().equals(previous.entity().uuid())
                        && candidate.dimension().equals(previous.dimension())) {
                        if (match >= 0) { match = -1; break; }
                        match = i;
                    }
                }
                if (match >= 0) this.selectedSourceIndex = match;
            }
        }
    }

    public void applyResume() {
        if (selectedSource() != null) selectionHint = selectedSource();
        this.paused = false;
        this.stepping = false;
        this.snapshot = null;
        this.selectedSourceIndex = -1;
        this.selectedFrameIndex = 0;
        this.controlPending = false;
    }

    /** Server-confirmed advancement: discard the old pause but retain the freecam session. */
    public void applyStep() {
        applyResume();
        this.stepping = true;
    }

    public boolean isStepping() {
        return stepping;
    }

    public void applyBreakpoints(List<BlockLocation> blocks) {
        this.blockBreakpoints = List.copyOf(blocks);
    }

    public boolean isPaused() {
        return paused;
    }

    public @Nullable PauseSnapshot snapshot() {
        return snapshot;
    }

    public List<BlockLocation> blockBreakpoints() {
        return blockBreakpoints;
    }

    public int selectedSourceIndex() {
        return selectedSourceIndex;
    }

    public @Nullable PauseSource selectedSource() {
        PauseSnapshot current = snapshot;
        return current != null && selectedSourceIndex >= 0 && selectedSourceIndex < current.pauseSources().size()
            ? current.pauseSources().get(selectedSourceIndex) : null;
    }

    public void selectSource(int index) {
        PauseSnapshot current = snapshot;
        if (paused && current != null && index >= 0 && index < current.pauseSources().size()) {
            selectedSourceIndex = index;
        }
    }

    public int selectedFrameIndex() {
        return selectedFrameIndex;
    }

    public void selectFrame(int index) {
        PauseSnapshot current = snapshot;
        if (paused && current != null && index >= 0 && index < current.callStack().size()) {
            selectedFrameIndex = index;
        }
    }

    public GizmoMode gizmoMode() {
        return gizmoMode;
    }

    public void setGizmoMode(GizmoMode mode) {
        gizmoMode = Objects.requireNonNull(mode);
    }

    /** Only a fresh server packet completes a control request; UI never fabricates a pause. */
    public boolean beginControlRequest() {
        if (!paused || snapshot == null || controlPending()) return false;
        controlPending = true;
        controlRequestedAt = clock.getAsLong();
        return true;
    }

    public boolean controlPending() {
        // Rejected commands (for example, lost permission) have no resume packet. Allow a retry
        // without pretending that the server resumed or replacing its authoritative snapshot.
        if (controlPending && clock.getAsLong() - controlRequestedAt >= 2_000_000_000L) controlPending = false;
        return controlPending;
    }

    public void reset() {
        applyResume();
        selectionHint = null;
        blockBreakpoints = List.of();
    }
}
