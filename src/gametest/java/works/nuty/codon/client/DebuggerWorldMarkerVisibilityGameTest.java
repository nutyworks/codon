package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.phys.Vec3;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.client.input.UiHideGesture;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.PauseSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native first-frame and sustained visibility checks at a real entity-context breakpoint. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerWorldMarkerVisibilityGameTest implements FabricClientGameTest {
    private static final BlockLocation BLOCK = new BlockLocation(4, 80, 0, "minecraft:overworld");
    private static boolean sentinelEnabled;
    private static boolean sentinelRegistered;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1280, 800);
            world.getConnection().waitForChunksRender();
            var server = world.getServer().computeOnServer(value -> value);
            configure(world);
            context.waitFor(client -> client.player.getY() > 80 && client.player.getZ() > 8, 200);
            world.getConnection().waitForChunksRender();
            context.waitTicks(4);
            context.runOnClient(client -> {
                CodonClientMod.input().resetUiVisibility();
                if (!sentinelRegistered) {
                    LevelRenderEvents.BEFORE_GIZMOS.register(render -> {
                        if (sentinelEnabled) Gizmos.line(new Vec3(-2, 80.2, 0), new Vec3(-2, 82, 0),
                            0xFFFF00FF, 3).setAlwaysOnTop();
                    });
                    sentinelRegistered = true;
                }
                sentinelEnabled = true;
                client.gui.hud.getChat().clearMessages(false);
                client.gui.hud.getChat().addClientSystemMessage(Component.literal("marker visibility: vanilla chat"));
            });
            try {
                world.getServer().runCommand("setblock 3 80 0 minecraft:redstone_block");
                context.waitFor(client -> CodonClientMod.state().isPaused(), 200);
                // Enter the actual execute-as/at stages until the entity ring has world coordinates.
                for (int step = 0; step < 8 && !context.computeOnClient(client -> hasEntityContext()); step++) {
                    long id = context.computeOnClient(client -> CodonClientMod.state().snapshot().pauseId());
                    context.getInput().pressKey(options -> CodonClientMod.input().stepIntoKey);
                    context.waitFor(client -> CodonClientMod.state().isPaused()
                        && CodonClientMod.state().snapshot().pauseId() != id, 200);
                }
                context.runOnClient(client -> require(hasEntityContext(), "real pause includes the stand's ring"));
                PauseSnapshot clientPause = context.computeOnClient(client -> CodonClientMod.state().snapshot());
                PauseSnapshot serverPause = CodonMod.engine().currentSnapshot();
                checkGestures(context, "world", true);
                context.runOnClient(client -> client.setScreenAndShow(
                    new CodonScreen(CodonClientMod.input(), new DebuggerOverlay(CodonClientMod.state()))));
                checkGestures(context, "cursor", true);
                context.runOnClient(client -> {
                    require(CodonClientMod.state().snapshot() == clientPause, "H preserves the acknowledged pause");
                    require(CodonMod.engine().isPaused() && CodonMod.engine().currentSnapshot() == serverPause,
                        "H does not step or resume the parked server");
                    client.setScreenAndShow(null);
                    client.player.connection.sendCommand("codon resume");
                });
                context.waitFor(client -> !CodonClientMod.state().isPaused() && !CodonClientMod.state().isContinuing(), 200);
                world.getServer().runOnServer(value -> {
                    var objective = value.getScoreboard().getObjective("marker_visibility");
                    require(value.getScoreboard().getPlayerScoreInfo(
                        net.minecraft.world.scores.ScoreHolder.forNameOnly("result"), objective).value() == 1,
                        "the inspected command executes exactly once after Resume");
                });
                // Breakpoint outlines remain visible while running and obey the same frame boundary.
                checkGestures(context, "running", false);
                context.runOnClient(client -> tap(client));
                context.runOnClient(client -> require(CodonClientMod.input().isUiHidden(), "disconnect starts hidden"));
            } finally {
                AtomicBoolean cleaned = new AtomicBoolean();
                DebuggerTaskQueue.execute(server, () -> {
                    CodonMod.engine().clearBreakpoints();
                    CodonMod.engine().resetSession();
                    cleaned.set(true);
                });
                context.waitFor(client -> cleaned.get(), 200);
                context.runOnClient(client -> {
                    WorldMarkerRenderProbe.stop();
                    sentinelEnabled = false;
                    CodonClientMod.state().reset();
                    if (client.gui.screen() != null) client.setScreenAndShow(null);
                });
            }
        }
        context.runOnClient(client -> require(!CodonClientMod.input().isUiHidden(), "disconnect restores visibility"));
        try (TestSingleplayerContext rejoined = context.worldBuilder().create()) {
            rejoined.getConnection().waitForChunksRender();
            context.runOnClient(client -> require(!CodonClientMod.input().isUiHidden(), "rejoin remains visible"));
        }
    }

    private static boolean hasEntityContext() {
        return CodonClientMod.state().worldSources().stream().anyMatch(source -> source.entity() != null
            && Math.abs(source.anchor().x() - 2) < 0.1 && Math.abs(source.anchor().z()) < 0.1);
    }

    private static void checkGestures(ClientGameTestContext context, String phase, boolean paused) {
        observe(context, false, phase + "-before");
        context.runOnClient(client -> {
            WorldMarkerRenderProbe.start("codon-marker-" + phase + "-first-held");
            key(client, InputConstants.PRESS);
        });
        awaitFrames(context);
        assertFrames(context, true, phase + " first held frame");
        long visibleStart = System.nanoTime();
        context.waitFor(client -> System.nanoTime() - visibleStart >= UiHideGesture.HOLD_NANOS, 200);
        assertFrames(context, true, phase + " sustained held frames");
        context.runOnClient(client -> {
            WorldMarkerRenderProbe.start("codon-marker-" + phase + "-first-restored");
            key(client, InputConstants.RELEASE);
        });
        awaitFrames(context);
        assertFrames(context, false, phase + " first restored frame");
        context.runOnClient(client -> { WorldMarkerRenderProbe.start(); tap(client); });
        awaitFrames(context);
        assertFrames(context, true, phase + " tap hides");
        // A long hold begun while latched hidden must restore that hidden state.
        context.runOnClient(client -> key(client, InputConstants.PRESS));
        long hiddenStart = System.nanoTime();
        context.waitFor(client -> System.nanoTime() - hiddenStart >= UiHideGesture.HOLD_NANOS, 200);
        context.runOnClient(client -> { WorldMarkerRenderProbe.start(); key(client, InputConstants.RELEASE); });
        awaitFrames(context);
        assertFrames(context, true, phase + " hidden long release");
        context.runOnClient(client -> { WorldMarkerRenderProbe.start(); tap(client); });
        awaitFrames(context);
        assertFrames(context, false, phase + " tap restores");
        context.runOnClient(client -> require(CodonClientMod.state().isPaused() == paused,
            phase + " gestures preserve execution state"));
    }

    private static void observe(ClientGameTestContext context, boolean hidden, String phase) {
        context.runOnClient(client -> WorldMarkerRenderProbe.start("codon-marker-" + phase));
        awaitFrames(context);
        assertFrames(context, hidden, phase);
    }

    private static void awaitFrames(ClientGameTestContext context) {
        context.waitFor(client -> WorldMarkerRenderProbe.frames().size() >= 4, 200);
    }

    private static void assertFrames(ClientGameTestContext context, boolean hidden, String phase) {
        List<WorldMarkerRenderProbe.Frame> frames = context.computeOnClient(client -> WorldMarkerRenderProbe.frames());
        CodonMod.LOGGER.info("World marker frames {}: count={}, first={}", phase, frames.size(),
            frames.stream().limit(4).toList());
        for (var frame : frames) {
            require(frame.hidden() == hidden, phase + " visibility: " + frame);
            require(hidden ? frame.markers() == 0 : frame.markers() > 0, phase + " geometry: " + frame);
            require(frame.sentinel() > 0, phase + " preserves unrelated gizmos: " + frame);
        }
    }

    private static void configure(TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            server.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
            CodonMod.engine().clearBreakpoints();
            CodonMod.engine().toggleBlockBreakpoint(BLOCK);
        });
        world.getServer().runCommand("scoreboard objectives add marker_visibility dummy");
        world.getServer().runCommand("scoreboard players set result marker_visibility 0");
        world.getServer().runCommand("gamemode creative @a");
        world.getServer().runCommand("fill -4 79 -2 6 79 9 minecraft:stone");
        world.getServer().runCommand("tp @a 0 81 8 180 18");
        world.getServer().runCommand("summon minecraft:armor_stand 2.0 80 0.0 {Tags:[\"marker_target\"],NoGravity:1b}");
        world.getServer().runCommand("setblock 4 80 0 minecraft:command_block{Command:\"execute as @e[tag=marker_target,limit=1] at @s run scoreboard players add result marker_visibility 1\",auto:0b}");
    }

    private static void tap(Minecraft client) { key(client, InputConstants.PRESS); key(client, InputConstants.RELEASE); }
    private static void key(Minecraft client, int action) {
        client.keyboardHandler.keyPress(client.getWindow().handle(), action, new KeyEvent(InputConstants.KEY_H, 0, 0));
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
