package works.nuty.codon.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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
import works.nuty.codon.core.port.DebuggerEventSink;

import java.util.List;
import java.util.Set;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Minecraft adapter for {@link DebuggerEventSink}: broadcasts debugger state to every connected
 * client that has Codon installed. This is what makes the in-game UI work on dedicated servers —
 * the client renders from synced state rather than reaching into the server's memory.
 */
public final class NetworkDebuggerEventSink implements DebuggerEventSink {
    private final Supplier<MinecraftServer> server;
    private final PauseWatchChanges watchChanges = new PauseWatchChanges();
    private static final AtomicLong nextBreakpointTransferId = new AtomicLong();
    private long changesPauseId;
    private List<WatchChange> changes = List.of();

    public NetworkDebuggerEventSink(Supplier<MinecraftServer> server) {
        this.server = server;
    }

    @Override
    public void paused(PauseSnapshot snapshot) {
        MinecraftServer s = server.get();
        changesPauseId = snapshot.pauseId();
        changes = List.of();
        if (s != null) {
            try {
                changes = watchChanges.capture(s, snapshot);
            } catch (RuntimeException failure) {
                watchChanges.reset();
                CodonMod.LOGGER.warn("Could not capture pause watch changes", failure);
            }
        }
        broadcast(new PauseSyncPayload(snapshot));
        if (s != null) for (ServerPlayer player : s.getPlayerList().getPlayers()) sendWatchChanges(player, snapshot.pauseId());
    }

    public void sendWatchChanges(ServerPlayer player, long pauseId) {
        if (pauseId <= 0 || pauseId != changesPauseId
            || !player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_OWNER)
            || !ServerPlayNetworking.canSend(player, WatchChangesSyncPayload.TYPE.id())) return;
        for (int offset = 0; ; offset += WatchChangesSyncPayload.PAGE_SIZE) {
            int end = Math.min(changes.size(), offset + WatchChangesSyncPayload.PAGE_SIZE);
            boolean last = end == changes.size();
            ServerPlayNetworking.send(player, new WatchChangesSyncPayload(pauseId, offset, last, changes.subList(offset, end)));
            if (last) break;
        }
    }

    public void resetWatchChanges() {
        watchChanges.reset();
        changes = List.of();
        changesPauseId = 0;
    }

    @Override
    public void resumed() {
        resetWatchChanges();
        broadcast(new ResumeSyncPayload());
    }

    @Override
    public void continued() {
        // A later breakpoint in this execution still compares against the preceding stop.
        changes = List.of();
        changesPauseId = 0;
        MinecraftServer s = server.get();
        if (s == null) return;
        for (ServerPlayer player : s.getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(player, ContinueSyncPayload.TYPE.id())) {
                ServerPlayNetworking.send(player, new ContinueSyncPayload());
            } else if (ServerPlayNetworking.canSend(player, ResumeSyncPayload.TYPE.id())) {
                ServerPlayNetworking.send(player, new ResumeSyncPayload());
            }
        }
    }

    @Override
    public void stepping() {
        changes = List.of();
        changesPauseId = 0;
        MinecraftServer s = server.get();
        if (s == null) return;
        for (ServerPlayer player : s.getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(player, StepSyncPayload.TYPE.id())) {
                ServerPlayNetworking.send(player, new StepSyncPayload());
            } else if (ServerPlayNetworking.canSend(player, ResumeSyncPayload.TYPE.id())) {
                // Keep the original resume protocol usable by clients without step support.
                ServerPlayNetworking.send(player, new ResumeSyncPayload());
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
        if (!player.connection.isAcceptingMessages()
            || !player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_OWNER)
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
            if (ServerPlayNetworking.canSend(player, payload.type().id())) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }
}
