package works.nuty.codon.client;

import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSetCommandBlockPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.adapter.SourceMapper;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.BreakpointCondition;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.core.service.CommandStageEvent;
import works.nuty.codon.network.NbtTreeQueryPayload;
import works.nuty.codon.network.NbtTreeSyncPayload;
import works.nuty.codon.network.WatchQueryPayload;
import works.nuty.codon.network.WatchSavePayload;
import works.nuty.codon.network.WatchSaveSyncPayload;
import works.nuty.codon.network.WatchSyncPayload;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Covers the private C2S debugger request boundary while a production engine has parked the
 * integrated server. Replies are observed below the UI so permission failures cannot be hidden
 * by client state handling.
 */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerRequestTransportGameTest implements FabricClientGameTest {
    private static final BlockLocation BREAKPOINT = new BlockLocation(71, 80, 0, "minecraft:overworld");
    private static final WatchSpec SCORE = new WatchSpec(WatchSpec.Kind.SCORE, "transport_points", "");

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            assertCommandBlockAccess(world);
            world.getServer().runCommand("scoreboard objectives add transport_points dummy");
            world.getServer().runCommand("scoreboard players set @a transport_points 10");
            MinecraftServer server = world.getServer().computeOnServer(value -> value);
            AtomicBoolean completed = new AtomicBoolean();
            AtomicBoolean ordinaryTask = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Replies replies = new Replies();

            world.getServer().runOnServer(value -> {
                var player = value.getPlayerList().getPlayers().getFirst();
                value.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
                require(player.createCommandSourceStack().permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_OWNER),
                    "fixture player has debugger-owner permission");
                assertPublicCommandTree(value.createCommandSourceStack());
                CodonMod.engine().clearBreakpoints();
            });

            InstalledObservers observers = context.computeOnClient(client -> installObservers(replies));
            try {
                server.execute(() -> {
                    var engine = CodonMod.engine();
                    engine.onExecutionStarted();
                    try {
                        var player = server.getPlayerList().getPlayers().getFirst();
                        engine.toggleBlockBreakpoint(BREAKPOINT);
                        var sources = SourceMapper.toPauseSources(List.of(player.createCommandSourceStack()));
                        engine.onCommandStage(new CommandStageEvent(981001, 0, new SourceLocation.Block(BREAKPOINT),
                            CommandSnippet.plain("scoreboard players set @s transport_points 11"), () -> sources));
                        server.getScoreboard().getOrCreatePlayerScore(player,
                            server.getScoreboard().getObjective("transport_points")).set(11);
                        engine.onCommandStage(new CommandStageEvent(981002, 0, new SourceLocation.Block(BREAKPOINT),
                            CommandSnippet.plain("say transport-next-stage"), () -> sources));
                    } catch (Throwable problem) {
                        failure.set(problem);
                    } finally {
                        engine.onExecutionFinished(failure.get() == null);
                        engine.clearBreakpoints();
                        completed.set(true);
                    }
                });

                context.waitFor(client -> CodonClientMod.state().isPaused(), 200);
                long firstPause = context.computeOnClient(client -> CodonClientMod.state().snapshot().pauseId());
                require(CodonMod.engine().isPaused(), "fixture genuinely parks the server");
                server.execute(() -> ordinaryTask.set(true));
                context.waitTicks(3);
                require(!ordinaryTask.get(), "normal server work stays blocked while request mailbox remains live");

                context.runOnClient(client -> {
                    ClientPlayNetworking.send(new WatchQueryPayload(firstPause, 101, 0, SCORE));
                    // The request packet must precede this control command in the parked-server FIFO.
                    client.player.connection.sendCommand("codon stepinto");
                });
                context.waitFor(client -> replies.watch(101) != null && CodonClientMod.state().isPaused()
                    && CodonClientMod.state().snapshot().pauseId() != firstPause, 200);
                require(replies.watch(101).result().status() == WatchResult.Status.VALUE
                        && replies.watch(101).result().value().equals("10"),
                    "watch request observes the pre-step value before the next stage mutates it");
                require(!ordinaryTask.get(), "query and step do not drain ordinary server tasks");

                long secondPause = context.computeOnClient(client -> CodonClientMod.state().snapshot().pauseId());
                context.runOnClient(client -> {
                    ClientPlayNetworking.send(new WatchQueryPayload(firstPause, 102, 0, SCORE));
                    ClientPlayNetworking.send(new NbtTreeQueryPayload(secondPause, 103, 0, 0, ""));
                });
                context.waitFor(client -> replies.watch(102) != null && replies.nbt(103) != null, 200);
                require(replies.watch(102).result().status() == WatchResult.Status.UNAVAILABLE,
                    "an old pause ID receives an explicit unavailable result");
                require(replies.nbt(103).page().status() == WatchResult.Status.VALUE,
                    "an owner can request the selected executor NBT over C2S while parked");

                context.runOnClient(client -> {
                    for (String action : new String[]{"resume", "stepinto", "stepover", "stepout"}) {
                        client.player.connection.sendCommand("codon " + action + " " + firstPause);
                    }
                    // This query follows all stale controls through the same mailbox.
                    ClientPlayNetworking.send(new WatchQueryPayload(secondPause, 108, 0, SCORE));
                });
                context.waitFor(client -> replies.watch(108) != null, 200);
                require(replies.watch(108).result().status() == WatchResult.Status.VALUE
                        && replies.watch(108).result().value().equals("11")
                        && CodonMod.engine().isPaused() && CodonMod.engine().currentSnapshot().pauseId() == secondPause,
                    "delayed controls for the first pause must leave the second pause unchanged");

                setOwner(context, server, false);
                context.runOnClient(client -> {
                    ClientPlayNetworking.send(new WatchQueryPayload(secondPause, 104, 0, SCORE));
                    ClientPlayNetworking.send(new NbtTreeQueryPayload(secondPause, 105, 0, 0, ""));
                    ClientPlayNetworking.send(new WatchSavePayload(106, 0, true, List.of(SCORE)));
                });
                context.waitFor(client -> replies.save(106) != null, 200);
                context.waitTicks(5);
                require(replies.watch(104) == null && replies.nbt(105) == null,
                    "a non-owner receives no read reply and cannot inspect paused data");
                require(replies.save(106).status() == WatchSaveSyncPayload.Status.FAILED,
                    "a non-owner cannot persist watch definitions");

                setOwner(context, server, true);
                context.runOnClient(client -> ClientPlayNetworking.send(new WatchQueryPayload(secondPause, 107, 0, SCORE)));
                context.waitFor(client -> replies.watch(107) != null, 200);
                require(replies.watch(107).result().value().equals("11"),
                    "permission is checked at request execution time and an owner can read again");

                context.runOnClient(client -> client.player.connection.sendCommand("codon stepinto " + secondPause));
                context.waitFor(client -> CodonClientMod.state().isPaused()
                    && CodonClientMod.state().snapshot().reason() == works.nuty.codon.core.model.PauseReason.EXECUTION_COMPLETE, 200);
                context.runOnClient(client -> client.player.connection.sendCommand("codon stepinto"));
                context.waitFor(client -> completed.get() && !CodonClientMod.state().isPaused(), 200);
                if (failure.get() != null) throw new AssertionError("parked transport fixture failed", failure.get());
                context.waitFor(client -> ordinaryTask.get(), 200);
                CodonMod.LOGGER.info("Paused control transport PASS: stale IDs rejected for all four actions; current ID and unversioned steps accepted");
            } finally {
                AtomicBoolean cleaned = new AtomicBoolean();
                DebuggerTaskQueue.execute(server, () -> {
                    CodonMod.engine().clearBreakpoints();
                    CodonMod.engine().resetSession();
                    cleaned.set(true);
                });
                context.waitFor(client -> completed.get() && cleaned.get(), 200);
                context.runOnClient(client -> uninstallObservers(observers));
            }
        }
    }

    private static void assertPublicCommandTree(CommandSourceStack source) {
        CommandNode<CommandSourceStack> codon = source.getServer().getCommands().getDispatcher().getRoot().getChild("codon");
        require(codon != null, "codon command remains registered");
        var children = codon.getChildren().stream().map(CommandNode::getName).collect(java.util.stream.Collectors.toSet());
        require(!children.contains("watch") && !children.contains("nbt"),
            "internal request transports are absent from the public codon command tree");
        require(children.containsAll(List.of("breakpoint", "resume", "stepinto", "stepover", "stepout")),
            "public breakpoint and stepping controls remain available");
    }

    private static void assertCommandBlockAccess(TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            var level = player.level();
            var loaded = player.blockPosition().offset(2, 0, 0);
            var unloaded = new BlockPos(12_000_000, loaded.getY(), -12_000_000);
            player.setGameMode(GameType.CREATIVE);
            level.setBlock(loaded, Blocks.COMMAND_BLOCK.defaultBlockState(), 3);
            var block = (CommandBlockEntity) level.getBlockEntity(loaded);
            block.getCommandBlock().setCommand("say before");
            var target = BreakpointTarget.whole(new SourceLocation.Block(
                SourceMapper.toBlockLocation(loaded, level.dimension().identifier().toString())));
            CodonMod.engine().saveBreakpoint(new BreakpointDefinition(target, true,
                BreakpointCondition.count(BreakpointCondition.Kind.OUTPUT_COUNT, BreakpointCondition.Comparison.GT, 0)));
            server.getPlayerList().deop(player.nameAndId());
            require(!player.canUseGameMasterBlocks(), "fixture editor is unauthorized");
            require(level.getChunkSource().getChunkNow(unloaded.getX() >> 4, unloaded.getZ() >> 4) == null,
                "fixture target chunk starts absent");
            for (var pos : List.of(unloaded, loaded)) {
                player.connection.handleSetCommandBlock(new ServerboundSetCommandBlockPacket(pos, "say denied",
                    CommandBlockEntity.Mode.REDSTONE, true, false, false));
            }
            DebuggerTaskQueue.drain(server);
            require(level.getChunkSource().getChunkNow(unloaded.getX() >> 4, unloaded.getZ() >> 4) == null,
                "unauthorized command-block packet must not acquire a chunk");
            require(block.getCommandBlock().getCommand().equals("say before"), "unauthorized edit is rejected");
            require(CodonMod.engine().breakpointDefinitions().stream().anyMatch(definition ->
                definition.target().equals(target) && definition.enabled()), "denied edits preserve result conditions");
            server.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
            player.connection.handleSetCommandBlock(new ServerboundSetCommandBlockPacket(loaded, "say accepted",
                CommandBlockEntity.Mode.REDSTONE, true, false, false));
            DebuggerTaskQueue.drain(server);
            require(block.getCommandBlock().getCommand().equals("say accepted"), "authorized loaded edit remains valid");
            require(CodonMod.engine().breakpointDefinitions().stream().anyMatch(definition ->
                definition.target().equals(target) && !definition.enabled() && definition.staleSource()),
                "the actual authorized mutation invalidates old result conditions");
            CodonMod.engine().clearBreakpoints();
            level.removeBlock(loaded, false);
        });
    }

    private static void setOwner(ClientGameTestContext context, MinecraftServer server, boolean owner) {
        AtomicBoolean changed = new AtomicBoolean();
        AtomicBoolean authorized = new AtomicBoolean();
        DebuggerTaskQueue.execute(server, () -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            if (owner) {
                server.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
            } else {
                server.getPlayerList().deop(player.nameAndId());
            }
            authorized.set(player.createCommandSourceStack().permissions()
                .hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_OWNER));
            changed.set(true);
        });
        context.waitFor(client -> changed.get(), 200);
        require(authorized.get() == owner, "fixture updates COMMANDS_OWNER before the next request");
    }

    private static InstalledObservers installObservers(Replies replies) {
        var watch = removeLocalReceiver(WatchSyncPayload.TYPE);
        var nbt = removeLocalReceiver(NbtTreeSyncPayload.TYPE);
        var save = removeLocalReceiver(WatchSaveSyncPayload.TYPE);
        require(watch != null && nbt != null && save != null, "the live client has the normal reply handlers to preserve");
        require(ClientPlayNetworking.registerReceiver(WatchSyncPayload.TYPE, (payload, context) -> {
                replies.watches.add(payload);
                watch.receive(payload, context);
            }),
            "install a connection-local Watch reply observer");
        require(ClientPlayNetworking.registerReceiver(NbtTreeSyncPayload.TYPE, (payload, context) -> {
                replies.nbts.add(payload);
                nbt.receive(payload, context);
            }),
            "install a connection-local NBT reply observer");
        require(ClientPlayNetworking.registerReceiver(WatchSaveSyncPayload.TYPE, (payload, context) -> {
                replies.saves.add(payload);
                save.receive(payload, context);
            }),
            "install a connection-local save acknowledgement observer");
        return new InstalledObservers(watch, nbt, save);
    }

    private static void uninstallObservers(InstalledObservers observers) {
        ClientPlayNetworking.unregisterReceiver(WatchSyncPayload.TYPE.id());
        ClientPlayNetworking.unregisterReceiver(NbtTreeSyncPayload.TYPE.id());
        ClientPlayNetworking.unregisterReceiver(WatchSaveSyncPayload.TYPE.id());
        require(ClientPlayNetworking.registerReceiver(WatchSyncPayload.TYPE, observers.watch), "restore Watch reply handler");
        require(ClientPlayNetworking.registerReceiver(NbtTreeSyncPayload.TYPE, observers.nbt), "restore NBT reply handler");
        require(ClientPlayNetworking.registerReceiver(WatchSaveSyncPayload.TYPE, observers.save), "restore save reply handler");
    }

    @SuppressWarnings("unchecked")
    private static <T extends net.minecraft.network.protocol.common.custom.CustomPacketPayload>
    ClientPlayNetworking.PlayPayloadHandler<T> removeLocalReceiver(net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<T> type) {
        return (ClientPlayNetworking.PlayPayloadHandler<T>) ClientPlayNetworking.unregisterReceiver(type.id());
    }

    private record InstalledObservers(ClientPlayNetworking.PlayPayloadHandler<WatchSyncPayload> watch,
                                      ClientPlayNetworking.PlayPayloadHandler<NbtTreeSyncPayload> nbt,
                                      ClientPlayNetworking.PlayPayloadHandler<WatchSaveSyncPayload> save) {
    }

    private static final class Replies {
        final List<WatchSyncPayload> watches = new CopyOnWriteArrayList<>();
        final List<NbtTreeSyncPayload> nbts = new CopyOnWriteArrayList<>();
        final List<WatchSaveSyncPayload> saves = new CopyOnWriteArrayList<>();

        WatchSyncPayload watch(long requestId) { return watches.stream().filter(value -> value.requestId() == requestId).findFirst().orElse(null); }
        NbtTreeSyncPayload nbt(long requestId) { return nbts.stream().filter(value -> value.requestId() == requestId).findFirst().orElse(null); }
        WatchSaveSyncPayload save(long transferId) { return saves.stream().filter(value -> value.transferId() == transferId).findFirst().orElse(null); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
