package works.nuty.codon.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.WatchReader;
import works.nuty.codon.adapter.WatchEditorReader;
import works.nuty.codon.core.model.WatchEditorPage;
import works.nuty.codon.core.model.WatchEditorQuery;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.core.service.DebuggerEngine;
import works.nuty.codon.network.WatchSyncPayload;
import works.nuty.codon.network.WatchEditorQueryCodec;
import works.nuty.codon.network.WatchEditorSyncPayload;
import works.nuty.codon.network.WatchSaveSyncPayload;
import works.nuty.codon.persistence.WatchDefinitions;
import works.nuty.codon.persistence.WorldWatchPersistence;

/** Inherits /codon's owner permission and its paused-server control-only mailbox. */
final class WatchCommand {
    private WatchCommand() {}

    static LiteralArgumentBuilder<CommandSourceStack> node(DebuggerEngine engine, WorldWatchPersistence watches) {
        var watch = Commands.literal("watch");
        watch.then(Commands.literal("save").then(Commands.argument("definitions", StringArgumentType.greedyString())
            .executes(c -> saveDefinitions(c, watches))));
        watch.then(Commands.literal("save_chunk").then(Commands.argument("transfer", LongArgumentType.longArg(1))
            .then(Commands.argument("offset", IntegerArgumentType.integer(0))
                .then(Commands.argument("last", BoolArgumentType.bool())
                    .then(Commands.argument("definitions", StringArgumentType.greedyString())
                        .executes(c -> saveChunk(c, watches)))))));
        watch.then(Commands.literal("editor")
            .then(Commands.argument("pause", LongArgumentType.longArg(0))
                .then(Commands.argument("request", LongArgumentType.longArg(1))
                    .then(Commands.argument("context", IntegerArgumentType.integer(-1))
                        .then(Commands.argument("query", StringArgumentType.greedyString())
                            .executes(c -> editor(c, engine)))))));

        var source = Commands.argument("context", IntegerArgumentType.integer(-1));
        source.then(Commands.literal("score").then(Commands.argument("target", StringArgumentType.greedyString())
            .executes(c -> query(c, engine, WatchSpec.Kind.SCORE))));
        source.then(Commands.literal("entity").then(Commands.argument("path", StringArgumentType.greedyString())
            .executes(c -> query(c, engine, WatchSpec.Kind.ENTITY_NBT))));
        source.then(Commands.literal("storage").then(Commands.argument("target", IdentifierArgument.id())
            .then(Commands.argument("path", StringArgumentType.greedyString())
                .executes(c -> query(c, engine, WatchSpec.Kind.STORAGE_NBT)))));

        var captured = Commands.literal("captured");
        var uuid = Commands.argument("uuid", StringArgumentType.word());
        uuid.then(Commands.literal("score").then(Commands.argument("target", StringArgumentType.greedyString())
            .executes(c -> queryCaptured(c, engine, WatchSpec.Kind.SCORE))));
        uuid.then(Commands.literal("entity").then(Commands.argument("path", StringArgumentType.greedyString())
            .executes(c -> queryCaptured(c, engine, WatchSpec.Kind.ENTITY_NBT))));
        captured.then(uuid);

        var request = Commands.argument("request", LongArgumentType.longArg(1));
        request.then(source);
        request.then(captured);
        watch.then(Commands.argument("pause", LongArgumentType.longArg(1)).then(request));
        return watch;
    }

    private static int saveDefinitions(CommandContext<CommandSourceStack> context, WorldWatchPersistence watches) {
        var player = context.getSource().getPlayer();
        if (player == null) return 0;
        try {
            // Ownership is always the authenticated sender; the client cannot name another player or world.
            if (!watches.save(player.getUUID(), WatchDefinitions.fromJson(StringArgumentType.getString(context, "definitions")))) {
                context.getSource().sendFailure(Component.translatable("codon.watch.feedback.save_failed"));
                return 0;
            }
            return 1;
        } catch (IllegalArgumentException invalid) {
            context.getSource().sendFailure(Component.translatable("codon.watch.feedback.invalid_saved"));
            return 0;
        }
    }

    private static int saveChunk(CommandContext<CommandSourceStack> context, WorldWatchPersistence watches) {
        var player = context.getSource().getPlayer();
        if (player == null) return 0;
        long transferId = LongArgumentType.getLong(context, "transfer");
        try {
            // Ownership is always the authenticated sender; no player or world identity is accepted from the client.
            WorldWatchPersistence.ChunkSaveResult result = watches.saveChunk(player.getUUID(), transferId,
                IntegerArgumentType.getInteger(context, "offset"), BoolArgumentType.getBool(context, "last"),
                WatchDefinitions.fromPageJson(StringArgumentType.getString(context, "definitions")));
            if (result == WorldWatchPersistence.ChunkSaveResult.SAVE_FAILED) {
                context.getSource().sendFailure(Component.translatable("codon.watch.feedback.save_failed"));
                acknowledge(player, transferId, WatchSaveSyncPayload.Status.FAILED);
                return 0;
            }
            if (result == WorldWatchPersistence.ChunkSaveResult.INVALID) {
                context.getSource().sendFailure(Component.translatable("codon.watch.feedback.invalid_saved"));
                acknowledge(player, transferId, WatchSaveSyncPayload.Status.INVALID);
                return 0;
            }
            if (BoolArgumentType.getBool(context, "last")) acknowledge(player, transferId, WatchSaveSyncPayload.Status.SAVED);
            return 1;
        } catch (IllegalArgumentException invalid) {
            watches.resetTransfer(player.getUUID());
            context.getSource().sendFailure(Component.translatable("codon.watch.feedback.invalid_saved"));
            acknowledge(player, transferId, WatchSaveSyncPayload.Status.INVALID);
            return 0;
        }
    }

    private static void acknowledge(net.minecraft.server.level.ServerPlayer player, long transferId, WatchSaveSyncPayload.Status status) {
        if (ServerPlayNetworking.canSend(player, WatchSaveSyncPayload.TYPE.id()))
            ServerPlayNetworking.send(player, new WatchSaveSyncPayload(transferId, status));
    }

    private static int editor(CommandContext<CommandSourceStack> context, DebuggerEngine engine) {
        var player = context.getSource().getPlayer();
        if (player == null || !ServerPlayNetworking.canSend(player, WatchEditorSyncPayload.TYPE.id())) return 0;
        long pauseId = LongArgumentType.getLong(context, "pause");
        long requestId = LongArgumentType.getLong(context, "request");
        WatchEditorPage page;
        try {
            WatchEditorQuery query = WatchEditorQueryCodec.fromJson(StringArgumentType.getString(context, "query"));
            var snapshot = engine.currentSnapshot();
            if (pauseId != 0 && (!engine.isPaused() || snapshot == null || snapshot.pauseId() != pauseId)) {
                page = WatchEditorPage.absent(WatchResult.Status.UNAVAILABLE);
            } else {
                page = WatchEditorReader.read(context.getSource().getServer(), pauseId == 0 ? null : snapshot,
                    IntegerArgumentType.getInteger(context, "context"), query);
            }
        } catch (IllegalArgumentException e) {
            page = WatchEditorPage.absent(WatchResult.Status.INVALID_PATH);
        } catch (RuntimeException e) {
            CodonMod.LOGGER.warn("Could not read Watch editor data", e);
            page = WatchEditorPage.absent(WatchResult.Status.ERROR);
        }
        ServerPlayNetworking.send(player, new WatchEditorSyncPayload(pauseId, requestId, page));
        return 1;
    }

    private static int query(CommandContext<CommandSourceStack> context, DebuggerEngine engine, WatchSpec.Kind kind) {
        var source = context.getSource();
        var player = source.getPlayer();
        if (player == null || !ServerPlayNetworking.canSend(player, WatchSyncPayload.TYPE.id())) return 0;
        long pauseId = LongArgumentType.getLong(context, "pause");
        long requestId = LongArgumentType.getLong(context, "request");
        int sourceIndex = IntegerArgumentType.getInteger(context, "context");
        var snapshot = engine.currentSnapshot();
        WatchResult result;
        if (!engine.isPaused() || snapshot == null || snapshot.pauseId() != pauseId) {
            result = WatchResult.absent(WatchResult.Status.UNAVAILABLE, "");
        } else {
            try {
                var spec = new WatchSpec(kind,
                    kind == WatchSpec.Kind.ENTITY_NBT ? "" : kind == WatchSpec.Kind.STORAGE_NBT
                        ? IdentifierArgument.getId(context, "target").toString() : StringArgumentType.getString(context, "target"),
                    kind == WatchSpec.Kind.SCORE ? "" : StringArgumentType.getString(context, "path"));
                result = WatchReader.read(source.getServer(), snapshot, sourceIndex, spec);
            } catch (IllegalArgumentException e) {
                result = WatchResult.absent(WatchResult.Status.INVALID_PATH, "");
            } catch (RuntimeException e) {
                CodonMod.LOGGER.warn("Could not read debugger watch", e);
                result = WatchResult.absent(WatchResult.Status.ERROR, "");
            }
        }
        ServerPlayNetworking.send(player, new WatchSyncPayload(pauseId, requestId, result));
        return 1;
    }

    private static int queryCaptured(CommandContext<CommandSourceStack> context, DebuggerEngine engine, WatchSpec.Kind kind) {
        var source = context.getSource();
        var player = source.getPlayer();
        if (player == null || !ServerPlayNetworking.canSend(player, WatchSyncPayload.TYPE.id())) return 0;
        long pauseId = LongArgumentType.getLong(context, "pause");
        long requestId = LongArgumentType.getLong(context, "request");
        WatchResult result;
        var snapshot = engine.currentSnapshot();
        if (!engine.isPaused() || snapshot == null || snapshot.pauseId() != pauseId) {
            result = WatchResult.absent(WatchResult.Status.UNAVAILABLE, "");
        } else {
            try {
                var uuid = java.util.UUID.fromString(StringArgumentType.getString(context, "uuid"));
                String target = kind == WatchSpec.Kind.SCORE
                    ? StringArgumentType.getString(context, "target") : "";
                String path = kind == WatchSpec.Kind.SCORE ? "" : StringArgumentType.getString(context, "path");
                result = WatchReader.readCapturedEntity(source.getServer(), uuid, new WatchSpec(kind, target, path));
            } catch (IllegalArgumentException e) {
                result = WatchResult.absent(WatchResult.Status.INVALID_PATH, "");
            } catch (RuntimeException e) {
                CodonMod.LOGGER.warn("Could not read captured debugger watch", e);
                result = WatchResult.absent(WatchResult.Status.ERROR, "");
            }
        }
        ServerPlayNetworking.send(player, new WatchSyncPayload(pauseId, requestId, result));
        return 1;
    }
}
