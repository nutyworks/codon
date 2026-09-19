package works.nuty.codon.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import works.nuty.codon.client.camera.DebuggerFreecam;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.EntityRef;
import works.nuty.codon.core.model.PauseReason;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.Vec3d;

import java.util.List;

/**
 * Client-only freecam checks using an explicit {@link ClientDebuggerState#applyPause(PauseSnapshot)}
 * fixture. They deliberately do not claim that a server breakpoint caused the pause.
 */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerFreecamGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            Fixture fixture = context.computeOnClient(client -> {
                ClientDebuggerState state = require(CodonClientMod.state(), "client debugger state is initialized");
                DebuggerFreecam freecam = require(CodonClientMod.freecam(), "client freecam is initialized");
                LocalPlayer player = require(client.player, "local player is available");
                CameraType originalPerspective = client.options.getCameraType();
                ToggleOptions toggles = ToggleOptions.capture(client.options);
                ToggleOptions.setAll(client.options, false);
                client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);

                state.applyPause(pauseFixture(player));
                freecam.synchronize(client);
                FreecamRenderProbe.resetHandSubmissions();
                require(freecam.isActive(), "fixture pause enables freecam immediately");
                require(client.getCameraEntity() != player, "freecam replaces the player camera entity");
                require(client.options.getCameraType() == CameraType.FIRST_PERSON, "freecam uses first-person camera");
                return new Fixture(state, freecam, player, originalPerspective, toggles);
            });
            try {
                FreecamPacketProbe.start();
                verifyMoveToSelectedAnchor(context, fixture);
                context.runOnClient(client -> {
                    client.getCameraEntity().setYRot(0);
                    client.getCameraEntity().setXRot(-70);
                });
                PlayerState beforeMovement = context.computeOnClient(client -> playerState(fixture.player()));
                CameraState cameraBeforeMovement = context.computeOnClient(DebuggerFreecamGameTest::cameraState);
                context.getInput().holdKey(options -> options.keyUp);
                context.waitTicks(4);
                context.getInput().releaseKey(options -> options.keyUp);
                context.waitTicks(1);
                context.runOnClient(client -> {
                    Vec3 moved = cameraState(client).position().subtract(cameraBeforeMovement.position());
                    require(moved.z > 0 && moved.x == 0 && moved.y == 0,
                        "W moves horizontally forward even when looking steeply up");
                    require(playerState(fixture.player()).equals(beforeMovement),
                        "freecam movement leaves the local player position, velocity, and rotation unchanged");
                });

                CameraState beforeStrafe = context.computeOnClient(client -> {
                    client.getCameraEntity().setXRot(70);
                    return cameraState(client);
                });
                context.getInput().holdKey(options -> options.keyRight);
                context.waitTicks(2);
                context.getInput().releaseKey(options -> options.keyRight);
                context.runOnClient(client -> {
                    Vec3 moved = cameraState(client).position().subtract(beforeStrafe.position());
                    require(moved.x < 0 && moved.y == 0 && moved.z == 0,
                        "D strafes horizontally to the right even when looking steeply down");
                });
                CameraState beforeJump = context.computeOnClient(DebuggerFreecamGameTest::cameraState);
                context.getInput().holdKey(options -> options.keyJump);
                context.waitTicks(2);
                context.getInput().releaseKey(options -> options.keyJump);
                context.runOnClient(client -> {
                    Vec3 moved = cameraState(client).position().subtract(beforeJump.position());
                    require(moved.y > 0 && moved.x == 0 && moved.z == 0,
                        "jump raises only the camera vertically regardless of pitch");
                    require(playerState(fixture.player()).equals(beforeMovement),
                        "horizontal and vertical navigation leave the paused player unchanged");
                });

                PlayerState beforeMouse = context.computeOnClient(client -> playerState(fixture.player()));
                CameraState cameraBeforeMouse = context.computeOnClient(DebuggerFreecamGameTest::cameraState);
                float renderedYawBeforeMouse = context.computeOnClient(client -> client.gameRenderer.mainCamera().yRot());
                // Vanilla discards the first cursor sample after grabbing the mouse.
                context.getInput().moveCursor(0, 0);
                context.getInput().moveCursor(24, 12);
                context.waitTicks(1);
                context.runOnClient(client -> {
                    CameraState afterMouse = cameraState(client);
                    require(afterMouse.yaw() != cameraBeforeMouse.yaw() || afterMouse.pitch() != cameraBeforeMouse.pitch(),
                        "mouse input rotates the freecam camera");
                    require(client.gameRenderer.mainCamera().yRot() != renderedYawBeforeMouse,
                        "mouse input rotates the rendered view as well as the camera entity");
                    require(playerState(fixture.player()).equals(beforeMouse), "mouse input cannot rotate the local player");
                    verifyCameraInterpolation(client, fixture.freecam());
                });

                PlayerState beforeBlockedActions = context.computeOnClient(client -> playerState(fixture.player()));
                context.getInput().pressKey(options -> options.keyInventory);
                context.getInput().pressKey(options -> options.keyDrop);
                context.getInput().pressMouse(0);
                context.getInput().pressMouse(1);
                context.getInput().pressKey(options -> options.keyHotbarSlots[2]);
                context.getInput().scroll(1);
                context.waitTicks(1);
                context.runOnClient(client -> {
                    require(client.gui.screen() == null, "inventory input is blocked while freecam is active");
                    require(playerState(fixture.player()).equals(beforeBlockedActions),
                        "gameplay clicks, hotbar keys, and scroll do not mutate the paused player");
                });

                CameraState beforeScreen = context.computeOnClient(DebuggerFreecamGameTest::cameraState);
                context.runOnClient(client -> client.setScreenAndShow(new Screen(Component.empty()) { }));
                context.getInput().holdKey(options -> options.keyUp);
                context.waitTicks(3);
                context.getInput().releaseKey(options -> options.keyUp);
                context.runOnClient(client -> require(cameraState(client).equals(beforeScreen),
                    "an open screen stops freecam movement"));
                context.runOnClient(client -> client.setScreenAndShow(null));
                context.waitTicks(1);
                context.getInput().holdKey(options -> options.keyUp);
                context.waitTicks(2);
                context.getInput().releaseKey(options -> options.keyUp);
                context.runOnClient(client -> require(!cameraState(client).position().equals(beforeScreen.position()),
                    "freecam resumes movement after the screen closes"));

                // Accessibility toggles must be discarded as well as ordinary held keys.
                context.runOnClient(client -> ToggleOptions.setAll(client.options, true));
                context.getInput().pressMouse(0);
                context.getInput().pressMouse(1);
                context.getInput().pressKey(options -> options.keyShift);
                context.getInput().pressKey(options -> options.keySprint);
                context.waitTicks(2);
                context.runOnClient(client -> {
                    require(!client.options.keyAttack.isDown() && !client.options.keyUse.isDown(),
                        "toggle attack/use are cleared while paused");
                    require(client.options.keyShift.isDown() && client.options.keySprint.isDown(),
                        "toggle sneak/sprint remain available for freecam navigation");
                    require(playerState(fixture.player()).equals(beforeMovement),
                        "toggle navigation leaves the paused player unchanged");
                    boolean hasPlayerBody = false;
                    boolean hasCameraBody = false;
                    for (Entity entity : client.level.entitiesForRendering()) {
                        hasPlayerBody |= entity == fixture.player();
                        hasCameraBody |= entity == client.getCameraEntity();
                    }
                    require(hasPlayerBody && !hasCameraBody,
                        "the original player remains in the world and the freecam creates no duplicate body");
                    // Frame the original body to make the requested stationary ghost visible in QA.
                    Entity camera = client.getCameraEntity();
                    Vec3 origin = fixture.player().position();
                    camera.snapTo(origin.x, origin.y + 0.2, origin.z + 4, 180, 12);
                    camera.setOldPosAndRot();
                    FreecamRenderProbe.reset();
                });
                context.getInput().pressKey(options -> options.keyShift);
                context.getInput().pressKey(options -> options.keySprint);
                context.waitTicks(1);
                require(FreecamPacketProbe.packets().isEmpty(),
                    "freecam sends no gameplay packets: " + FreecamPacketProbe.packets());
                FreecamPacketProbe.stop();
                context.takeScreenshot("codon-freecam");
                require(FreecamRenderProbe.handSubmissions() == 0,
                    "freecam must not render the frozen player's first-person arms or held items; submissions="
                        + FreecamRenderProbe.handSubmissions());
                require(FreecamRenderProbe.playerPartialTick() == 1.0F,
                    "the paused player body is rendered with a fixed pose from the detached camera; observed partial="
                        + FreecamRenderProbe.playerPartialTick() + "; " + FreecamRenderProbe.visibility() + context.computeOnClient(client ->
                            "; player=" + fixture.player().position() + "; camera=" + client.gameRenderer.mainCamera().position()
                                + "; distanceVisible=" + fixture.player().shouldRenderAtSqrDistance(16)
                                + "; invisible=" + fixture.player().isInvisible() + "; spectator=" + fixture.player().isSpectator()));

                context.runOnClient(client -> {
                    client.options.keyShift.setDown(true);
                    client.options.keySprint.setDown(true);
                    client.setScreenAndShow(new Screen(Component.empty()) { });
                    fixture.state().applyResume();
                    fixture.freecam().synchronize(client);
                    require(!fixture.freecam().isActive(), "resume disables freecam");
                    require(client.getCameraEntity() == fixture.player(), "resume restores the player camera");
                    require(client.options.getCameraType() == CameraType.THIRD_PERSON_FRONT,
                        "resume restores the saved camera perspective");
                    client.setScreenAndShow(null);
                    require(!client.options.keyAttack.isDown() && !client.options.keyUse.isDown()
                            && !client.options.keyShift.isDown() && !client.options.keySprint.isDown(),
                        "resume clears toggle states including pending screen-close restoration");
                    fixture.state().preferences().setKeepFreecam(true);
                    try {
                        fixture.state().applyPause(pauseFixture(fixture.player()));
                        fixture.freecam().synchronize(client);
                        Entity retained = client.getCameraEntity();
                        Vec3 retainedPosition = retained.position();
                        fixture.state().applyResume();
                        fixture.freecam().synchronize(client);
                        require(fixture.freecam().isActive() && client.getCameraEntity() == retained,
                            "enabled preference retains the camera after terminal resume");
                        require(retained.position().equals(retainedPosition),
                            "terminal resume preserves the freecam position");
                        fixture.state().preferences().setKeepFreecam(false);
                        fixture.freecam().synchronize(client);
                        require(!fixture.freecam().isActive() && client.getCameraEntity() == fixture.player(),
                            "disabling retention while running restores the player camera");

                        fixture.state().preferences().setKeepFreecam(true);
                        fixture.state().applyPause(pauseFixture(fixture.player()));
                        fixture.freecam().synchronize(client);
                        fixture.state().applyResume();
                        fixture.freecam().synchronize(client);
                        fixture.state().reset();
                        fixture.freecam().synchronize(client);
                        require(!fixture.freecam().isActive(),
                            "session reset releases retained freecam even when preference is enabled");
                    } finally {
                        fixture.state().preferences().setKeepFreecam(false);
                        fixture.state().applyResume();
                        fixture.freecam().synchronize(client);
                    }
                    client.options.setCameraType(CameraType.FIRST_PERSON);
                    FreecamRenderProbe.resetHandSubmissions();
                });
                context.waitTicks(2);
                context.takeScreenshot("codon-freecam-resumed");
                require(FreecamRenderProbe.handSubmissions() > 0,
                    "normal first-person arms and held items render again after freecam resumes");
                context.runOnClient(client -> {
                    require(client.gui.screen() == null, "blocked inventory input is not replayed after resume");
                    require(fixture.player().getInventory().getSelectedSlot() == beforeBlockedActions.selectedSlot(),
                        "blocked hotbar input is not replayed after resume");
                    fixture.state().applyPause(pauseFixture(fixture.player()));
                    fixture.freecam().synchronize(client);
                    require(fixture.freecam().isActive(), "a second pause reactivates freecam");
                    fixture.state().reset();
                    fixture.freecam().synchronize(client);
                    require(!fixture.freecam().isActive(), "reset disables active freecam");
                    require(client.getCameraEntity() == fixture.player(), "reset leaves the player as camera");
                });
            } finally {
                FreecamPacketProbe.stop();
                context.runOnClient(client -> {
                    fixture.state().reset();
                    fixture.freecam().synchronize(client);
                    client.setScreenAndShow(null);
                    client.options.setCameraType(fixture.originalPerspective());
                    fixture.toggles().restore(client.options);
                });
            }
        }
    }

    private static PauseSnapshot pauseFixture(LocalPlayer player) {
        BlockLocation block = new BlockLocation(player.getBlockX(), player.getBlockY(), player.getBlockZ(),
            player.level().dimension().identifier().toString());
        SourceLocation location = new SourceLocation.Block(block);
        return new PauseSnapshot(location, CommandSnippet.plain("say freecam fixture"), 0, List.of(), List.of(),
            PauseReason.BREAKPOINT);
    }

    /**
     * The selected source's anchor is the command execution reference point. It can differ from
     * the attached executor's current position, so this checks the rendered eye against the
     * captured anchor and never against the player entity.
     */
    private static void verifyMoveToSelectedAnchor(ClientGameTestContext context, Fixture fixture) {
        context.runOnClient(client -> {
            LocalPlayer player = fixture.player();
            String dimension = player.level().dimension().identifier().toString();
            Vec3 anchor = player.position().add(13.25, 7.5, -9.75);
            PauseSource selected = new PauseSource(new Vec3d(anchor.x, anchor.y, anchor.z), 23.5F, -137.25F,
                new EntityRef(player.getUUID(), player.getName().getString()), dimension);
            require(anchor.distanceToSqr(player.getEyePosition()) > 1.0,
                "fixture anchor is intentionally distinct from its attached entity's current eye position");
            fixture.state().applyPause(pauseFixture(player, List.of(selected)));
            fixture.freecam().synchronize(client);

            require(fixture.freecam().selectedAnchorStatus(client).equals("ready"),
                "a paused selected source in the current dimension reports a reachable anchor");
            PlayerState playerBeforeMove = playerState(player);
            require(fixture.freecam().moveToSelectedAnchor(client), "the selected anchor is reachable");
            client.gameRenderer.mainCamera().update(DeltaTracker.ONE);
            require(client.gameRenderer.mainCamera().position().distanceToSqr(anchor) < 1.0E-8,
                "freecam eye moves to the selected execution anchor");
            require(client.getCameraEntity().getYRot() == selected.yaw()
                    && client.getCameraEntity().getXRot() == selected.pitch(),
                "freecam adopts the selected execution rotation");
            require(playerState(player).equals(playerBeforeMove),
                "moving to an execution anchor does not move or rotate the local player");

            CameraState beforeRejectedMove = cameraState(client);
            fixture.state().applyPause(pauseFixture(player, List.of()));
            require(fixture.freecam().selectedAnchorStatus(client).equals("no_selection"),
                "a pause without sources explains that no execution context is selected");
            require(!fixture.freecam().moveToSelectedAnchor(client), "a missing selection cannot move freecam");
            require(cameraState(client).equals(beforeRejectedMove), "a rejected move leaves the freecam pose intact");

            PauseSource otherDimension = new PauseSource(new Vec3d(anchor.x, anchor.y, anchor.z), 0, 0,
                null, "minecraft:the_nether");
            fixture.state().applyPause(pauseFixture(player, List.of(otherDimension)));
            require(fixture.freecam().selectedAnchorStatus(client).equals("other_dimension"),
                "a selected anchor in another dimension reports why it cannot be reached");
            require(!fixture.freecam().moveToSelectedAnchor(client), "another dimension cannot move the local freecam");
            require(cameraState(client).equals(beforeRejectedMove), "another-dimension rejection preserves the freecam pose");

            PauseSource nonFinite = new PauseSource(new Vec3d(Double.NaN, anchor.y, anchor.z), 0, 0,
                null, dimension);
            fixture.state().applyPause(pauseFixture(player, List.of(nonFinite)));
            require(fixture.freecam().selectedAnchorStatus(client).equals("invalid_position"),
                "a non-finite captured anchor reports invalid position");
            require(!fixture.freecam().moveToSelectedAnchor(client), "a non-finite anchor cannot move freecam");
            require(cameraState(client).equals(beforeRejectedMove), "invalid-position rejection preserves the freecam pose");

            fixture.state().applyResume();
            require(fixture.freecam().selectedAnchorStatus(client).equals("not_paused"),
                "terminal resume makes selected-anchor movement unavailable");
            require(!fixture.freecam().moveToSelectedAnchor(client), "a running debugger cannot move freecam to an anchor");
            fixture.freecam().synchronize(client);

            fixture.state().applyPause(pauseFixture(player));
            fixture.freecam().synchronize(client);
            require(fixture.freecam().isActive(), "anchor rejection cases leave the fixture able to resume freecam testing");
        });
    }

    private static PauseSnapshot pauseFixture(LocalPlayer player, List<PauseSource> sources) {
        BlockLocation block = new BlockLocation(player.getBlockX(), player.getBlockY(), player.getBlockZ(),
            player.level().dimension().identifier().toString());
        SourceLocation location = new SourceLocation.Block(block);
        return new PauseSnapshot(location, CommandSnippet.plain("say freecam anchor fixture"), 0, List.of(), sources,
            PauseReason.BREAKPOINT);
    }

    /** Exercise the rendered camera at sub-tick positions, including the boundary between ticks. */
    private static void verifyCameraInterpolation(Minecraft client, DebuggerFreecam freecam) {
        Entity camera = client.getCameraEntity();
        CameraState saved = cameraState(client);
        try {
            for (int yaw : new int[] {0, 90, 180, 270}) {
                camera.snapTo(saved.position().x, saved.position().y, saved.position().z, yaw, -60);
                camera.setOldPosAndRot();
                client.gameRenderer.mainCamera().update(DeltaTracker.ONE);
                Vec3 previousEnd = client.gameRenderer.mainCamera().position();
                var facing = client.gameRenderer.mainCamera().forwardVector();
                Vec3 horizontalFacing = new Vec3(facing.x(), 0, facing.z()).normalize();
                client.options.keyUp.setDown(true);
                for (int tick = 0; tick < 2; tick++) {
                    freecam.tick(client);
                    for (float fraction : new float[] {0, 0.25F, 0.5F, 0.75F, 1}) {
                        client.gameRenderer.mainCamera().update(new FixedDelta(fraction));
                        Vec3 expected = previousEnd.add(horizontalFacing.scale(0.4 * fraction));
                        require(client.gameRenderer.mainCamera().position().distanceToSqr(expected) < 1.0E-8,
                            "rendered camera follows horizontal view direction without snapping backward between ticks at yaw=" + yaw);
                    }
                    previousEnd = client.gameRenderer.mainCamera().position();
                }
                client.options.keyUp.setDown(false);
                freecam.tick(client);
                for (float fraction : new float[] {0, 0.5F, 1}) {
                    client.gameRenderer.mainCamera().update(new FixedDelta(fraction));
                    require(client.gameRenderer.mainCamera().position().distanceToSqr(previousEnd) < 1.0E-8,
                        "releasing movement leaves the rendered camera stationary throughout the next tick");
                }
            }
        } finally {
            client.options.keyUp.setDown(false);
            camera.snapTo(saved.position().x, saved.position().y, saved.position().z, saved.yaw(), saved.pitch());
            camera.setOldPosAndRot();
            client.gameRenderer.mainCamera().update(DeltaTracker.ONE);
        }
    }

    private record FixedDelta(float fraction) implements DeltaTracker {
        @Override public float getGameTimeDeltaTicks() { return 0; }
        @Override public float getGameTimeDeltaPartialTick(boolean ignored) { return fraction; }
        @Override public float getRealtimeDeltaTicks() { return 0; }
    }

    private static CameraState cameraState(Minecraft client) {
        Entity camera = require(client.getCameraEntity(), "camera entity is available");
        return new CameraState(camera.position(), camera.getDeltaMovement(), camera.getYRot(), camera.getXRot());
    }

    private static PlayerState playerState(LocalPlayer player) {
        return new PlayerState(player.position(), player.getDeltaMovement(), player.getYRot(), player.getXRot(),
            player.getInventory().getSelectedSlot());
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static <T> T require(T value, String description) {
        if (value == null) throw new AssertionError(description);
        return value;
    }

    private record Fixture(ClientDebuggerState state, DebuggerFreecam freecam, LocalPlayer player,
                           CameraType originalPerspective, ToggleOptions toggles) {
    }

    private record ToggleOptions(boolean attack, boolean use, boolean crouch, boolean sprint) {
        static ToggleOptions capture(Options options) {
            return new ToggleOptions(options.toggleAttack().get(), options.toggleUse().get(),
                options.toggleCrouch().get(), options.toggleSprint().get());
        }

        static void setAll(Options options, boolean value) {
            new ToggleOptions(value, value, value, value).restore(options);
        }

        void restore(Options options) {
            options.toggleAttack().set(attack);
            options.toggleUse().set(use);
            options.toggleCrouch().set(crouch);
            options.toggleSprint().set(sprint);
        }
    }

    private record CameraState(Vec3 position, Vec3 velocity, float yaw, float pitch) {
    }

    private record PlayerState(Vec3 position, Vec3 velocity, float yaw, float pitch, int selectedSlot) {
    }
}
