package works.nuty.codon.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import works.nuty.codon.client.input.UiHideGesture;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.client.camera.DebuggerFreecam;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.PauseSnapshot;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A real command-block chain verifies that Resume is a temporary continuation while commands
 * remain to inspect, preserving freecam until execution actually finishes.
 */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerFreecamResumeGameTest implements FabricClientGameTest {
    private static final String OBJECTIVE = "freecam_resume";
    private static final BlockPos FIRST_POS = new BlockPos(4, 80, 0);
    private static final BlockPos SECOND_POS = new BlockPos(5, 80, 0);
    private static final BlockPos THIRD_POS = new BlockPos(6, 80, 0);
    private static final BlockLocation FIRST = block(FIRST_POS);
    private static final BlockLocation SECOND = block(SECOND_POS);
    private static final BlockLocation THIRD = block(THIRD_POS);

    @Override
    public void runTest(ClientGameTestContext context) {
        runResume(context, false);
        runResume(context, true);
    }

    private void runResume(ClientGameTestContext context, boolean keepFreecam) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            MinecraftServer server = world.getServer().computeOnServer(value -> value);
            configure(world);
            Fixture fixture = context.computeOnClient(DebuggerFreecamResumeGameTest::prepare);
            boolean originalKeep = fixture.state().preferences().keepFreecam();
            context.runOnClient(client -> fixture.state().preferences().setKeepFreecam(keepFreecam));
            try {
                // A normal redstone trigger enters the production command-block execution path.
                world.getServer().runCommand("setblock 3 80 0 minecraft:redstone_block");
                waitForPause(context, FIRST);
                CameraGuard guard = context.computeOnClient(client -> cameraGuard(client, fixture));
                if (!keepFreecam) checkHideDuringNativePause(context, fixture);

                resumeToContinuation(context, SECOND);
                context.waitTicks(3);
                context.runOnClient(client -> assertGuard(client, fixture, guard,
                    "the continuation after the first resume"));
                waitForPause(context, SECOND);
                context.runOnClient(client -> assertGuard(client, fixture, guard, "the second breakpoint"));

                resumeToContinuation(context, THIRD);
                context.waitTicks(3);
                context.runOnClient(client -> assertGuard(client, fixture, guard,
                    "the continuation after the second resume"));
                waitForPause(context, THIRD);
                context.runOnClient(client -> assertGuard(client, fixture, guard, "the third breakpoint"));

                // No later breakpoint remains. Keep Freecam determines whether the camera is restored.
                context.runOnClient(client -> client.player.connection.sendCommand("codon resume"));
                context.waitFor(client -> {
                    ClientDebuggerState state = require(CodonClientMod.state(), "client debugger state remains initialized");
                    DebuggerFreecam freecam = require(CodonClientMod.freecam(), "client freecam remains initialized");
                    return !state.isPaused() && !state.isContinuing() && freecam.isActive() == keepFreecam;
                }, 200);
                if (keepFreecam) {
                    context.runOnClient(client -> {
                        assertGuard(client, fixture, guard, "terminal resume with retained freecam");
                        require(!fixture.freecam().freezes(fixture.player()), "resumed body is not frozen");
                        fixture.player().getAbilities().flying = false;
                        fixture.player().setDeltaMovement(Vec3.ZERO);
                    });
                    int bodyTicks = context.computeOnClient(client -> fixture.player().tickCount);
                    double bodyY = context.computeOnClient(client -> fixture.player().getY());
                    context.waitTicks(10);
                    context.runOnClient(client -> {
                        require(fixture.player().tickCount > bodyTicks, "body ticks resume while camera stays detached");
                        require(fixture.player().getY() < bodyY - 0.5, "resumed body falls under gravity");
                        assertGuard(client, fixture, guard, "resumed body physics");
                        client.options.keyUp.setDown(true);
                        client.options.keyJump.setDown(true);
                        client.options.keyShift.setDown(true);
                        fixture.player().input.tick();
                        require(fixture.player().input.keyPresses.equals(net.minecraft.world.entity.player.Input.EMPTY),
                            "camera navigation does not become body input");
                        client.options.keyUp.setDown(false);
                        client.options.keyJump.setDown(false);
                        client.options.keyShift.setDown(false);
                    });
                    world.getServer().runOnServer(value -> require(
                        value.getPlayerList().getPlayers().getFirst().getY() < bodyY - 0.5,
                        "server receives the falling body's position while freecam is retained"));
                    context.runOnClient(client -> {
                        // Frame the actual resumed body and observe vanilla render extraction.
                        Entity camera = client.getCameraEntity();
                        Vec3 body = fixture.player().position();
                        camera.snapTo(body.x, body.y + 0.2, body.z + 4, 180, 12);
                        camera.setOldPosAndRot();
                        FreecamRenderProbe.reset();
                    });
                    context.waitTicks(2);
                    context.takeScreenshot("codon-freecam-retained-body");
                    require(!Float.isNaN(FreecamRenderProbe.playerPartialTick()),
                        "resumed player body remains rendered from retained freecam; "
                            + FreecamRenderProbe.visibility());
                } else {
                    context.runOnClient(client -> assertTerminalResume(client, fixture));
                }
                world.getServer().runOnServer(value -> {
                    var player = value.getPlayerList().getPlayers().getFirst();
                    var score = value.getScoreboard().getPlayerScoreInfo(player, value.getScoreboard().getObjective(OBJECTIVE));
                    require(score != null && score.value() == 111,
                        "each command-block stage executes exactly once after its resume");
                });
            } finally {
                AtomicBoolean cleaned = new AtomicBoolean();
                DebuggerTaskQueue.execute(server, () -> {
                    CodonMod.engine().clearBreakpoints();
                    CodonMod.engine().resetSession();
                    cleaned.set(true);
                });
                context.waitFor(client -> cleaned.get(), 200);
                context.runOnClient(client -> {
                    fixture.state().preferences().setKeepFreecam(originalKeep);
                    fixture.state().reset();
                    fixture.freecam().synchronize(client);
                    client.options.setCameraType(fixture.originalPerspective());
                    client.setScreenAndShow(null);
                });
            }
        }
    }

    private static void checkHideDuringNativePause(ClientGameTestContext context, Fixture fixture) {
        var input = CodonClientMod.input();
        PauseSnapshotGuard pause = context.computeOnClient(client -> new PauseSnapshotGuard(
            fixture.state().snapshot(), CodonMod.engine().currentSnapshot()));
        context.getInput().holdKey(options -> input.hideUiKey);
        long start = System.nanoTime();
        context.waitFor(client -> System.nanoTime() - start >= UiHideGesture.HOLD_NANOS, 200);
        context.runOnClient(client -> require(input.isUiHidden(), "native breakpoint UI hides during a long press"));
        context.getInput().releaseKey(options -> input.hideUiKey);
        context.runOnClient(client -> {
            require(!input.isUiHidden(), "long release restores native breakpoint UI");
            KeyEvent h = new KeyEvent(InputConstants.KEY_H, 0, 0);
            client.keyboardHandler.keyPress(client.getWindow().handle(), InputConstants.PRESS, h);
            client.keyboardHandler.keyPress(client.getWindow().handle(), InputConstants.RELEASE, h);
            require(input.isUiHidden(), "native breakpoint UI toggles on a tap");
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(fixture.state().isPaused() && fixture.state().snapshot() == pause.client(),
                "hiding preserves the acknowledged client pause");
            require(CodonMod.engine().isPaused() && CodonMod.engine().currentSnapshot() == pause.server(),
                "hiding cannot resume or step the real server breakpoint");
            input.resetUiVisibility();
        });
    }

    private record PauseSnapshotGuard(PauseSnapshot client, PauseSnapshot server) { }

    private static void configure(TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            server.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
            CodonMod.engine().clearBreakpoints();
            CodonMod.engine().toggleBlockBreakpoint(FIRST);
            CodonMod.engine().toggleBlockBreakpoint(SECOND);
            CodonMod.engine().toggleBlockBreakpoint(THIRD);
        });
        world.getServer().runCommand("scoreboard objectives add " + OBJECTIVE + " dummy");
        world.getServer().runCommand("scoreboard players set @a " + OBJECTIVE + " 0");
        world.getServer().runCommand("gamemode creative @a");
        world.getServer().runCommand("tp @a 5 82 5 180 25");
        world.getServer().runCommand("fill 3 79 -1 7 79 1 minecraft:stone");
        world.getServer().runCommand("setblock 4 80 0 minecraft:command_block[facing=east]{Command:\"execute as @a run scoreboard players add @s "
            + OBJECTIVE + " 1\",auto:0b}");
        world.getServer().runCommand("setblock 5 80 0 minecraft:chain_command_block[facing=east]{Command:\"execute as @a run scoreboard players add @s "
            + OBJECTIVE + " 10\",auto:1b}");
        world.getServer().runCommand("setblock 6 80 0 minecraft:chain_command_block[facing=east]{Command:\"execute as @a run scoreboard players add @s "
            + OBJECTIVE + " 100\",auto:1b}");
    }

    private static Fixture prepare(Minecraft client) {
        ClientDebuggerState state = require(CodonClientMod.state(), "client debugger state is initialized");
        DebuggerFreecam freecam = require(CodonClientMod.freecam(), "client freecam is initialized");
        LocalPlayer player = require(client.player, "local player is available");
        CameraType originalPerspective = client.options.getCameraType();
        state.reset();
        freecam.synchronize(client);
        client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        return new Fixture(state, freecam, player, originalPerspective);
    }

    private static void resumeToContinuation(ClientGameTestContext context, BlockLocation nextBreakpoint) {
        context.runOnClient(client -> client.player.connection.sendCommand("codon resume"));
        context.waitFor(client -> {
            ClientDebuggerState state = require(CodonClientMod.state(), "client debugger state remains initialized");
            DebuggerFreecam freecam = require(CodonClientMod.freecam(), "client freecam remains initialized");
            // Continue and the next pause may be delivered in the same client frame. Never
            // require sampling that transient state, and never accept the previous breakpoint.
            return freecam.isActive() && ((!state.isPaused() && state.isContinuing() && state.snapshot() == null)
                || (state.isPaused() && state.snapshot() != null
                    && state.snapshot().location().equals(new SourceLocation.Block(nextBreakpoint))));
        }, 200);
    }

    private static void waitForPause(ClientGameTestContext context, BlockLocation expected) {
        context.waitFor(client -> {
            ClientDebuggerState state = require(CodonClientMod.state(), "client debugger state remains initialized");
            return state.isPaused() && !state.isContinuing() && state.snapshot() != null
                && state.snapshot().location().equals(new SourceLocation.Block(expected));
        }, 200);
    }

    private static CameraGuard cameraGuard(Minecraft client, Fixture fixture) {
        require(fixture.freecam().isActive(), "the first breakpoint starts freecam");
        require(client.options.getCameraType() == CameraType.FIRST_PERSON,
            "freecam starts in first-person regardless of the saved perspective");
        Entity camera = require(client.getCameraEntity(), "detached camera entity is available");
        require(camera != fixture.player(), "freecam replaces the player camera");
        camera.snapTo(fixture.player().getX() + 4, fixture.player().getY() + 2, fixture.player().getZ() - 3, 37, -22);
        camera.setOldPosAndRot();
        client.gameRenderer.mainCamera().update(DeltaTracker.ONE);
        return new CameraGuard(camera, camera.position(), camera.getYRot(), camera.getXRot(),
            client.gameRenderer.mainCamera().position(), client.gameRenderer.mainCamera().yRot(),
            client.gameRenderer.mainCamera().xRot());
    }

    private static void assertGuard(Minecraft client, Fixture fixture, CameraGuard expected, String phase) {
        require(fixture.freecam().isActive(), "freecam remains active through " + phase);
        Entity camera = require(client.getCameraEntity(), "freecam camera remains available through " + phase);
        require(camera == expected.entity(), "freecam keeps the same entity through " + phase);
        require(camera.position().equals(expected.position()), "freecam keeps its position through " + phase);
        require(camera.getYRot() == expected.yaw() && camera.getXRot() == expected.pitch(),
            "freecam keeps its yaw and pitch through " + phase);
        require(client.options.getCameraType() == CameraType.FIRST_PERSON,
            "freecam stays first-person through " + phase);
        client.gameRenderer.mainCamera().update(DeltaTracker.ONE);
        require(client.gameRenderer.mainCamera().position().equals(expected.renderedPosition()),
            "the rendered camera keeps its position through " + phase);
        require(client.gameRenderer.mainCamera().yRot() == expected.renderedYaw()
                && client.gameRenderer.mainCamera().xRot() == expected.renderedPitch(),
            "the rendered camera keeps its yaw and pitch through " + phase);
    }

    private static void assertTerminalResume(Minecraft client, Fixture fixture) {
        require(!fixture.freecam().isActive(), "freecam stops only after execution is exhausted");
        require(client.getCameraEntity() == fixture.player(), "terminal resume restores the player camera");
        require(client.options.getCameraType() == CameraType.THIRD_PERSON_FRONT,
            "terminal resume restores the pre-freecam perspective");
    }

    private static BlockLocation block(BlockPos position) {
        return new BlockLocation(position.getX(), position.getY(), position.getZ(), "minecraft:overworld");
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static <T> T require(T value, String description) {
        if (value == null) throw new AssertionError(description);
        return value;
    }

    private record Fixture(ClientDebuggerState state, DebuggerFreecam freecam, LocalPlayer player,
                           CameraType originalPerspective) { }

    private record CameraGuard(Entity entity, Vec3 position, float yaw, float pitch, Vec3 renderedPosition,
                               float renderedYaw, float renderedPitch) { }
}
