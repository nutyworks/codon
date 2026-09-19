package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.ClientWatchState;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.client.ui.WatchScreen;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.PauseReason;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.WatchSpec;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real F9/Continue command-block execution with no saved watch for the changed score. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerAutomaticWatchGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            var server = world.getServer().computeOnServer(s -> s);
            var uuid = world.getServer().computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().getUUID());
            world.getServer().runOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                s.getPlayerList().op(player.nameAndId(),
                    Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER), Optional.empty());
                CodonMod.engine().clearBreakpoints();
            });
            world.getServer().runCommand("scoreboard objectives add auto_points dummy");
            world.getServer().runCommand("scoreboard players set @a auto_points 0");
            world.getServer().runCommand("gamemode creative @a");
            world.getServer().runCommand("tp @a 5 82 5 180 25");
            world.getServer().runCommand("fill 3 79 -1 6 79 1 minecraft:stone");
            world.getServer().runCommand("setblock 4 80 0 minecraft:command_block[facing=east]{Command:\"execute as @a run scoreboard players add @s auto_points 3\"}");
            world.getServer().runCommand("setblock 5 80 0 minecraft:chain_command_block[facing=east]{auto:1b,Command:\"execute as @a run scoreboard players add @s auto_points 4\"}");
            world.getServer().runOnServer(s -> CodonMod.engine().toggleBlockBreakpoint(new BlockLocation(4, 80, 0, "minecraft:overworld")));
            world.getServer().runOnServer(s -> CodonMod.engine().toggleBlockBreakpoint(new BlockLocation(5, 80, 0, "minecraft:overworld")));
            WatchSpec pinned = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health", uuid);
            context.runOnClient(client -> {
                var watches = CodonClientMod.state().watches();
                watches.reset();
                watches.add(pinned);
                client.setScreenAndShow(null);
            });
            context.getInput().resizeWindow(1280, 800);
            boolean firstChange = false, completed = false, continued = false;
            try {
                world.getServer().runCommand("setblock 3 80 0 minecraft:redstone_block");
                context.waitFor(client -> CodonClientMod.state().isPaused(), 200);
                for (int stop = 0; stop < 16; stop++) {
                    context.waitFor(client -> CodonClientMod.state().isPaused()
                        && CodonClientMod.state().watches().entries().getFirst().result() != null, 200);
                    var snapshot = context.computeOnClient(client -> CodonClientMod.state().snapshot());
                    boolean postFirst = !firstChange && snapshot.location().equals(new SourceLocation.Block(
                        new BlockLocation(5, 80, 0, "minecraft:overworld")));
                    if (postFirst || snapshot.reason() == PauseReason.EXECUTION_COMPLETE) {
                        context.waitFor(client -> automaticScore() != null, 200);
                        context.runOnClient(client -> {
                            var rows = CodonClientMod.state().watches().displayedEntries();
                            require(rows.getFirst().spec().equals(pinned), "unchanged saved pin precedes automatic changes");
                            var score = automaticScore();
                            require(score.automatic() && score.displayedExecutor().equals(uuid), "unregistered score retains its executor");
                            require(score.previousValue().equals(postFirst ? "0" : "3"), "delta starts at the previous stop");
                            require(score.result().value().equals(postFirst ? "3" : "7"), "delta includes this command's change");
                            require(CodonClientMod.state().watches().definitions().equals(List.of(pinned)), "automatic values never enter saved definitions");
                        });
                        if (postFirst) {
                            require(context.computeOnClient(client -> CodonClientMod.state().selectedSource().entity() == null),
                                "outgoing executor change remains visible at the next block's executor-free stop");
                            screenshot(context, "codon-automatic-watch-pins-first");
                            firstChange = true;
                        } else {
                            completed = true;
                            screenshot(context, "codon-automatic-watch-final");
                            break;
                        }
                    } else if (firstChange) {
                        context.runOnClient(client -> require(automaticScore() == null,
                            "the preceding pause's changed score is removed at an unchanged stop"));
                    }
                    long previousPause = snapshot.pauseId();
                    if (!continued && !firstChange && snapshot.pauseSources().stream().anyMatch(source -> source.entity() != null)) {
                        continued = true;
                        context.runOnClient(client -> client.player.connection.sendCommand("codon resume"));
                    } else {
                        context.getInput().pressKey(InputConstants.KEY_F9);
                    }
                    context.waitFor(client -> CodonClientMod.state().isPaused()
                        && CodonClientMod.state().snapshot().pauseId() != previousPause, 200);
                }
                require(firstChange && completed && continued, "both Continue and F9 produce automatic changes through the real chain");
                context.runOnClient(client -> client.player.connection.sendCommand("codon resume"));
                context.waitFor(client -> !CodonClientMod.state().isPaused(), 200);
                context.runOnClient(client -> require(CodonClientMod.state().watches().displayedEntries().stream()
                    .noneMatch(ClientWatchState.Entry::automatic), "resume clears automatic rows"));
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

    private static ClientWatchState.Entry automaticScore() {
        return CodonClientMod.state().watches().displayedEntries().stream()
            .filter(entry -> entry.spec().kind() == WatchSpec.Kind.SCORE && entry.spec().target().equals("auto_points"))
            .findFirst().orElse(null);
    }

    private static void screenshot(ClientGameTestContext context, String name) {
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
        context.takeScreenshot(name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
