package works.nuty.bastion.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.ExecutionFlowTrace;
import works.nuty.bastion.core.model.PauseSnapshot;
import works.nuty.bastion.core.port.DebuggerEventSink;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Minecraft adapter for {@link DebuggerEventSink}: broadcasts debugger state to every connected
 * client that has Bastion installed. This is what makes the in-game UI work on dedicated servers —
 * the client renders from synced state rather than reaching into the server's memory.
 */
public final class NetworkDebuggerEventSink implements DebuggerEventSink {
    private final Supplier<MinecraftServer> server;

    public NetworkDebuggerEventSink(Supplier<MinecraftServer> server) {
        this.server = server;
    }

    @Override
    public void paused(PauseSnapshot snapshot) {
        broadcast(new PauseSyncPayload(snapshot));
    }

    @Override
    public void resumed() {
        broadcast(new ResumeSyncPayload());
    }

    @Override
    public void continued() {
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
