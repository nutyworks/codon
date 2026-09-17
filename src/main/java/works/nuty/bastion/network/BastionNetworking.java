package works.nuty.bastion.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import works.nuty.bastion.core.model.PauseSnapshot;
import works.nuty.bastion.core.service.DebuggerEngine;

import java.util.List;

/**
 * Registers the S2C sync payload types and brings newly joined clients up to date (current block
 * breakpoints, plus the active pause if the debugger is parked when they connect).
 */
public final class BastionNetworking {
    private BastionNetworking() {
    }

    public static void registerPayloadTypes() {
        PayloadTypeRegistry.clientboundPlay().register(PauseSyncPayload.TYPE, PauseSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ResumeSyncPayload.TYPE, ResumeSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StepSyncPayload.TYPE, StepSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ContinueSyncPayload.TYPE, ContinueSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BreakpointSyncPayload.TYPE, BreakpointSyncPayload.CODEC);
    }

    public static void registerJoinSync(DebuggerEngine engine) {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();

            if (ServerPlayNetworking.canSend(player, BreakpointSyncPayload.TYPE.id())) {
                ServerPlayNetworking.send(player, new BreakpointSyncPayload(List.copyOf(engine.blockBreakpoints())));
            }

            PauseSnapshot snapshot = engine.currentSnapshot();
            if (snapshot != null && ServerPlayNetworking.canSend(player, PauseSyncPayload.TYPE.id())) {
                ServerPlayNetworking.send(player, new PauseSyncPayload(snapshot));
            }
        });
    }
}
