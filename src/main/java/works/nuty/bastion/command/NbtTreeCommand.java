package works.nuty.bastion.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import works.nuty.bastion.adapter.NbtTreeReader;
import works.nuty.bastion.core.model.NbtPage;
import works.nuty.bastion.core.service.DebuggerEngine;
import works.nuty.bastion.network.NbtTreeSyncPayload;

final class NbtTreeCommand {
    private NbtTreeCommand() {}

    static LiteralArgumentBuilder<CommandSourceStack> node(DebuggerEngine engine) {
        return Commands.literal("nbt")
            .then(Commands.argument("pause", LongArgumentType.longArg(1))
                .then(Commands.argument("request", LongArgumentType.longArg(1))
                    .then(Commands.argument("source", IntegerArgumentType.integer(-1))
                        .then(Commands.argument("offset", IntegerArgumentType.integer(0))
                            .then(Commands.literal("root").executes(c -> query(c, engine, "")))
                            .then(Commands.literal("path")
                                .then(Commands.argument("path", StringArgumentType.greedyString())
                                    .executes(c -> query(c, engine, StringArgumentType.getString(c, "path")))))))));
    }

    private static int query(CommandContext<CommandSourceStack> context, DebuggerEngine engine, String path) {
        var player = context.getSource().getPlayer();
        if (player == null || !ServerPlayNetworking.canSend(player, NbtTreeSyncPayload.TYPE.id())) return 0;
        long pauseId = LongArgumentType.getLong(context, "pause");
        long requestId = LongArgumentType.getLong(context, "request");
        NbtPage page;
        var snapshot = engine.currentSnapshot();
        if (!engine.isPaused() || snapshot == null || snapshot.pauseId() != pauseId) page = NbtPage.absent(works.nuty.bastion.core.model.WatchResult.Status.UNAVAILABLE);
        else {
            try { page = NbtTreeReader.read(context.getSource().getServer(), snapshot, IntegerArgumentType.getInteger(context, "source"), path, IntegerArgumentType.getInteger(context, "offset")); }
            catch (RuntimeException e) { page = NbtPage.absent(works.nuty.bastion.core.model.WatchResult.Status.ERROR); }
        }
        ServerPlayNetworking.send(player, new NbtTreeSyncPayload(pauseId, requestId, page));
        return 1;
    }
}
