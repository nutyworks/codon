package works.nuty.codon.client.camera;

import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.ToggleKeyMapping;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.mixin.client.ToggleKeyMappingAccessor;

/** A client-only viewpoint: never added to the world and never used for player movement packets. */
public final class DebuggerFreecam {
    private final ClientDebuggerState state;
    private @Nullable RemotePlayer camera;
    private @Nullable LocalPlayer suspendedPlayer;
    private @Nullable Entity previousCamera;
    private @Nullable CameraType previousPerspective;
    private @Nullable PauseSnapshot abandonedSnapshot;

    public DebuggerFreecam(ClientDebuggerState state) {
        this.state = state;
    }

    /** Called immediately after sync packets as well as before each client tick. */
    public void synchronize(Minecraft client) {
        if (!state.isPaused() || state.snapshot() == null) {
            // Step and Continue can reach another pause within this execution. Only its terminal
            // resume normally ends inspection. The preference also retains it after completion.
            if ((state.isStepping() || state.isContinuing()
                    || (state.preferences().keepFreecam() && state.inspectionSnapshot() != null))
                    && isActive()) return;
            stop(client);
            abandonedSnapshot = null;
            return;
        }
        if (camera != null && (client.player != suspendedPlayer || client.level != camera.level())) {
            // A respawn/world replacement must not carry the old camera into a new session.
            abandonedSnapshot = state.snapshot();
            stop(client);
            return;
        }
        if (camera == null && client.player != null && client.level != null
                && state.snapshot() != abandonedSnapshot) {
            suspendedPlayer = client.player;
            previousCamera = client.getCameraEntity();
            previousPerspective = client.options.getCameraType();
            var view = client.gameRenderer.mainCamera();
            Entity origin = previousCamera != null ? previousCamera : client.player;
            Vec3 eye = view.isInitialized() ? view.position() : origin.getEyePosition();
            float yaw = view.isInitialized() ? view.yRot() : origin.getYRot();
            float pitch = view.isInitialized() ? view.xRot() : origin.getXRot();
            camera = new RemotePlayer(client.level, client.player.getGameProfile()) {
                @Override
                public boolean isSpectator() { return true; }

                // This entity never ticks a living body's separate head rotation.
                // Use the same yaw as mouse look, movement, and camera ray picking.
                @Override
                public float getViewYRot(float partialTick) { return getYRot(partialTick); }
            };
            camera.snapTo(eye.x, eye.y - camera.getEyeHeight(), eye.z, yaw, pitch);
            camera.setOldPosAndRot();
            suspendedPlayer.setOldPosAndRot();
            suspendedPlayer.getRootVehicle().setOldPosAndRot();
            client.options.setCameraType(CameraType.FIRST_PERSON);
            client.setCameraEntity(camera);
        }
        if (isActive() && client.gui.screen() instanceof AbstractContainerScreen<?>) {
            // Finish an already-open inventory session once, before freezing its input.
            // Vanilla queues its close notification; no inventory mutations are performed here.
            client.player.closeContainer();
        }
    }

    public void tick(Minecraft client) {
        synchronize(client);
        if (!isActive()) return;
        camera.setOldPosAndRot();
        drainGameplayClicks(client.options);
        if (client.gui.screen() != null || client.gui.overlay() != null || !client.mouseHandler.isMouseGrabbed()) return;

        Options options = client.options;
        double forward = direction(options.keyUp, options.keyDown);
        double sideways = direction(options.keyRight, options.keyLeft);
        double vertical = direction(options.keyJump, options.keyShift);
        double yaw = Math.toRadians(camera.getYRot());
        // Match vanilla flight: looking up/down never tilts WASD out of the horizontal plane.
        Vec3 movement = new Vec3(
            -Math.sin(yaw) * forward - Math.cos(yaw) * sideways,
            0,
            Math.cos(yaw) * forward - Math.sin(yaw) * sideways);
        if (movement.lengthSqr() > 1) movement = movement.normalize();
        movement = movement.add(0, vertical, 0);
        double speed = options.keySprint.isDown() ? 1.2 : 0.4;
        camera.setPos(camera.position().add(movement.scale(speed)));
    }

    public boolean isActive() {
        Minecraft client = Minecraft.getInstance();
        return camera != null && client.player == suspendedPlayer && client.level == camera.level();
    }

    /** Availability is rechecked on activation so a step cannot use a stale UI target. */
    public String selectedAnchorStatus(Minecraft client) {
        if (!state.isPaused() || state.snapshot() == null) return "not_paused";
        if (state.controlPending()) return "pending";
        var source = state.selectedSource();
        if (source == null) return "no_selection";
        if (!isActive() || client.getCameraEntity() != camera) return "unavailable";
        if (!source.dimension().equals(client.level.dimension().identifier().toString())) return "other_dimension";
        var anchor = source.anchor();
        if (!Double.isFinite(anchor.x()) || !Double.isFinite(anchor.y()) || !Double.isFinite(anchor.z())
                || !Float.isFinite(source.yaw()) || !Float.isFinite(source.pitch())) return "invalid_position";
        return "ready";
    }

    /** Moves only the detached camera; the recorded execution anchor is the new eye position. */
    public boolean moveToSelectedAnchor(Minecraft client) {
        if (!selectedAnchorStatus(client).equals("ready")) return false;
        var source = state.selectedSource();
        var anchor = source.anchor();
        camera.snapTo(anchor.x(), anchor.y() - camera.getEyeHeight(), anchor.z(),
            source.yaw(), Math.clamp(source.pitch(), -90.0f, 90.0f));
        camera.setOldPosAndRot();
        return true;
    }

    public boolean freezes(Entity entity) {
        return state.isPaused() && isActive()
            && (entity == suspendedPlayer || entity == suspendedPlayer.getRootVehicle());
    }

    /** Receives the same sensitivity/inversion-adjusted deltas as vanilla LocalPlayer.turn. */
    public void turn(double horizontal, double vertical) {
        if (isActive()) camera.turn(horizontal, vertical);
    }

    public void stop(Minecraft client) {
        if (camera == null) return;
        if (client.getCameraEntity() == camera) {
            Entity restored = previousCamera != null && previousCamera.level() == client.level && !previousCamera.isRemoved()
                ? previousCamera : client.player;
            client.setCameraEntity(restored);
        }
        if (previousPerspective != null) client.options.setCameraType(previousPerspective);
        drainGameplayClicks(client.options);
        // Camera navigation held at resume must not become an unintended player movement.
        for (KeyMapping key : new KeyMapping[] {client.options.keyUp, client.options.keyDown,
                client.options.keyLeft, client.options.keyRight, client.options.keyJump,
                client.options.keyShift, client.options.keySprint}) {
            clear(key);
        }
        camera = null;
        suspendedPlayer = null;
        previousCamera = null;
        previousPerspective = null;
    }

    /** Keep chat/debugger controls available; discard gameplay actions instead of replaying them later. */
    public void handlePausedKeybinds(Minecraft client) {
        client.gui.handleKeybinds();
        drainGameplayClicks(client.options);
    }

    private static void drainGameplayClicks(Options options) {
        for (KeyMapping key : new KeyMapping[] {options.keyAttack, options.keyUse, options.keyPickItem,
                options.keyDrop, options.keySwapOffhand, options.keyInventory, options.keyQuickActions,
                options.keyTogglePerspective, options.keySmoothCamera, options.keyToggleSpectatorShaderEffects,
                options.keySpectatorHotbar, options.keySaveHotbarActivator, options.keyLoadHotbarActivator}) {
            clear(key);
        }
        for (KeyMapping key : options.keyHotbarSlots) {
            clear(key);
        }
    }

    private static void clear(KeyMapping key) {
        if (key instanceof ToggleKeyMapping) {
            var toggle = (ToggleKeyMappingAccessor) key;
            toggle.codon$reset();
            // A screen can otherwise restore the previous toggle after the pause ends.
            toggle.codon$setReleasedByScreenWhenDown(false);
        } else {
            key.setDown(false);
        }
        drain(key);
    }

    private static void drain(KeyMapping key) {
        while (key.consumeClick()) { }
    }

    private static int direction(KeyMapping positive, KeyMapping negative) {
        return (positive.isDown() ? 1 : 0) - (negative.isDown() ? 1 : 0);
    }
}
