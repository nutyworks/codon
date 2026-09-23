package works.nuty.codon.network;

import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import works.nuty.codon.adapter.BreakpointStageParser;
import works.nuty.codon.adapter.FunctionSourceRepository;
import works.nuty.codon.core.model.FunctionSourceDocument;
import works.nuty.codon.core.model.SourceLocation;

/** Registers and serves bounded, parse-only breakpoint-stage previews on the server thread. */
public final class BreakpointStagePreviewNetworking {
    private BreakpointStagePreviewNetworking() { }

    public static void registerPayloadTypes() {
        PayloadTypeRegistry.serverboundPlay().register(BreakpointStagePreviewRequestPayload.TYPE,
            BreakpointStagePreviewRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BreakpointStagePreviewSyncPayload.TYPE,
            BreakpointStagePreviewSyncPayload.CODEC);
    }

    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(BreakpointStagePreviewRequestPayload.TYPE,
            (payload, context) -> preview(context.server(), context.player(), payload));
    }

    private static void preview(MinecraftServer server, ServerPlayer player, BreakpointStagePreviewRequestPayload request) {
        if (!canReply(player, BreakpointStagePreviewSyncPayload.TYPE)) return;
        if (!authorized(player)) {
            send(player, request, BreakpointStagePreviewSyncPayload.Status.UNAUTHORIZED, "", List.of());
            return;
        }
        try {
            Optional<String> command = savedCommand(server, request.location());
            if (command.isEmpty()) {
                send(player, request, BreakpointStagePreviewSyncPayload.Status.NOT_FOUND, "", List.of());
                return;
            }
            BreakpointStagePreviewWire.command(command.get());
            Optional<List<BreakpointStagePreviewSyncPayload.StageSpan>> stages = BreakpointStageParser.parse(
                server, server.createCommandSourceStack(), command.get());
            if (stages.isEmpty()) {
                send(player, request, BreakpointStagePreviewSyncPayload.Status.INVALID, "", List.of());
                return;
            }
            send(player, request, BreakpointStagePreviewSyncPayload.Status.READY, command.get(), stages.get());
        } catch (RuntimeException invalid) {
            send(player, request, BreakpointStagePreviewSyncPayload.Status.INVALID, "", List.of());
        }
    }

    private static Optional<String> savedCommand(MinecraftServer server, SourceLocation location) {
        return switch (location) {
            case SourceLocation.Block block -> commandBlock(server, block);
            case SourceLocation.Function function -> functionLine(server, function);
            case SourceLocation.Player ignored -> Optional.empty();
        };
    }

    private static Optional<String> commandBlock(MinecraftServer server, SourceLocation.Block block) {
        ServerLevel level = null;
        for (ServerLevel candidate : server.getAllLevels()) {
            if (candidate.dimension().identifier().toString().equals(block.block().dimension())) {
                level = candidate;
                break;
            }
        }
        if (level == null) return Optional.empty();
        BlockPos position = new BlockPos(block.block().x(), block.block().y(), block.block().z());
        if (!level.isLoaded(position) || !(level.getBlockEntity(position) instanceof CommandBlockEntity entity)) return Optional.empty();
        return Optional.of(entity.getCommandBlock().getCommand());
    }

    private static Optional<String> functionLine(MinecraftServer server, SourceLocation.Function function) {
        Optional<FunctionSourceDocument> document = FunctionSourceRepository.read(server, function.location().function());
        int line = function.location().line();
        if (document.isEmpty() || line < 1 || line > document.get().lines().size()) return Optional.empty();
        String command = document.get().lines().get(line - 1).trim();
        return command.isEmpty() || command.startsWith("#") || command.startsWith("$") ? Optional.empty() : Optional.of(command);
    }

    private static boolean authorized(ServerPlayer player) {
        return player.connection.isAcceptingMessages()
            && player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }

    private static boolean canReply(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return player.connection.isAcceptingMessages() && ServerPlayNetworking.canSend(player, type.id());
    }

    private static void send(ServerPlayer player, BreakpointStagePreviewRequestPayload request,
                             BreakpointStagePreviewSyncPayload.Status status, String command,
                             List<BreakpointStagePreviewSyncPayload.StageSpan> stages) {
        ServerPlayNetworking.send(player, new BreakpointStagePreviewSyncPayload(request.requestId(), status,
            request.location(), command, stages));
    }
}
