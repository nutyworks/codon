package works.nuty.bastion.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.phys.Vec3;
import works.nuty.bastion.client.camera.DebuggerFreecam;
import works.nuty.bastion.client.state.ClientDebuggerState;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.CommandSnippet;
import works.nuty.bastion.core.model.PauseReason;
import works.nuty.bastion.core.model.PauseSnapshot;
import works.nuty.bastion.core.model.SourceLocation;

import java.util.List;

/** Explicit client pause fixture; this does not assert that a server breakpoint produced the pause. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerMountedFreecamGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            Fixture fixture = context.computeOnClient(client -> createMountedPauseFixture(client));
            try {
                VehicleState vehicleAtPause = context.computeOnClient(client -> vehicleState(fixture.vehicle()));
                PlayerState playerAtPause = context.computeOnClient(client -> playerState(fixture.player()));
                Vec3 cameraAtPause = context.computeOnClient(client -> client.getCameraEntity().position());
                context.getInput().holdKey(options -> options.keyUp);
                context.getInput().holdKey(options -> options.keyJump);
                context.waitTicks(4);
                context.getInput().releaseKey(options -> options.keyUp);
                context.getInput().releaseKey(options -> options.keyJump);
                context.runOnClient(client -> {
                    require(vehicleState(fixture.vehicle()).equals(vehicleAtPause),
                        "paused root vehicle cannot tick, move, or alter interpolation state");
                    require(playerState(fixture.player()).equals(playerAtPause),
                        "paused mounted player cannot tick or move with its vehicle");
                    require(client.getCameraEntity() != fixture.player(), "mounted pause still uses the freecam camera");
                    require(!client.getCameraEntity().position().equals(cameraAtPause),
                        "navigation moves the freecam while the player and root vehicle remain frozen");
                });

                context.runOnClient(client -> {
                    fixture.state().applyResume();
                    fixture.freecam().synchronize(client);
                    require(!fixture.freecam().isActive(), "resume disables mounted freecam");
                    require(client.getCameraEntity() == fixture.player(), "resume restores the mounted player camera");
                });
            } finally {
                context.runOnClient(client -> cleanup(client, fixture));
            }
        }
    }

    private static Fixture createMountedPauseFixture(Minecraft client) {
        ClientDebuggerState state = require(BastionClientMod.state(), "client debugger state is initialized");
        DebuggerFreecam freecam = require(BastionClientMod.freecam(), "client freecam is initialized");
        LocalPlayer player = require(client.player, "local player is available");
        require(client.level != null, "client level is available");
        state.reset();
        freecam.synchronize(client);

        Minecart vehicle = new Minecart(EntityTypes.MINECART, client.level);
        int vehicleId = 1_000_000;
        while (client.level.getEntity(vehicleId) != null) vehicleId++;
        vehicle.setId(vehicleId);
        vehicle.setNoGravity(true);
        vehicle.setPos(player.position());
        vehicle.setDeltaMovement(0.4, 0, 0);
        client.level.addEntity(vehicle);
        require(player.startRiding(vehicle, true, true), "test player mounts the client-only minecart");
        require(player.getRootVehicle() == vehicle, "minecart is the mounted player's root vehicle");

        // Model an interpolated vehicle update: activation must make old and current positions agree.
        vehicle.setOldPosAndRot(vehicle.position().add(-2, 0, 0), vehicle.getYRot(), vehicle.getXRot());
        state.applyPause(pauseFixture(player));
        freecam.synchronize(client);
        require(freecam.isActive(), "pause enables freecam for a mounted player");
        require(vehicle.oldPosition().equals(vehicle.position()),
            "freecam activation normalizes root-vehicle interpolation state");
        return new Fixture(state, freecam, player, vehicle);
    }

    private static void cleanup(Minecraft client, Fixture fixture) {
        fixture.state().reset();
        fixture.freecam().synchronize(client);
        fixture.player().removeVehicle();
        if (client.level != null) {
            client.level.removeEntity(fixture.vehicle().getId(), Entity.RemovalReason.DISCARDED);
        }
    }

    private static PauseSnapshot pauseFixture(LocalPlayer player) {
        BlockLocation block = new BlockLocation(player.getBlockX(), player.getBlockY(), player.getBlockZ(),
            player.level().dimension().identifier().toString());
        SourceLocation location = new SourceLocation.Block(block);
        return new PauseSnapshot(location, CommandSnippet.plain("say mounted freecam fixture"), 0, List.of(), List.of(),
            PauseReason.BREAKPOINT);
    }

    private static VehicleState vehicleState(Minecart vehicle) {
        return new VehicleState(vehicle.position(), vehicle.oldPosition(), vehicle.getDeltaMovement());
    }

    private static PlayerState playerState(LocalPlayer player) {
        return new PlayerState(player.position(), player.oldPosition(), player.getDeltaMovement(),
            player.getYRot(), player.getXRot());
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static <T> T require(T value, String description) {
        if (value == null) throw new AssertionError(description);
        return value;
    }

    private record Fixture(ClientDebuggerState state, DebuggerFreecam freecam, LocalPlayer player, Minecart vehicle) {
    }

    private record VehicleState(Vec3 position, Vec3 oldPosition, Vec3 velocity) {
    }

    private record PlayerState(Vec3 position, Vec3 oldPosition, Vec3 velocity, float yaw, float pitch) {
    }
}
