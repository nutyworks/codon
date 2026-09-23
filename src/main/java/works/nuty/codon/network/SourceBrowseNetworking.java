package works.nuty.codon.network;

import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.FunctionSourceRepository;
import works.nuty.codon.core.model.FunctionId;
import works.nuty.codon.core.model.FunctionSourceDocument;

/** Registration and server-thread handlers for the read-only function source browser. */
public final class SourceBrowseNetworking {
    private SourceBrowseNetworking() { }

    /** Called by the common networking composition root before play connections are accepted. */
    public static void registerPayloadTypes() {
        PayloadTypeRegistry.serverboundPlay().register(FunctionSourceListRequestPayload.TYPE, FunctionSourceListRequestPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(FunctionSourceReadRequestPayload.TYPE, FunctionSourceReadRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(FunctionSourceListSyncPayload.TYPE, FunctionSourceListSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(FunctionSourceReadSyncPayload.TYPE, FunctionSourceReadSyncPayload.CODEC);
    }

    /** Called by the common networking composition root after the server is available. */
    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(FunctionSourceListRequestPayload.TYPE,
            (payload, context) -> sendList(context.server(), context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(FunctionSourceReadRequestPayload.TYPE,
            (payload, context) -> sendSource(context.server(), context.player(), payload));
    }

    private static boolean authorized(ServerPlayer player) {
        return player.connection.isAcceptingMessages()
            && player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }

    private static boolean canReply(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return player.connection.isAcceptingMessages() && ServerPlayNetworking.canSend(player, type.id());
    }

    private static void sendList(MinecraftServer server, ServerPlayer player, FunctionSourceListRequestPayload request) {
        if (!canReply(player, FunctionSourceListSyncPayload.TYPE)) return;
        if (!authorized(player)) {
            ServerPlayNetworking.send(player, new FunctionSourceListSyncPayload(request.requestId(),
                FunctionSourceListSyncPayload.Status.UNAUTHORIZED, 0, true, List.of()));
            return;
        }
        try {
            sendListPages(player, request.requestId(), FunctionSourceRepository.list(server));
        } catch (RuntimeException failure) {
            CodonMod.LOGGER.warn("Could not list datapack functions for source browser", failure);
            ServerPlayNetworking.send(player, new FunctionSourceListSyncPayload(request.requestId(),
                FunctionSourceListSyncPayload.Status.ERROR, 0, true, List.of()));
        }
    }

    private static void sendListPages(ServerPlayer player, long requestId, List<FunctionId> functions) {
        for (int offset = 0; ; offset += FunctionSourceListSyncPayload.PAGE_SIZE) {
            int end = Math.min(functions.size(), offset + FunctionSourceListSyncPayload.PAGE_SIZE);
            boolean last = end == functions.size();
            ServerPlayNetworking.send(player, new FunctionSourceListSyncPayload(requestId,
                FunctionSourceListSyncPayload.Status.OK, offset, last, functions.subList(offset, end)));
            if (last) return;
        }
    }

    private static void sendSource(MinecraftServer server, ServerPlayer player, FunctionSourceReadRequestPayload request) {
        if (!canReply(player, FunctionSourceReadSyncPayload.TYPE)) return;
        if (!authorized(player)) {
            sendReadFailure(player, request, FunctionSourceReadSyncPayload.Status.UNAUTHORIZED);
            return;
        }
        try {
            FunctionSourceRepository.read(server, request.function()).ifPresentOrElse(
                document -> sendSourcePages(player, request.requestId(), document),
                () -> sendReadFailure(player, request, FunctionSourceReadSyncPayload.Status.NOT_FOUND)
            );
        } catch (RuntimeException failure) {
            CodonMod.LOGGER.warn("Could not read datapack function for source browser", failure);
            sendReadFailure(player, request, FunctionSourceReadSyncPayload.Status.ERROR);
        }
    }

    private static void sendSourcePages(ServerPlayer player, long requestId, FunctionSourceDocument document) {
        for (int offset = 0; ; ) {
            int end = offset;
            int pageChars = 0;
            while (end < document.lines().size() && end - offset < FunctionSourceReadSyncPayload.PAGE_SIZE) {
                int lineChars = document.lines().get(end).length();
                if (end > offset && pageChars + lineChars > FunctionSourceReadSyncPayload.MAX_PAGE_CHARS) break;
                pageChars += lineChars;
                end++;
            }
            boolean last = end == document.lines().size();
            ServerPlayNetworking.send(player, new FunctionSourceReadSyncPayload(requestId,
                FunctionSourceReadSyncPayload.Status.OK, document.id(), document.provider(), document.revision(),
                document.truncated(), offset, last, document.lines().subList(offset, end)));
            if (last) return;
            offset = end;
        }
    }

    private static void sendReadFailure(ServerPlayer player, FunctionSourceReadRequestPayload request,
                                        FunctionSourceReadSyncPayload.Status status) {
        ServerPlayNetworking.send(player, new FunctionSourceReadSyncPayload(request.requestId(), status,
            request.function(), "", "", false, 0, true, List.of()));
    }
}
