package works.nuty.codon.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.NbtTreeReader;
import works.nuty.codon.adapter.WatchEditorReader;
import works.nuty.codon.adapter.WatchReader;
import works.nuty.codon.core.model.NbtPage;
import works.nuty.codon.core.model.WatchEditorPage;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.service.DebuggerEngine;
import works.nuty.codon.persistence.WorldWatchPersistence;

/** Server-thread request handlers, reached through the same mailbox as debugger controls. */
final class DebuggerRequestHandler {
    private final DebuggerEngine engine;
    private final WorldWatchPersistence watches;

    DebuggerRequestHandler(DebuggerEngine engine, WorldWatchPersistence watches) {
        this.engine = engine;
        this.watches = watches;
    }

    private static boolean authorized(ServerPlayer player) {
        // Check at execution time: permission may change while a request is waiting in the mailbox.
        return player.connection.isAcceptingMessages()
            && player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }

    private static boolean canReply(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return authorized(player) && ServerPlayNetworking.canSend(player, type.id());
    }

    void query(MinecraftServer server, ServerPlayer player, WatchQueryPayload request) {
        if (!canReply(player, WatchSyncPayload.TYPE)) return;
        var snapshot = engine.currentSnapshot();
        WatchResult result;
        if (!engine.isPaused() || snapshot == null || snapshot.pauseId() != request.pauseId()) {
            result = WatchResult.absent(WatchResult.Status.UNAVAILABLE, "");
        } else {
            try {
                result = WatchReader.read(server, snapshot, request.sourceIndex(), request.spec());
            } catch (IllegalArgumentException invalid) {
                result = WatchResult.absent(WatchResult.Status.INVALID_PATH, "");
            } catch (RuntimeException failure) {
                CodonMod.LOGGER.warn("Could not read debugger watch", failure);
                result = WatchResult.absent(WatchResult.Status.ERROR, "");
            }
        }
        ServerPlayNetworking.send(player, new WatchSyncPayload(request.pauseId(), request.requestId(), result));
    }

    void editor(MinecraftServer server, ServerPlayer player, WatchEditorQueryPayload request) {
        if (!canReply(player, WatchEditorSyncPayload.TYPE)) return;
        var snapshot = engine.currentSnapshot();
        WatchEditorPage page;
        if (request.pauseId() != 0 && (!engine.isPaused() || snapshot == null || snapshot.pauseId() != request.pauseId())) {
            page = WatchEditorPage.absent(WatchResult.Status.UNAVAILABLE);
        } else {
            try {
                page = WatchEditorReader.read(server, request.pauseId() == 0 ? null : snapshot,
                    request.sourceIndex(), request.query());
            } catch (IllegalArgumentException invalid) {
                page = WatchEditorPage.absent(WatchResult.Status.INVALID_PATH);
            } catch (RuntimeException failure) {
                CodonMod.LOGGER.warn("Could not read Watch editor data", failure);
                page = WatchEditorPage.absent(WatchResult.Status.ERROR);
            }
        }
        ServerPlayNetworking.send(player, new WatchEditorSyncPayload(request.pauseId(), request.requestId(), page));
    }

    void nbt(MinecraftServer server, ServerPlayer player, NbtTreeQueryPayload request) {
        if (!canReply(player, NbtTreeSyncPayload.TYPE)) return;
        var snapshot = engine.currentSnapshot();
        NbtPage page;
        if (!engine.isPaused() || snapshot == null || snapshot.pauseId() != request.pauseId()) {
            page = NbtPage.absent(WatchResult.Status.UNAVAILABLE);
        } else {
            try {
                page = NbtTreeReader.read(server, snapshot, request.sourceIndex(), request.path(), request.offset());
            } catch (RuntimeException failure) {
                CodonMod.LOGGER.warn("Could not read debugger NBT", failure);
                page = NbtPage.absent(WatchResult.Status.ERROR);
            }
        }
        ServerPlayNetworking.send(player, new NbtTreeSyncPayload(request.pauseId(), request.requestId(), page));
    }

    void save(ServerPlayer player, WatchSavePayload request) {
        if (!authorized(player)) {
            acknowledge(player, request.transferId(), WatchSaveSyncPayload.Status.FAILED);
            return;
        }
        try {
            // Only the authenticated sender identifies the owner; packets cannot name another player/world.
            var result = watches.saveChunk(player.getUUID(), request.transferId(), request.offset(),
                request.last(), request.definitions());
            if (result == WorldWatchPersistence.ChunkSaveResult.SAVE_FAILED) {
                player.sendSystemMessage(Component.translatable("codon.watch.feedback.save_failed"));
                acknowledge(player, request.transferId(), WatchSaveSyncPayload.Status.FAILED);
            } else if (result == WorldWatchPersistence.ChunkSaveResult.INVALID) {
                player.sendSystemMessage(Component.translatable("codon.watch.feedback.invalid_saved"));
                acknowledge(player, request.transferId(), WatchSaveSyncPayload.Status.INVALID);
            } else if (request.last()) {
                acknowledge(player, request.transferId(), WatchSaveSyncPayload.Status.SAVED);
            }
        } catch (IllegalArgumentException invalid) {
            watches.resetTransfer(player.getUUID());
            player.sendSystemMessage(Component.translatable("codon.watch.feedback.invalid_saved"));
            acknowledge(player, request.transferId(), WatchSaveSyncPayload.Status.INVALID);
        }
    }

    private static void acknowledge(ServerPlayer player, long transferId, WatchSaveSyncPayload.Status status) {
        if (player.connection.isAcceptingMessages() && ServerPlayNetworking.canSend(player, WatchSaveSyncPayload.TYPE.id()))
            ServerPlayNetworking.send(player, new WatchSaveSyncPayload(transferId, status));
    }
}
