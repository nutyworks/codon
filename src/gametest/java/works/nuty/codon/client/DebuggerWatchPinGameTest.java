package works.nuty.codon.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.adapter.SourceMapper;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.ClientWatchState;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.service.CommandStageEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises Watches-HUD pin controls against a real parked server and two execution entities. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerWatchPinGameTest implements FabricClientGameTest {
    private static final WatchSpec SCORE = new WatchSpec(WatchSpec.Kind.SCORE, "pin_points", "");
    private static final WatchSpec ENTITY = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "UUID");
    private static final WatchSpec STORAGE = new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "codon:pin_test", "value");
    private static final BlockLocation BREAKPOINT = new BlockLocation(20, 80, 0, "minecraft:overworld");

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            world.getServer().runCommand("scoreboard objectives add pin_points dummy");
            world.getServer().runCommand("summon minecraft:armor_stand 22 80 0 {NoGravity:1b}");
            MinecraftServer server = world.getServer().computeOnServer(s -> s);
            AtomicReference<ArmorStand> second = new AtomicReference<>();
            AtomicReference<UUID> firstId = new AtomicReference<>();
            AtomicReference<UUID> secondId = new AtomicReference<>();
            world.getServer().runOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                ArmorStand stand = s.overworld().getEntitiesOfClass(ArmorStand.class,
                    new AABB(20, 78, -2, 24, 84, 2)).getFirst();
                stand.setCustomName(Component.literal("pin-b"));
                s.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
                var objective = s.getScoreboard().getObjective("pin_points");
                s.getScoreboard().getOrCreatePlayerScore(player, objective).set(10);
                s.getScoreboard().getOrCreatePlayerScore(stand, objective).set(20);
                second.set(stand);
                firstId.set(player.getUUID());
                secondId.set(stand.getUUID());
                CodonMod.engine().clearBreakpoints();
            });

            context.runOnClient(client -> {
                var watches = CodonClientMod.state().watches();
                watches.reset();
                require(watches.add(SCORE), "score watch fixture");
                require(watches.add(ENTITY), "entity NBT watch fixture");
                require(watches.add(STORAGE), "storage watch fixture");
            });
            AtomicBoolean finished = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            try {
                server.execute(() -> {
                    var engine = CodonMod.engine();
                    engine.onExecutionStarted();
                    try {
                        var player = server.getPlayerList().getPlayers().getFirst();
                        var sources = SourceMapper.toPauseSources(List.of(player.createCommandSourceStack(),
                            server.createCommandSourceStack().withEntity(second.get()).withPosition(second.get().position())));
                        engine.toggleBlockBreakpoint(BREAKPOINT);
                        engine.onCommandStage(stage(1, sources));
                        var objective = server.getScoreboard().getObjective("pin_points");
                        server.getScoreboard().getOrCreatePlayerScore(player, objective).set(11);
                        server.getScoreboard().getOrCreatePlayerScore(second.get(), objective).set(22);
                        var noExecutor = SourceMapper.toPauseSources(List.of(server.createCommandSourceStack()));
                        engine.onCommandStage(stage(2, noExecutor));
                        second.get().discard();
                        engine.onCommandStage(stage(3, noExecutor));
                    } catch (Throwable problem) {
                        failure.set(problem);
                    } finally {
                        engine.onExecutionFinished(failure.get() == null);
                        engine.clearBreakpoints();
                        finished.set(true);
                    }
                });

                context.waitFor(client -> ready(3), 200);
                openWatchHud(context);
                long storageId = context.computeOnClient(client -> CodonClientMod.state().watches().findId(STORAGE));
                WatchGameTestUi.open(context, storageId);
                context.runOnClient(client -> {
                    require(WatchGameTestUi.action(client.gui.screen(), "codon.watch.pin") == null
                        && WatchGameTestUi.action(client.gui.screen(), "codon.watch.unpin") == null,
                        "Storage row menu has no executor pin or unpin action");
                    works.nuty.codon.client.ui.ScreenLayers.get(client.gui.screen()).onClose();
                });
                WatchGameTestUi.perform(context, context.computeOnClient(client -> score(null).id()), "codon.watch.pin");
                context.runOnClient(client -> require(score(firstId.get()).spec().isPinned(),
                    "Watches row menu pins score to selected player A"));
                WatchGameTestUi.perform(context, context.computeOnClient(client -> entity(null).id()), "codon.watch.pin");
                context.runOnClient(client -> {
                    require(entity(firstId.get()).spec().isPinned(), "Watches row menu also pins entity NBT");
                    require(CodonClientMod.state().watches().add(SCORE), "state fixture adds a second floating score for HUD pinning");
                    CodonClientMod.state().selectSource(1);
                });
                context.waitTicks(3);
                WatchGameTestUi.perform(context, context.computeOnClient(client -> score(null).id()), "codon.watch.pin");
                context.runOnClient(client -> require(score(secondId.get()).spec().isPinned(),
                    "same score expression pins independently to B through Watches row menu"));

                context.waitFor(client -> pinnedScoresReady(firstId.get(), secondId.get())
                    && entity(firstId.get()).result() != null, 200);
                context.runOnClient(client -> {
                    require(CodonClientMod.state().selectedSource().entity().uuid().equals(secondId.get()), "source B is selected");
                    require(score(firstId.get()).result().targetKey().equals(entityKey(firstId.get())),
                        "selecting B never replaces pinned A's current result");
                    require(score(secondId.get()).result().targetKey().equals(entityKey(secondId.get())), "pinned B reads B");
                    require(entity(firstId.get()).result().status() == WatchResult.Status.VALUE
                        && entity(firstId.get()).result().targetKey().equals(entityKey(firstId.get())),
                        "entity NBT binding reads A after source selection changes to B");
                });
                screenshotPinnedRows(context, "codon-watch-two-pins");
                hoverFirstPin(context);

                long firstPause = context.computeOnClient(client -> CodonClientMod.state().snapshot().pauseId());
                context.runOnClient(client -> client.player.connection.sendCommand("codon stepover"));
                context.waitFor(client -> CodonClientMod.state().isPaused()
                    && CodonClientMod.state().snapshot().pauseId() != firstPause
                    && pinnedScoresReady(firstId.get(), secondId.get()), 200);
                context.runOnClient(client -> {
                    ClientWatchState.Entry a = score(firstId.get());
                    ClientWatchState.Entry b = score(secondId.get());
                    require(a.result().value().equals("11") && a.previousValue().equals("10"), "A retains its own score history");
                    require(b.result().value().equals("22") && b.previousValue().equals("20"), "B retains its own score history");
                    require(CodonClientMod.state().selectedSource().entity() == null, "both pinned values refresh even without a current executor");
                });
                WatchGameTestUi.perform(context, context.computeOnClient(client -> score(firstId.get()).id()), "codon.watch.unpin");
                context.runOnClient(client -> require(!score(null).spec().isPinned(), "Watches row menu unpins A"));
                WatchGameTestUi.perform(context, context.computeOnClient(client -> score(secondId.get()).id()), "codon.watch.unpin");
                context.runOnClient(client -> {
                    require(CodonClientMod.state().watches().definitions().stream().filter(SCORE::equals).count() == 1,
                        "unpin into an existing floating definition merges without a duplicate");
                    require(CodonClientMod.state().watches().definitions().stream().noneMatch(SCORE.withExecutor(secondId.get())::equals),
                        "the redundant pinned row is removed");
                    require(CodonClientMod.state().watches().revealId() == score(null).id(), "the existing following row is revealed");
                    require(CodonClientMod.state().watches().add(SCORE.withExecutor(secondId.get())),
                        "restore B binding for subsequent removed-entity coverage");
                });
                long secondPause = context.computeOnClient(client -> CodonClientMod.state().snapshot().pauseId());
                context.runOnClient(client -> client.player.connection.sendCommand("codon stepover"));
                context.waitFor(client -> CodonClientMod.state().isPaused()
                    && CodonClientMod.state().snapshot().pauseId() != secondPause
                    && score(secondId.get()).result() != null, 200);
                context.runOnClient(client -> require(score(secondId.get()).result().status() == WatchResult.Status.TARGET_MISSING
                    && score(secondId.get()).result().targetKey().equals(entityKey(secondId.get())),
                    "a removed pinned entity remains missing and never falls back to another executor"));
                context.runOnClient(client -> client.player.connection.sendCommand("codon resume"));
                context.waitFor(client -> finished.get() && !CodonClientMod.state().isPaused(), 200);
                if (failure.get() != null) throw new AssertionError("pin fixture failed", failure.get());
            } finally {
                AtomicBoolean cleaned = new AtomicBoolean();
                DebuggerTaskQueue.execute(server, () -> {
                    CodonMod.engine().clearBreakpoints();
                    CodonMod.engine().resetSession();
                    cleaned.set(true);
                });
                context.waitFor(client -> cleaned.get(), 200);
                context.runOnClient(client -> client.setScreenAndShow(null));
            }
        }
    }

    private static CommandStageEvent stage(long sequence, List<PauseSource> sources) {
        return new CommandStageEvent(810000 + sequence, 0,
            new SourceLocation.Block(BREAKPOINT), CommandSnippet.plain("scoreboard players add @s pin_points 1"), () -> sources);
    }

    private static boolean ready(int expectedEntries) {
        return CodonClientMod.state().isPaused() && CodonClientMod.state().watches().entries().size() == expectedEntries
            && CodonClientMod.state().watches().entries().stream().allMatch(entry -> entry.result() != null);
    }

    private static boolean pinnedScoresReady(UUID first, UUID second) {
        return CodonClientMod.state().watches().entries().stream()
            .filter(entry -> entry.spec().equals(SCORE.withExecutor(first)) || entry.spec().equals(SCORE.withExecutor(second)))
            .count() == 2
            && score(first).result() != null && score(second).result() != null;
    }

    private static ClientWatchState.Entry score(UUID executor) {
        return CodonClientMod.state().watches().entries().stream()
            .filter(entry -> entry.spec().equals(SCORE.withExecutor(executor))).findFirst().orElseThrow();
    }

    private static ClientWatchState.Entry entity(UUID executor) {
        return CodonClientMod.state().watches().entries().stream()
            .filter(entry -> entry.spec().equals(ENTITY.withExecutor(executor))).findFirst().orElseThrow();
    }

    private static String entityKey(UUID uuid) { return "entity:" + uuid; }

    private static CodonScreen screen(net.minecraft.client.Minecraft client) {
        if (!(client.gui.screen() instanceof CodonScreen screen)) throw new AssertionError("Watches HUD is open");
        return screen;
    }

    private static void openWatchHud(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280, 800);
        context.runOnClient(client -> {
            client.options.guiScale().set(2);
            client.resizeGui();
            var input = new InputManager(CodonClientMod.state(), ignored -> {});
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
            client.setScreenAndShow(new CodonScreen(input, new DebuggerOverlay(CodonClientMod.state())));
        });
        context.waitTicks(3);
    }

    private static void screenshotPinnedRows(ClientGameTestContext context, String name) {
        context.waitTicks(3);
        context.takeScreenshot(name);
    }

    private static void hoverFirstPin(ClientGameTestContext context) {
        double[] cursor = context.computeOnClient(client -> {
            DebuggerButton pin = WatchGameTestUi.row(screen(client), CodonClientMod.state().watches().entries().stream()
                .filter(entry -> entry.spec().isPinned()).findFirst().orElseThrow().id());
            return new double[] {
                (pin.getX() + 3.0) * client.getWindow().getScreenWidth() / client.gui.screen().width,
                (pin.getY() + 4.0) * client.getWindow().getScreenHeight() / client.gui.screen().height
            };
        });
        context.getInput().setCursorPos(cursor[0], cursor[1]);
        context.waitTicks(3);
        context.takeScreenshot("codon-watch-two-pins-tooltip");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
