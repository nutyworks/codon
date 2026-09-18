package works.nuty.codon.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import works.nuty.codon.client.camera.DebuggerFreecam;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.PauseReason;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.network.PauseSyncPayload;
import works.nuty.codon.network.ResumeSyncPayload;
import works.nuty.codon.network.StepSyncPayload;

import java.util.List;

/**
 * Regression coverage for the temporary execution release between debugger pause snapshots.
 * Packets are sent by the integrated server so the production client receivers own every state
 * transition and freecam synchronization.
 */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerFreecamStepGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            Fixture fixture = context.computeOnClient(DebuggerFreecamStepGameTest::prepare);
            try {
                PauseSnapshot firstPause = context.computeOnClient(client -> pauseFixture(fixture.player(), "initial"));
                send(world, new PauseSyncPayload(firstPause));
                waitForPause(context, firstPause);
                CameraGuard guard = context.computeOnClient(client -> cameraGuard(client, fixture));

                // Back-to-back packets must not destroy and recreate freecam between client tasks.
                PauseSnapshot batchedPause = context.computeOnClient(client -> pauseFixture(fixture.player(), "batched"));
                world.getServer().runOnServer(server -> {
                    send(server, new StepSyncPayload());
                    send(server, new PauseSyncPayload(batchedPause));
                });
                waitForPause(context, batchedPause);
                context.runOnClient(client -> assertGuard(client, fixture, guard, "a batched step/pause round"));

                // Repeated rounds also retain the camera while normal client/render ticks occur in between.
                for (int round = 1; round <= 4; round++) {
                    int currentRound = round;
                    send(world, new StepSyncPayload());
                    waitForStep(context);
                    context.waitTicks(3);
                    context.runOnClient(client -> assertGuard(client, fixture, guard,
                        "step round " + currentRound + " after intervening client ticks"));

                    PauseSnapshot nextPause = context.computeOnClient(client ->
                        pauseFixture(fixture.player(), "separated-" + currentRound));
                    send(world, new PauseSyncPayload(nextPause));
                    waitForPause(context, nextPause);
                    context.runOnClient(client -> assertGuard(client, fixture, guard,
                        "pause round " + currentRound + " after a separated step"));
                }

                // A normal resume after a pause ends the retained freecam session.
                send(world, new ResumeSyncPayload());
                waitForTerminalResume(context);
                context.runOnClient(client -> assertTerminalResume(client, fixture, "a resume from pause"));

                // Completion can arrive while the server is stepping; it has the same terminal behavior.
                PauseSnapshot completionPause = context.computeOnClient(client ->
                    pauseFixture(fixture.player(), "completion"));
                send(world, new PauseSyncPayload(completionPause));
                waitForPause(context, completionPause);
                send(world, new StepSyncPayload());
                waitForStep(context);
                context.waitTicks(2);
                context.runOnClient(client -> require(fixture.freecam().isActive(),
                    "freecam remains active while waiting for a completion resume"));
                send(world, new ResumeSyncPayload());
                waitForTerminalResume(context);
                context.runOnClient(client -> assertTerminalResume(client, fixture, "a completion resume during stepping"));
            } finally {
                context.runOnClient(client -> {
                    fixture.state().reset();
                    fixture.freecam().synchronize(client);
                    client.options.setCameraType(fixture.originalPerspective());
                });
            }
        }
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

    private static void send(TestSingleplayerContext world, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        world.getServer().runOnServer(server -> send(server, payload));
    }

    private static void send(net.minecraft.server.MinecraftServer server,
                             net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        require(players.size() == 1, "the singleplayer test has exactly one server player");
        ServerPlayNetworking.send(players.getFirst(), payload);
    }

    private static void waitForPause(ClientGameTestContext context, PauseSnapshot expectedSnapshot) {
        context.waitFor(client -> {
            ClientDebuggerState state = require(CodonClientMod.state(), "client debugger state remains initialized");
            return state.isPaused() && !state.isStepping() && expectedSnapshot.equals(state.snapshot());
        });
    }

    private static void waitForStep(ClientGameTestContext context) {
        context.waitFor(client -> {
            ClientDebuggerState state = require(CodonClientMod.state(), "client debugger state remains initialized");
            return !state.isPaused() && state.isStepping() && state.snapshot() == null;
        });
    }

    private static void waitForTerminalResume(ClientGameTestContext context) {
        context.waitFor(client -> {
            ClientDebuggerState state = require(CodonClientMod.state(), "client debugger state remains initialized");
            DebuggerFreecam freecam = require(CodonClientMod.freecam(), "client freecam remains initialized");
            return !state.isPaused() && !state.isStepping() && state.snapshot() == null && !freecam.isActive();
        });
    }

    private static CameraGuard cameraGuard(Minecraft client, Fixture fixture) {
        require(fixture.freecam().isActive(), "the pause packet starts freecam");
        require(client.options.getCameraType() == CameraType.FIRST_PERSON,
            "freecam starts in first-person regardless of the saved perspective");
        Entity camera = require(client.getCameraEntity(), "freecam camera entity is available");
        require(camera != fixture.player(), "freecam uses a detached entity");
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
        require(camera == expected.entity(), "freecam keeps the same camera entity through " + phase);
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

    private static void assertTerminalResume(Minecraft client, Fixture fixture, String phase) {
        require(!fixture.freecam().isActive(), "freecam stops after " + phase);
        require(client.getCameraEntity() == fixture.player(), "resume restores the player camera after " + phase);
        require(client.options.getCameraType() == CameraType.THIRD_PERSON_FRONT,
            "resume restores the pre-freecam perspective after " + phase);
    }

    private static PauseSnapshot pauseFixture(LocalPlayer player, String name) {
        BlockLocation block = new BlockLocation(player.getBlockX(), player.getBlockY(), player.getBlockZ(),
            player.level().dimension().identifier().toString());
        return new PauseSnapshot(new SourceLocation.Block(block), CommandSnippet.plain("say " + name), 0,
            List.of(), List.of(), PauseReason.BREAKPOINT);
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
