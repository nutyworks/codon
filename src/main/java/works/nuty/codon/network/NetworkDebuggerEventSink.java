package works.nuty.codon.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.PauseWatchChanges;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.WatchChange;
import works.nuty.codon.core.model.TransferBudget;
import works.nuty.codon.core.port.DebuggerEventSink;

import java.util.List;
import java.util.Set;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Minecraft adapter for {@link DebuggerEventSink}: broadcasts debugger state to connected,
 * owner-authorized clients that support it. This makes the in-game UI work on dedicated servers —
 * the client renders from synced state rather than reaching into the server's memory.
 */
public final class NetworkDebuggerEventSink implements DebuggerEventSink {
    private final Supplier<MinecraftServer> server;
    private final PauseWatchChanges watchChanges = new PauseWatchChanges();
    private static final AtomicLong nextBreakpointTransferId = new AtomicLong();
    private long changesPauseId;
    private List<WatchChange> changes = List.of();
    private WatchChangesUnavailablePayload.Reason changesFailure;

    public NetworkDebuggerEventSink(Supplier<MinecraftServer> server) {
        this.server = server;
    }

    @Override
    public void paused(PauseSnapshot snapshot) {
        MinecraftServer s = server.get();
        changesPauseId = snapshot.pauseId();
        changes = List.of();
        changesFailure = null;
        if (!ClientboundLimits.supports(snapshot)) {
            resetWatchChanges();
        } else if (s != null) {
            try {
                changes = watchChanges.capture(s, snapshot);
                if (!fitsChangeBudget(changes)) {
                    changesFailure = WatchChangesUnavailablePayload.Reason.TOO_LARGE;
                    changes = List.of();
                }
            } catch (RuntimeException failure) {
                watchChanges.reset();
                changesFailure = WatchChangesUnavailablePayload.Reason.CAPTURE_FAILED;
                changes = List.of();
                CodonMod.LOGGER.warn("Could not capture pause watch changes", failure);
            }
        }
        if (s != null) for (ServerPlayer player : s.getPlayerList().getPlayers()) {
            if (sendPauseSnapshot(player, snapshot)) sendWatchChanges(player, snapshot.pauseId());
        }
    }

    /** Shared live/JOIN boundary: never truncate source indices or disconnect an owner on encode. */
    public boolean sendPauseSnapshot(ServerPlayer player, PauseSnapshot snapshot) {
        if (!authorized(player) || !ServerPlayNetworking.canSend(player, PauseSyncPayload.TYPE.id())) return false;
        if (!ClientboundLimits.supports(snapshot)) {
            sendResume(player);
            // Only presentation is cleared. The engine remains paused and its controls remain usable.
            player.sendSystemMessage(Component.translatableWithFallback("codon.pause.snapshot_too_large",
                "Codon is paused, but this snapshot is too large to display. Use /codon resume to continue."));
            return false;
        }
        ServerPlayNetworking.send(player, new PauseSyncPayload(snapshot));
        return true;
    }

    public void sendWatchChanges(ServerPlayer player, long pauseId) {
        if (pauseId <= 0 || pauseId != changesPauseId
            || !authorized(player)) return;
        if (changesFailure != null) {
            if (ServerPlayNetworking.canSend(player, WatchChangesUnavailablePayload.TYPE.id())) {
                ServerPlayNetworking.send(player, new WatchChangesUnavailablePayload(pauseId, changesFailure));
            } else {
                // Older peers cannot decode the new outcome, so provide a visible fallback.
                String key = changesFailure == WatchChangesUnavailablePayload.Reason.TOO_LARGE
                    ? "codon.watch.changes.too_large" : "codon.watch.changes.unavailable";
                String fallback = changesFailure == WatchChangesUnavailablePayload.Reason.TOO_LARGE
                    ? "Automatic Watch changes were too large to display at this pause."
                    : "Automatic Watch changes are unavailable at this pause.";
                player.sendSystemMessage(Component.translatableWithFallback(key, fallback));
            }
            return;
        }
        if (!ServerPlayNetworking.canSend(player, WatchChangesSyncPayload.TYPE.id())) return;
        for (int offset = 0; ; offset += WatchChangesSyncPayload.PAGE_SIZE) {
            int end = Math.min(changes.size(), offset + WatchChangesSyncPayload.PAGE_SIZE);
            boolean last = end == changes.size();
            ServerPlayNetworking.send(player, new WatchChangesSyncPayload(pauseId, offset, last, changes.subList(offset, end)));
            if (last) break;
        }
    }

    static boolean fitsChangeBudget(List<WatchChange> rows) {
        if (rows.size() > TransferBudget.WATCH_CHANGES.entries()) return false;
        long remaining = TransferBudget.WATCH_CHANGES.characters();
        for (WatchChange row : rows) {
            long characters = TransferBudget.characters(row.spec())
                + TransferBudget.characters(row.before()) + TransferBudget.characters(row.after());
            if (characters > remaining) return false;
            remaining -= characters;
        }
        // The row cap also limits 32-row pages to 128, below MAX_PAGES.
        return true;
    }

    public void resetWatchChanges() {
        watchChanges.reset();
        changes = List.of();
        changesFailure = null;
        changesPauseId = 0;
    }

    @Override
    public void resumed() {
        resetWatchChanges();
        MinecraftServer s = server.get();
        if (s != null) for (ServerPlayer player : s.getPlayerList().getPlayers()) sendResume(player);
    }

    @Override
    public void continued() {
        // A later breakpoint in this execution still compares against the preceding stop.
        changes = List.of();
        changesFailure = null;
        changesPauseId = 0;
        MinecraftServer s = server.get();
        if (s == null) return;
        for (ServerPlayer player : s.getPlayerList().getPlayers()) {
            if (authorized(player) && ServerPlayNetworking.canSend(player, ContinueSyncPayload.TYPE.id())) {
                ServerPlayNetworking.send(player, new ContinueSyncPayload());
            } else {
                sendResume(player);
            }
        }
    }

    @Override
    public void stepping() {
        changes = List.of();
        changesFailure = null;
        changesPauseId = 0;
        MinecraftServer s = server.get();
        if (s == null) return;
        for (ServerPlayer player : s.getPlayerList().getPlayers()) {
            if (authorized(player) && ServerPlayNetworking.canSend(player, StepSyncPayload.TYPE.id())) {
                ServerPlayNetworking.send(player, new StepSyncPayload());
            } else {
                sendResume(player);
            }
        }
    }

    @Override
    public void executionFlowsCompleted(List<ExecutionFlowTrace> flows) {
        broadcast(new ExecutionFlowSyncPayload(flows));
    }

    @Override
    public void breakpointsChanged(Set<BlockLocation> blockBreakpoints) {
        broadcast(new BreakpointSyncPayload(List.copyOf(blockBreakpoints)));
        MinecraftServer current = server.get();
        var engine = CodonMod.engine();
        if (current == null || engine == null) return;
        List<BreakpointDefinition> definitions = engine.breakpointDefinitions();
        for (ServerPlayer player : current.getPlayerList().getPlayers()) sendBreakpointDefinitions(player, definitions);
    }

    public void sendBreakpointDefinitions(ServerPlayer player, List<BreakpointDefinition> definitions) {
        if (!authorized(player)
            || !ServerPlayNetworking.canSend(player, BreakpointDefinitionsSyncPayload.TYPE.id())) return;
        long transferId = nextBreakpointTransferId.incrementAndGet();
        List<BreakpointDefinition> sorted = definitions.stream()
            .sorted(Comparator.comparing(definition -> definition.target().toString())).toList();
        int offset = 0;
        do {
            int end = Math.min(sorted.size(), offset + BreakpointDefinitionsSyncPayload.PAGE_SIZE);
            ServerPlayNetworking.send(player, new BreakpointDefinitionsSyncPayload(transferId, offset,
                end == sorted.size(), sorted.subList(offset, end)));
            offset = end;
        } while (offset < sorted.size());
    }

    private void broadcast(CustomPacketPayload payload) {
        MinecraftServer s = server.get();
        if (s == null) {
            return;
        }
        for (ServerPlayer player : s.getPlayerList().getPlayers()) {
            if (authorized(player) && ServerPlayNetworking.canSend(player, payload.type().id())) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    static boolean authorized(ServerPlayer player) {
        // Capability advertisement is not authorization; recheck live permission for each send.
        return player.connection.isAcceptingMessages()
            && player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }

    private static void sendResume(ServerPlayer player) {
        // Empty terminal cleanup must reach former owners, and keeps legacy clients usable.
        // Step/Continue would retain freecam while their subsequent owner-only pauses are withheld.
        if (player.connection.isAcceptingMessages() && ServerPlayNetworking.canSend(player, ResumeSyncPayload.TYPE.id())) {
            ServerPlayNetworking.send(player, new ResumeSyncPayload());
        }
    }
}
