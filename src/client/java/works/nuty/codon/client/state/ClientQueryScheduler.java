package works.nuty.codon.client.state;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Bounds the paused client's read burst below the server's 32-request connection budget. */
public final class ClientQueryScheduler {
    // Editor and NBT have their own credits so a large Watch list cannot starve inspection UI.
    private static final int WATCH_LIMIT = 12;
    private static final int EDITOR_LIMIT = 1;
    private static final int NBT_LIMIT = 3;
    private static final long REPLY_TIMEOUT_NANOS = 5_000_000_000L;
    private static final long STEP_DEADLINE_NANOS = 10_000_000_000L;

    public interface Sender {
        void editor(ClientWatchEditorState.Query query);
        void watch(ClientWatchState.Query query);
        void nbt(ClientNbtState.Query query);
        void control(long pauseId, String command);
    }

    private record Ticket(long pauseId, long sentAt) { }
    private record PendingControl(long pauseId, String command, long queuedAt) { }

    private final LongSupplier clock;
    private final Map<Long, Ticket> watch = new HashMap<>();
    private final Map<Long, Ticket> editor = new HashMap<>();
    private final Map<Long, Ticket> nbt = new HashMap<>();
    private long activePause;
    private long failedPause;
    private PendingControl pendingControl;

    public ClientQueryScheduler(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }

    public void reset() {
        watch.clear();
        editor.clear();
        nbt.clear();
        activePause = 0;
        failedPause = 0;
        pendingControl = null;
    }

    public void watchReply(long pauseId, long requestId) { release(watch, pauseId, requestId); }
    public void editorReply(long pauseId, long requestId) { release(editor, pauseId, requestId); }
    public void nbtReply(long pauseId, long requestId) { release(nbt, pauseId, requestId); }

    private static void release(Map<Long, Ticket> tickets, long pauseId, long requestId) {
        Ticket ticket = tickets.get(requestId);
        if (ticket != null && ticket.pauseId() == pauseId) tickets.remove(requestId);
    }

    /** Resume can supersede a waiting step; a failed read cancels step without advancing execution. */
    public void requestControl(ClientDebuggerState state, long pauseId, String command,
                               boolean readBeforeStep, Sender sender) {
        if (!readBeforeStep) {
            pendingControl = null;
            sender.control(pauseId, command);
            state.controlSent();
            return;
        }
        if (failedPause == pauseId) {
            state.failWatchReads();
            return;
        }
        pendingControl = new PendingControl(pauseId, command, clock.getAsLong());
        state.deferControlForReads();
        pump(state, sender);
    }

    public void pump(ClientDebuggerState state, Sender sender) {
        long now = clock.getAsLong();
        var snapshot = state.snapshot();
        long pauseId = state.isPaused() && snapshot != null ? snapshot.pauseId() : 0;
        if (activePause != pauseId) {
            // A completed step has already passed the old queue entries on the server's FIFO.
            watch.clear();
            editor.clear();
            nbt.clear();
            activePause = pauseId;
            failedPause = 0;
        }
        PendingControl control = pendingControl;
        if (control != null && control.pauseId() != pauseId) pendingControl = control = null;
        if (control != null && !state.controlAwaitingReads()) {
            // A repeated pause packet reset the UI request; never revive its old step later.
            failedPause = pauseId;
            state.watches().failUnresolvedQueries();
            state.failWatchReads();
            watch.clear();
            editor.clear();
            nbt.clear();
            pendingControl = control = null;
        }
        if (failedPause == pauseId && pauseId > 0) return;

        if (pauseId > 0 && (state.watches().hasTimedOutQueries()
            || watch.values().stream().anyMatch(ticket -> now - ticket.sentAt() >= REPLY_TIMEOUT_NANOS)
            || control != null && now - control.queuedAt() >= STEP_DEADLINE_NANOS)) {
            // Admission is unknown without a reply. Stop this pause's reads instead of recycling
            // credits or sending a step that could overtake a dropped Watch request.
            failedPause = pauseId;
            state.watches().failUnresolvedQueries();
            state.failWatchReads();
            watch.clear();
            editor.clear();
            nbt.clear();
            pendingControl = null;
        }
        if (failedPause == pauseId && pauseId > 0) return;

        state.watchEditor().waiting(); // Apply its timeout before claiming another editor credit.
        if (editor.size() < EDITOR_LIMIT) for (var query : state.watchEditor().drainQueries()) {
            editor.put(query.requestId(), new Ticket(query.pauseId(), now));
            sender.editor(query);
        }
        if (pauseId <= 0) return;

        for (var query : state.watches().drainQueries(WATCH_LIMIT - watch.size())) {
            watch.put(query.requestId(), new Ticket(query.pauseId(), now));
            sender.watch(query);
        }
        for (var query : state.nbt().drainQueries(NBT_LIMIT - nbt.size())) {
            nbt.put(query.requestId(), new Ticket(query.pauseId(), now));
            sender.nbt(query);
        }

        control = pendingControl;
        if (control != null && control.pauseId() == pauseId && state.controlAwaitingReads()
            && watch.isEmpty() && !state.watches().hasUnresolvedQueries()) {
            // Every Watch replied; admitted reads precede control in the FIFO.
            sender.control(pauseId, control.command());
            state.controlSent();
            pendingControl = null;
        }
    }
}
