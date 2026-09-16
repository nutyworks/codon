package works.nuty.bastion.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.BossEvent;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import works.nuty.bastion.client.camera.DebuggerFreecam;
import works.nuty.bastion.client.state.ClientDebuggerState;
import works.nuty.bastion.client.testmixin.ParticleAccessor;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.CommandSnippet;
import works.nuty.bastion.core.model.PauseReason;
import works.nuty.bastion.core.model.PauseSnapshot;
import works.nuty.bastion.core.model.SourceLocation;

import java.util.List;

/**
 * Client pause coverage using a synthetic synced debugger state. It exercises the production
 * client loop; it does not model a server breakpoint or assert audio behavior.
 */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerClientPauseGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            Fixture fixture = context.computeOnClient(DebuggerClientPauseGameTest::prepare);
            try {
                // Move the real particle from its add queue before taking its simulation snapshot.
                context.waitTicks(2);
                RunningState beforePause = context.computeOnClient(client -> {
                    fixture.state().applyPause(pauseFixture(fixture.player()));
                    fixture.freecam().synchronize(client);
                    BastionClientMod.pauseEffects().synchronize(client);
                    fixture.bossBar().setProgress(0.25F);
                    require(fixture.freecam().isActive(), "fixture pause activates the production freecam");
                    ClientPauseProbe.resetTextureTicks();
                    return runningState(client, fixture);
                });

                context.getInput().holdKey(options -> options.keyUp);
                context.waitTicks(5);
                context.getInput().releaseKey(options -> options.keyUp);
                context.runOnClient(client -> {
                    RunningState paused = runningState(client, fixture);
                    require(paused.gameTime() == beforePause.gameTime(), "pause freezes ClientLevel game time");
                    require(paused.vehicleTicks() == beforePause.vehicleTicks()
                            && paused.vehiclePosition().equals(beforePause.vehiclePosition()),
                        "pause freezes client entity ticking and transforms");
                    require(paused.particle().equals(beforePause.particle()) && fixture.particle().isAlive(),
                        "pause preserves the real flame particle age, position, and lifetime");
                    require(close(paused.worldPartialTick(), beforePause.worldPartialTick()),
                        "pause freezes the world partial tick");
                    require(paused.glint().equals(beforePause.glint()) && paused.bossProgress() == beforePause.bossProgress(),
                        "pause freezes wall-clock glint animation and boss-bar interpolation");
                    require(ClientPauseProbe.textureTicks() == 0,
                        "pause skips TextureManager animation ticks in the real client loop");
                    require(!paused.cameraPosition().equals(beforePause.cameraPosition()),
                        "freecam input remains live while world simulation is frozen");
                    fixture.state().applyResume();
                    fixture.freecam().synchronize(client);
                });

                context.waitTicks(3);
                context.runOnClient(client -> {
                    RunningState resumed = runningState(client, fixture);
                    require(resumed.gameTime() > beforePause.gameTime(), "resume restarts ClientLevel time");
                    require(resumed.vehicleTicks() > beforePause.vehicleTicks(), "resume restarts client entity ticking");
                    require(resumed.particle().age() > beforePause.particle().age(), "resume restarts particle aging");
                    require(!resumed.particle().position().equals(beforePause.particle().position()),
                        "resume restarts particle motion");
                    require(ClientPauseProbe.textureTicks() > 0, "resume restarts TextureManager animation ticks");
                });
            } finally {
                context.runOnClient(client -> cleanup(client, fixture));
            }
        }
    }

    private static Fixture prepare(Minecraft client) {
        ClientDebuggerState state = require(BastionClientMod.state(), "client debugger state is initialized");
        DebuggerFreecam freecam = require(BastionClientMod.freecam(), "client freecam is initialized");
        LocalPlayer player = require(client.player, "local player is available");
        require(client.level != null, "client level is available");
        state.reset();
        freecam.synchronize(client);

        Minecart vehicle = new Minecart(EntityTypes.MINECART, client.level);
        int id = 1_100_000;
        while (client.level.getEntity(id) != null) id++;
        vehicle.setId(id);
        vehicle.setNoGravity(true);
        vehicle.setPos(player.getX() + 3, player.getY(), player.getZ());
        vehicle.setDeltaMovement(0.15, 0, 0);
        client.level.addEntity(vehicle);

        Particle particle = require(client.particleEngine.createParticle(ParticleTypes.FLAME,
            player.getX() + 2, player.getY() + 1, player.getZ(), 0.05, 0.1, 0), "flame particle is available");
        particle.setLifetime(100);
        LerpingBossEvent bossBar = new LerpingBossEvent(java.util.UUID.randomUUID(), Component.literal("pause fixture"),
            1.0F, BossEvent.BossBarColor.WHITE, BossEvent.BossBarOverlay.PROGRESS, false, false, false);
        return new Fixture(state, freecam, player, vehicle, particle, bossBar);
    }

    private static RunningState runningState(Minecraft client, Fixture fixture) {
        ParticleAccessor particle = (ParticleAccessor) fixture.particle();
        return new RunningState(client.level.getGameTime(), fixture.vehicle().tickCount, fixture.vehicle().position(),
            new ParticleState(particle.bastion$age(), new Vec3(particle.bastion$x(), particle.bastion$y(), particle.bastion$z())),
            client.getDeltaTracker().getGameTimeDeltaPartialTick(false), client.getCameraEntity().position(),
            TextureTransform.GLINT_TEXTURING.createMatrix(), fixture.bossBar().getProgress());
    }

    private static PauseSnapshot pauseFixture(LocalPlayer player) {
        BlockLocation block = new BlockLocation(player.getBlockX(), player.getBlockY(), player.getBlockZ(),
            player.level().dimension().identifier().toString());
        return new PauseSnapshot(new SourceLocation.Block(block), CommandSnippet.plain("say client pause fixture"), 0,
            List.of(), List.of(), PauseReason.BREAKPOINT);
    }

    private static void cleanup(Minecraft client, Fixture fixture) {
        fixture.state().reset();
        fixture.freecam().synchronize(client);
        if (client.level != null) client.level.removeEntity(fixture.vehicle().getId(), Entity.RemovalReason.DISCARDED);
    }

    private static boolean close(float actual, float expected) {
        return Math.abs(actual - expected) < 0.0001F;
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static <T> T require(T value, String description) {
        if (value == null) throw new AssertionError(description);
        return value;
    }

    private record Fixture(ClientDebuggerState state, DebuggerFreecam freecam, LocalPlayer player, Minecart vehicle,
                           Particle particle, LerpingBossEvent bossBar) { }

    private record ParticleState(int age, Vec3 position) { }

    private record RunningState(long gameTime, int vehicleTicks, Vec3 vehiclePosition, ParticleState particle,
                                float worldPartialTick, Vec3 cameraPosition, Matrix4f glint, float bossProgress) { }
}
