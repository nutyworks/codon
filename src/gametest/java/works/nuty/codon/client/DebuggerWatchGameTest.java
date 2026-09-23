package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.adapter.McExecutionController;
import works.nuty.codon.adapter.SourceMapper;
import works.nuty.codon.adapter.WatchReader;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.ClientWatchState;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.client.ui.WatchScreen;
import works.nuty.codon.core.model.*;
import works.nuty.codon.core.service.CommandStageEvent;
import works.nuty.codon.network.PauseSyncPayload;
import works.nuty.codon.network.WatchSyncPayload;
import works.nuty.codon.core.model.PauseReason;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Real parked-server query transport. Stage fixtures drive the production engine and mailbox. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerWatchGameTest implements FabricClientGameTest {
    private static final WatchSpec SCORE = new WatchSpec(WatchSpec.Kind.SCORE, "watch_points", "");
    private static final WatchSpec ENTITY = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "UUID");
    private static final WatchSpec STORAGE = new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "codon:watch_test", "count");
    private static final WatchSpec UNSET = new WatchSpec(WatchSpec.Kind.SCORE, "watch_unset", "");
    private static final WatchSpec NO_OBJECTIVE = new WatchSpec(WatchSpec.Kind.SCORE, "watch_absent", "");
    private static final WatchSpec NAMED = WatchSpec.scoreHolder("watch_points", " #fake \"counter\" \\ value ");
    private static final WatchSpec NAMED_UNSET = WatchSpec.scoreHolder("watch_unset", "#never-created");

    @Override
    public void runTest(ClientGameTestContext context) {
        checkPathReadsAndCodec();
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            world.getServer().runCommand("scoreboard objectives add watch_points dummy");
            world.getServer().runCommand("scoreboard objectives add watch_unset dummy");
            world.getServer().runCommand("scoreboard players set @a watch_points 10");
            world.getServer().runCommand("data merge storage codon:watch_test {count:10}");
            MinecraftServer server = world.getServer().computeOnServer(s -> s);
            world.getServer().runOnServer(s -> {
                s.getScoreboard().getOrCreatePlayerScore(net.minecraft.world.scores.ScoreHolder.forNameOnly(NAMED.scoreHolder()),
                    s.getScoreboard().getObjective("watch_points")).set(40);
                var player = s.getPlayerList().getPlayers().getFirst();
                s.getPlayerList().op(player.nameAndId(),
                    java.util.Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER), java.util.Optional.empty());
                require(player.createCommandSourceStack().permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_OWNER),
                    "watch fixture explicitly grants the same owner permission required by debugger controls");
            });
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean completed = new AtomicBoolean();
            AtomicBoolean ordinaryTask = new AtomicBoolean();
            context.runOnClient(client -> {
                var state = CodonClientMod.state();
                state.reset();
                for (var spec : List.of(SCORE, ENTITY, STORAGE, UNSET, NO_OBJECTIVE, NAMED, NAMED_UNSET))
                    require(state.watches().add(spec), "add fixture watch");
            });
            try {
                // Schedule without blocking the test thread: onCommandStage really parks this server.
                server.execute(() -> {
                    var engine = CodonMod.engine();
                    engine.onExecutionStarted();
                    try {
                        var player = server.getPlayerList().getPlayers().getFirst();
                        var location = new SourceLocation.Block(new BlockLocation(0, 80, 0, "minecraft:overworld"));
                        engine.toggleBlockBreakpoint(location.block());
                        // A changed execution dimension must still resolve the attached entity by UUID.
                        var sources = SourceMapper.toPauseSources(List.of(player.createCommandSourceStack()
                            .withLevel(server.getLevel(net.minecraft.world.level.Level.NETHER))));
                        engine.onCommandStage(new CommandStageEvent(900001, 0, location,
                            CommandSnippet.plain("scoreboard players add @s watch_points 1"), () -> sources));
                        server.getScoreboard().getOrCreatePlayerScore(player, server.getScoreboard().getObjective("watch_points")).set(11);
                        server.getScoreboard().getOrCreatePlayerScore(net.minecraft.world.scores.ScoreHolder.forNameOnly(NAMED.scoreHolder()),
                            server.getScoreboard().getObjective("watch_points")).set(41);
                        var storage = server.getCommandStorage().get(Identifier.parse("codon:watch_test")).copy();
                        storage.putInt("count", 11);
                        server.getCommandStorage().set(Identifier.parse("codon:watch_test"), storage);
                        engine.onCommandStage(new CommandStageEvent(900002, 0,
                            new SourceLocation.Block(new BlockLocation(1, 80, 0, "minecraft:overworld")),
                            CommandSnippet.plain("scoreboard players add @s watch_points 1"), () -> sources));
                        server.getScoreboard().getOrCreatePlayerScore(player, server.getScoreboard().getObjective("watch_points")).set(12);
                        storage.putInt("count", 12);
                        server.getCommandStorage().set(Identifier.parse("codon:watch_test"), storage);
                        require(server.getScoreboard().getPlayerScoreInfo(player, server.getScoreboard().getObjective("watch_unset")) == null,
                            "watch reads never create unset scores");
                        require(server.getScoreboard().getTrackedPlayers().stream()
                            .noneMatch(holder -> holder.getScoreboardName().equals(NAMED_UNSET.scoreHolder())),
                            "named watch reads never create a holder or its missing score");
                    } catch (Throwable problem) {
                        failure.set(problem);
                    } finally {
                        engine.onExecutionFinished(failure.get() == null);
                        engine.clearBreakpoints();
                        completed.set(true);
                    }
                });
                context.waitFor(client -> ready("10"), 200);
                long firstPause = context.computeOnClient(client -> CodonClientMod.state().snapshot().pauseId());
                var camera = context.computeOnClient(Minecraft::getCameraEntity);
                require(McExecutionController.isParked(), "the production server suspension is active");
                server.execute(() -> ordinaryTask.set(true));
                context.waitTicks(3);
                require(!ordinaryTask.get(), "watch reads do not drain ordinary server tasks");
                context.runOnClient(client -> {
                    var state = CodonClientMod.state();
                    state.watchEditor().request(firstPause, state.selectedPauseSourceIndex(),
                        new works.nuty.codon.core.model.WatchEditorQuery(
                            works.nuty.codon.core.model.WatchEditorQuery.Mode.PREVIEW,
                            WatchSpec.Kind.SCORE, "watch_points", "", null, "", 0, NAMED.scoreHolder()));
                    state.watches().retrySave();
                });
                context.waitFor(client -> CodonClientMod.state().watchEditor().page() != null, 200);
                context.waitFor(client -> CodonClientMod.state().watches().saveStatus() == ClientWatchState.SaveStatus.SAVED, 200);
                context.runOnClient(client -> {
                    var preview = CodonClientMod.state().watchEditor().page().preview();
                    require(preview != null && preview.value().equals("40"), "named editor preview roundtrip works while server is parked");
                    CodonClientMod.state().watchEditor().cancel();
                });
                require(!ordinaryTask.get(), "editor and save ACK do not drain ordinary server tasks");
                context.runOnClient(client -> {
                    require(entry(ENTITY).result().status() == WatchResult.Status.VALUE, "entity UUID NBT resolves across execute-in");
                    for (var spec : List.of(SCORE, ENTITY, UNSET, NO_OBJECTIVE)) {
                        require(entry(spec).result().targetName().equals(client.player.getName().getString()),
                            "entity query replies carry their actual executor name, including absent values");
                    }
                    require(entry(STORAGE).result().targetName().isEmpty(), "storage has no entity label");
                    require(entry(UNSET).result().status() == WatchResult.Status.VALUE_MISSING, "unset score is absent, not zero");
                    require(entry(NO_OBJECTIVE).result().status() == WatchResult.Status.OBJECTIVE_MISSING, "missing objective is distinct");
                    require(entry(NAMED).result().value().equals("40")
                        && entry(NAMED).result().targetName().equals(NAMED.scoreHolder())
                        && entry(NAMED).displayedExecutor() == null,
                        "named score roundtrip preserves quotes/backslashes and never borrows the selected executor");
                    require(entry(NAMED_UNSET).result().status() == WatchResult.Status.VALUE_MISSING,
                        "a missing fake-player score is not zero or a missing entity");
                    client.player.connection.sendCommand("codon stepover");
                });
                context.waitFor(client -> ready("11") && CodonClientMod.state().snapshot().pauseId() != firstPause, 200);
                context.runOnClient(client -> {
                    require(entry(SCORE).change() == ClientWatchState.Change.VALUE_CHANGED
                        && entry(SCORE).previousValue().equals("10"), "score changed 10 to 11");
                    require(entry(STORAGE).change() == ClientWatchState.Change.VALUE_CHANGED, "storage changed at the same step");
                    require(entry(ENTITY).change() == ClientWatchState.Change.UNCHANGED, "unchanged entity NBT stays unhighlighted");
                    require(entry(NAMED).change() == ClientWatchState.Change.VALUE_CHANGED
                        && entry(NAMED).previousValue().equals("40") && entry(NAMED).result().value().equals("41"),
                        "named scores are reread and compared across the actual parked-server step");
                    require(client.getCameraEntity() == camera, "real watch query steps retain freecam identity");
                    checkPauseCodec(CodonClientMod.state().snapshot());
                });
                require(!ordinaryTask.get(), "ordinary server tasks remain deferred at the next stop");
                checkUi(context);
                context.runOnClient(client -> client.player.connection.sendCommand("codon stepover"));
                context.waitFor(client -> ready("12")
                    && CodonClientMod.state().snapshot().reason() == PauseReason.EXECUTION_COMPLETE, 200);
                context.runOnClient(client -> {
                    require(entry(SCORE).previousValue().equals("11") && entry(STORAGE).previousValue().equals("11"),
                        "final command mutations remain inspectable as 11 to 12");
                    require(client.getCameraEntity() == camera, "completion inspection retains freecam");
                    require(!ordinaryTask.get() && !completed.get(), "completion pause still parks the server");
                    checkPauseCodec(CodonClientMod.state().snapshot());
                });
                context.getInput().resizeWindow(1280, 800);
                context.waitTicks(3);
                context.takeScreenshot("codon-watch-final-values");
                context.runOnClient(client -> client.gui.screen().onClose());
                context.waitTicks(3);
                context.takeScreenshot("codon-execution-complete");
                context.runOnClient(client -> client.player.connection.sendCommand("codon stepover"));
                context.waitFor(client -> completed.get() && !CodonClientMod.state().isPaused() && !CodonClientMod.state().isStepping());
                if (failure.get() != null) throw new AssertionError("Server fixture failed", failure.get());
                context.waitFor(client -> ordinaryTask.get());
                context.runOnClient(client -> {
                    require(client.getCameraEntity() == client.player, "terminal step restores the player camera");
                    require(CodonClientMod.state().watches().entries().stream().allMatch(e -> e.result() == null), "terminal resume drops stale watch values");
                    client.setScreenAndShow(null);
                });
            } finally {
                // Release a failed fixture via the same bounded mailbox, never the blocked normal queue.
                AtomicBoolean cleaned = new AtomicBoolean();
                DebuggerTaskQueue.execute(server, () -> {
                    CodonMod.engine().clearBreakpoints();
                    CodonMod.engine().resetSession();
                    cleaned.set(true);
                });
                context.waitFor(client -> completed.get() && cleaned.get(), 200);
                context.runOnClient(client -> client.setScreenAndShow(null));
            }
            checkVanillaQueueCompletion(context, world, server);
        }
    }

    /** Exercise the actual BuildContexts/ExecutionContext mixins without a trailing dummy command. */
    private static void checkVanillaQueueCompletion(ClientGameTestContext context, TestSingleplayerContext world,
                                                    MinecraftServer server) {
        world.getServer().runCommand("scoreboard players set @a watch_points 20");
        world.getServer().runOnServer(s -> {
            CodonMod.engine().clearBreakpoints();
            CodonMod.engine().toggleBlockBreakpoint(new BlockLocation(0, 80, 0, "minecraft:overworld"));
        });
        context.runOnClient(client -> {
            CodonClientMod.state().watches().reset();
            CodonClientMod.state().watches().add(SCORE);
        });
        AtomicBoolean returned = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            server.execute(() -> {
                try {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack()
                        .withPosition(new net.minecraft.world.phys.Vec3(0, 80, 0)),
                        "execute as @a run scoreboard players add @s watch_points 1");
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    returned.set(true);
                }
            });
            context.waitFor(client -> CodonClientMod.state().isPaused(), 200);
            context.runOnClient(client -> client.player.connection.sendCommand("codon stepinto"));
            context.waitFor(client -> CodonClientMod.state().isPaused()
                && entry(SCORE).result() != null && entry(SCORE).result().value().equals("20"), 200);
            // execute-as and run can each expose a modifier stage before the executable stage.
            // Advance those genuine stages, checking fresh pause IDs rather than assuming a fixed count.
            for (int stage = 0; stage < 8; stage++) {
                context.runOnClient(client -> {
                    var stop = CodonClientMod.state().snapshot();
                    require(stop.command().text().equals("execute as @a run scoreboard players add @s watch_points 1"),
                        "late watch/control commands never become debugged stages: " + stop.command().text());
                });
                if (context.computeOnClient(client -> CodonClientMod.state().snapshot().reason() == PauseReason.EXECUTION_COMPLETE)) break;
                long previousPause = context.computeOnClient(client -> CodonClientMod.state().snapshot().pauseId());
                context.runOnClient(client -> client.player.connection.sendCommand("codon stepover"));
                context.waitFor(client -> CodonClientMod.state().isPaused()
                    && CodonClientMod.state().snapshot().pauseId() != previousPause, 200);
            }
            context.waitFor(client -> CodonClientMod.state().isPaused()
                && CodonClientMod.state().snapshot().reason() == PauseReason.EXECUTION_COMPLETE
                && entry(SCORE).result() != null && entry(SCORE).result().value().equals("21"), 200);
            context.runOnClient(client -> {
                require(entry(SCORE).previousValue().equals("20"), "real final command changes score 20 to 21");
                require(!returned.get(), "native command queue remains parked at its completion hook");
                client.player.connection.sendCommand("codon stepout");
            });
            context.waitFor(client -> returned.get() && !CodonClientMod.state().isPaused()
                && !CodonClientMod.state().isStepping(), 200);
            if (failure.get() != null) throw new AssertionError("Vanilla queue fixture failed", failure.get());
            world.getServer().runOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                require(s.getScoreboard().getPlayerScoreInfo(player, s.getScoreboard().getObjective("watch_points")).value() == 21,
                    "leaving final inspection does not replay the command");
            });
        } finally {
            AtomicBoolean cleaned = new AtomicBoolean();
            DebuggerTaskQueue.execute(server, () -> {
                CodonMod.engine().clearBreakpoints();
                CodonMod.engine().resetSession();
                cleaned.set(true);
            });
            context.waitFor(client -> returned.get() && cleaned.get(), 200);
        }
    }

    private static boolean ready(String value) {
        var state = CodonClientMod.state();
        return state.isPaused() && state.watches().entries().stream().allMatch(e -> e.result() != null)
            && entry(SCORE).result().value().equals(value) && entry(STORAGE).result().value().equals(value);
    }

    private static ClientWatchState.Entry entry(WatchSpec spec) {
        return CodonClientMod.state().watches().entries().stream().filter(e -> e.spec().equals(spec)).findFirst().orElseThrow();
    }

    private static void checkUi(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280, 800);
        context.runOnClient(client -> {
            client.options.guiScale().set(2);
            client.resizeGui();
            var state = CodonClientMod.state();
            var input = new InputManager(state, ignored -> {});
            for (var key : client.options.keyMappings) {
                switch (key.getName()) {
                    case "key.codon.keep_freecam" -> input.keepFreecamKey = key;
                    case "key.codon.open_menu" -> input.menuKey = key;
                    case "key.codon.breakpoint" -> input.breakpointKey = key;
                    case "key.codon.resume" -> input.resumeKey = key;
                    case "key.codon.step_over" -> input.stepOverKey = key;
                    case "key.codon.step_into" -> input.stepIntoKey = key;
                }
            }
            client.setScreenAndShow(new WatchScreen(input, state, new DebuggerOverlay(state)));
        });
        context.waitTicks(3);
        context.takeScreenshot("codon-watch-changed");
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            var field = screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                .filter(EditBox::isVisible).findFirst().orElseThrow();
            field.setValue("watch_added");
            var add = screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
                .filter(b -> b.getMessage().getString().equals("Add")).findFirst().orElseThrow();
            int before = CodonClientMod.state().watches().entries().size();
            click(screen, add);
            require(CodonClientMod.state().watches().entries().size() == before + 1, "UI adds a watch");
            require(client.gui.screen() instanceof CodonScreen, "Add returns to the shared Watches HUD for management");
        });
        context.getInput().resizeWindow(640, 480);
        context.waitTicks(3);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            click(screen, screen.children().stream().filter(DebuggerButton.class::isInstance)
                .map(DebuggerButton.class::cast)
                .filter(button -> button.getMessage().getString().equals("View")).findFirst().orElseThrow());
        });
        context.waitTicks(1);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            click(screen, screen.children().stream().filter(DebuggerButton.class::isInstance)
                .map(DebuggerButton.class::cast)
                .filter(button -> button.getMessage().getString().contains("Watches")).findFirst().orElseThrow());
        });
        context.waitTicks(1);
        context.takeScreenshot("codon-watch-compact");
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            click(screen, screen.children().stream().filter(DebuggerButton.class::isInstance)
                .map(DebuggerButton.class::cast)
                .filter(button -> button.getMessage().getString().equals("Edit")).findFirst().orElseThrow());
            require(client.gui.screen() instanceof WatchScreen, "Watch row opens its editor directly");
            client.gui.screen().onClose();
        });
    }

    private static void click(Screen screen, DebuggerButton button) {
        var click = new MouseButtonEvent(button.getX() + 2, button.getY() + 2,
            new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
        require(screen.mouseClicked(click, false), "watch button accepts click");
        screen.mouseReleased(click);
    }

    private static void checkPauseCodec(PauseSnapshot snapshot) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            PauseSyncPayload.CODEC.encode(buffer, new PauseSyncPayload(snapshot));
            require(PauseSyncPayload.CODEC.decode(buffer).snapshot().equals(snapshot) && buffer.readableBytes() == 0,
                "pause codec preserves its nonzero stop ID");
        } finally { buffer.release(); }
    }

    private static void checkPathReadsAndCodec() {
        CompoundTag root = new CompoundTag();
        root.putInt("zero", 0);
        root.putString("long", "x".repeat(3000));
        var copy = root.copy();
        var zero = WatchReader.readPath(root, "zero", "fixture");
        require(zero.status() == WatchResult.Status.VALUE && zero.value().equals("0"), "real zero is a value");
        require(WatchReader.readPath(root, "absent", "fixture").status() == WatchResult.Status.VALUE_MISSING, "absent NBT path");
        require(WatchReader.readPath(root, "zero[", "fixture").status() == WatchResult.Status.INVALID_PATH, "invalid NBT path");
        require(WatchReader.readPath(root, "long", "fixture").status() == WatchResult.Status.TOO_LARGE, "oversize values are explicit, never silently truncated");
        require(root.equals(copy), "NBT path reads preserve storage contents");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var payload = new WatchSyncPayload(100, 200, zero.withTargetName("Pig"));
            WatchSyncPayload.CODEC.encode(buffer, payload);
            require(WatchSyncPayload.CODEC.decode(buffer).equals(payload) && buffer.readableBytes() == 0, "watch codec round trip");
        } finally { buffer.release(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
