package works.nuty.codon.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.service.DebuggerEngine;
import works.nuty.codon.persistence.WorldWatchPersistence;
import works.nuty.codon.persistence.WatchDefinitions;

import java.util.List;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registers debugger request/sync payloads and brings authorized clients up to date on join or
 * promotion (stored watches, breakpoints, plus an active pause).
 */
public final class CodonNetworking {
    private static final AtomicLong nextWatchTransferId = new AtomicLong();
    private CodonNetworking() {
    }

    public static void registerPayloadTypes() {
        PayloadTypeRegistry.serverboundPlay().register(WatchQueryPayload.TYPE, WatchQueryPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(WatchEditorQueryPayload.TYPE, WatchEditorQueryPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(WatchSavePayload.TYPE, WatchSavePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(WatchSaveV2Payload.TYPE, WatchSaveV2Payload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(NbtTreeQueryPayload.TYPE, NbtTreeQueryPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(BreakpointEditPayload.TYPE, BreakpointEditPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PauseSyncPayload.TYPE, PauseSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WatchSyncPayload.TYPE, WatchSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WatchChangesSyncPayload.TYPE, WatchChangesSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WatchChangesUnavailablePayload.TYPE, WatchChangesUnavailablePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(NbtTreeSyncPayload.TYPE, NbtTreeSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WatchDefinitionsSyncPayload.TYPE, WatchDefinitionsSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WatchRestoreFailedPayload.TYPE, WatchRestoreFailedPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WatchEditorSyncPayload.TYPE, WatchEditorSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WatchSaveSyncPayload.TYPE, WatchSaveSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WatchSavePageAckPayload.TYPE, WatchSavePageAckPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ResumeSyncPayload.TYPE, ResumeSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StepSyncPayload.TYPE, StepSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ContinueSyncPayload.TYPE, ContinueSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BreakpointSyncPayload.TYPE, BreakpointSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BreakpointDefinitionsSyncPayload.TYPE, BreakpointDefinitionsSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BreakpointEditResultPayload.TYPE, BreakpointEditResultPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ControlRejectedPayload.TYPE, ControlRejectedPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ExecutionFlowSyncPayload.TYPE, ExecutionFlowSyncPayload.CODEC);
        SourceBrowseNetworking.registerPayloadTypes();
        BreakpointStagePreviewNetworking.registerPayloadTypes();
    }

    public static void registerRequests(DebuggerEngine engine, WorldWatchPersistence watches) {
        var requests = new DebuggerRequestHandler(engine, watches);
        ServerPlayNetworking.registerGlobalReceiver(WatchQueryPayload.TYPE,
            (payload, context) -> requests.query(context.server(), context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(WatchEditorQueryPayload.TYPE,
            (payload, context) -> requests.editor(context.server(), context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(WatchSavePayload.TYPE,
            (payload, context) -> requests.save(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(WatchSaveV2Payload.TYPE,
            (payload, context) -> requests.save(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(NbtTreeQueryPayload.TYPE,
            (payload, context) -> requests.nbt(context.server(), context.player(), payload));
        var breakpointEdits = new BreakpointEditHandler(engine);
        ServerPlayNetworking.registerGlobalReceiver(BreakpointEditPayload.TYPE,
            (payload, context) -> breakpointEdits.edit(context.server(), context.player(), payload));
        SourceBrowseNetworking.registerServerReceivers();
        BreakpointStagePreviewNetworking.registerServerReceivers();
    }

    public static void registerJoinSync(DebuggerEngine engine, WorldWatchPersistence watches, NetworkDebuggerEventSink eventSink) {
        Map<ServerGamePacketListenerImpl, Set<CustomPacketPayload.Type<?>>> synchronizedOwners = new IdentityHashMap<>();
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            synchronizeOwner(player, engine, watches, eventSink, synchronizedOwners);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                // Promotion after JOIN still needs the handshake which enables watch persistence.
                synchronizeOwner(player, engine, watches, eventSink, synchronizedOwners);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            synchronizedOwners.remove(handler);
            watches.resetTransfer(handler.getPlayer().getUUID());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> synchronizedOwners.clear());
    }

    private static void synchronizeOwner(ServerPlayer player, DebuggerEngine engine,
                                          WorldWatchPersistence watches, NetworkDebuggerEventSink eventSink,
                                          Map<ServerGamePacketListenerImpl, Set<CustomPacketPayload.Type<?>>> synchronizedOwners) {
        var connection = player.connection;
        if (!NetworkDebuggerEventSink.authorized(player)) {
            var sent = synchronizedOwners.get(connection);
            // Preserve completed or rejected restores through re-promotion; neither may replace
            // local edits or retry the same oversized snapshot on every tick.
            if (sent != null) sent.retainAll(Set.of(WatchDefinitionsSyncPayload.TYPE, WatchRestoreFailedPayload.TYPE));
            return;
        }
        var sent = synchronizedOwners.computeIfAbsent(connection, ignored -> new HashSet<>());
        // Unsupported channels stay pending without resending other handshakes on each tick.
        if (!sent.contains(WatchDefinitionsSyncPayload.TYPE)
            && !sent.contains(WatchRestoreFailedPayload.TYPE)
            && ServerPlayNetworking.canSend(player, WatchDefinitionsSyncPayload.TYPE.id())) {
            synchronizeWatches(player, watches, sent);
        }

        if (!sent.contains(BreakpointSyncPayload.TYPE) && ServerPlayNetworking.canSend(player, BreakpointSyncPayload.TYPE.id())) {
            ServerPlayNetworking.send(player, new BreakpointSyncPayload(List.copyOf(engine.blockBreakpoints())));
            sent.add(BreakpointSyncPayload.TYPE);
        }
        if (!sent.contains(BreakpointDefinitionsSyncPayload.TYPE)
            && ServerPlayNetworking.canSend(player, BreakpointDefinitionsSyncPayload.TYPE.id())) {
            eventSink.sendBreakpointDefinitions(player, engine.breakpointDefinitions());
            sent.add(BreakpointDefinitionsSyncPayload.TYPE);
        }

        if (!sent.contains(PauseSyncPayload.TYPE) && ServerPlayNetworking.canSend(player, PauseSyncPayload.TYPE.id())) {
            PauseSnapshot snapshot = engine.currentSnapshot();
            if (snapshot != null && eventSink.sendPauseSnapshot(player, snapshot)) {
                eventSink.sendWatchChanges(player, snapshot.pauseId());
            }
            sent.add(PauseSyncPayload.TYPE);
        }
    }

    private static void synchronizeWatches(ServerPlayer player, WorldWatchPersistence watches,
                                           Set<CustomPacketPayload.Type<?>> sent) {
        List<List<works.nuty.codon.core.model.WatchSpec>> pages;
        try {
            pages = WatchDefinitions.validatedPages(watches.get(player.getUUID()));
        } catch (IllegalArgumentException tooLarge) {
            // No prefix or empty success: both would hide saved definitions from later edits.
            sent.add(WatchRestoreFailedPayload.TYPE);
            if (ServerPlayNetworking.canSend(player, WatchRestoreFailedPayload.TYPE.id()))
                ServerPlayNetworking.send(player, new WatchRestoreFailedPayload());
            player.sendSystemMessage(Component.translatableWithFallback("codon.watch.restore.failed",
                "Saved watches are too large to restore. Saved data is preserved; edits are session-only. "
                    + "Reduce the saved list while the world is closed, then reopen it."));
            return;
        }
        long transferId = nextTransferId();
        int offset = 0;
        for (int index = 0; index < pages.size(); index++) {
            List<works.nuty.codon.core.model.WatchSpec> page = pages.get(index);
            ServerPlayNetworking.send(player, new WatchDefinitionsSyncPayload(transferId, offset,
                index == pages.size() - 1, page));
            offset += page.size();
        }
        sent.add(WatchDefinitionsSyncPayload.TYPE);
    }

    private static long nextTransferId() {
        long id = nextWatchTransferId.updateAndGet(previous -> previous == Long.MAX_VALUE ? 1 : previous + 1);
        return id == 0 ? nextWatchTransferId.incrementAndGet() : id;
    }
}
