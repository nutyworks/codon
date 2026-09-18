package works.nuty.codon.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.service.DebuggerEngine;
import works.nuty.codon.persistence.WorldWatchPersistence;
import works.nuty.codon.persistence.WatchDefinitions;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registers the S2C sync payload types and brings newly joined clients up to date (current block
 * breakpoints, plus the active pause if the debugger is parked when they connect).
 */
public final class CodonNetworking {
    private static final AtomicLong nextWatchTransferId = new AtomicLong();
    private CodonNetworking() {
    }

    public static void registerPayloadTypes() {
        PayloadTypeRegistry.clientboundPlay().register(PauseSyncPayload.TYPE, PauseSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WatchSyncPayload.TYPE, WatchSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(NbtTreeSyncPayload.TYPE, NbtTreeSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WatchDefinitionsSyncPayload.TYPE, WatchDefinitionsSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ResumeSyncPayload.TYPE, ResumeSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StepSyncPayload.TYPE, StepSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ContinueSyncPayload.TYPE, ContinueSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BreakpointSyncPayload.TYPE, BreakpointSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ExecutionFlowSyncPayload.TYPE, ExecutionFlowSyncPayload.CODEC);
    }

    public static void registerJoinSync(DebuggerEngine engine, WorldWatchPersistence watches) {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (ServerPlayNetworking.canSend(player, WatchDefinitionsSyncPayload.TYPE.id())) {
                long transferId = nextTransferId();
                int offset = 0;
                List<List<works.nuty.codon.core.model.WatchSpec>> pages = WatchDefinitions.pages(watches.get(player.getUUID()));
                for (int index = 0; index < pages.size(); index++) {
                    List<works.nuty.codon.core.model.WatchSpec> page = pages.get(index);
                    ServerPlayNetworking.send(player, new WatchDefinitionsSyncPayload(transferId, offset,
                        index == pages.size() - 1, page));
                    offset += page.size();
                }
            }

            if (ServerPlayNetworking.canSend(player, BreakpointSyncPayload.TYPE.id())) {
                ServerPlayNetworking.send(player, new BreakpointSyncPayload(List.copyOf(engine.blockBreakpoints())));
            }

            PauseSnapshot snapshot = engine.currentSnapshot();
            if (snapshot != null && ServerPlayNetworking.canSend(player, PauseSyncPayload.TYPE.id())) {
                ServerPlayNetworking.send(player, new PauseSyncPayload(snapshot));
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> watches.resetTransfer(handler.getPlayer().getUUID()));
    }

    private static long nextTransferId() {
        long id = nextWatchTransferId.updateAndGet(previous -> previous == Long.MAX_VALUE ? 1 : previous + 1);
        return id == 0 ? nextWatchTransferId.incrementAndGet() : id;
    }
}
