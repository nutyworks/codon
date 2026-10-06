package works.nuty.codon.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.network.ClientNetworking;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.ScreenLayers;

/**
 * Client keybinds for the debugger. Control actions (resume/step) are issued as {@code /codon}
 * commands — the server is the source of truth — and gated on the synced pause state. Opening the
 * window editor is purely client-side.
 */
public final class InputManager implements ClientTickEvents.EndTick {
    public static final Identifier CATEGORY_ID = Identifier.fromNamespaceAndPath("codon", "debugger");

    private final ClientDebuggerState state;
    private final OpenScreen openScreen;
    private final UiHideGesture uiHide = new UiHideGesture();
    private ClientLevel hideLevel;
    private InputConstants.Key hideBinding;

    public KeyMapping menuKey;
    public KeyMapping keepFreecamKey;
    public KeyMapping hideUiKey;
    public KeyMapping breakpointKey;
    public KeyMapping resumeKey;
    public KeyMapping stepOverKey;
    public KeyMapping stepIntoKey;

    public enum Control {
        RESUME("resume"), OVER("stepover"), INTO("stepinto"), OUT("stepout");
        private final String command;
        Control(String command) { this.command = command; }
        public String translationKey() { return "codon.ui.control." + command; }
    }

    public InputManager(ClientDebuggerState state, OpenScreen openScreen) {
        this.state = state;
        this.openScreen = openScreen;
    }

    @Override
    public void onEndTick(Minecraft client) {
        synchronizeUiVisibility(client);
        if (hideUiKey != null) while (hideUiKey.consumeClick()) { }
        if (client.player == null) {
            return;
        }

        if (state.isPaused()) {
            while (resumeKey.consumeClick()) {
                control(Control.RESUME);
            }
            while (stepOverKey.consumeClick()) {
                control(Control.OVER);
            }
            while (stepIntoKey.consumeClick()) {
                control(client.hasControlDown() ? Control.OUT : Control.INTO);
            }
        } else {
            // Drain clicks so they don't fire later when paused.
            resumeKey.consumeClick();
            stepOverKey.consumeClick();
            stepIntoKey.consumeClick();
        }

        while (breakpointKey.consumeClick()) {
            toggleTargetBreakpoint();
        }

        while (keepFreecamKey.consumeClick()) {
            toggleKeepFreecam();
        }

        while (menuKey.consumeClick()) {
            openScreen.open(this);
        }
    }

    public void toggleKeepFreecam() {
        state.preferences().setKeepFreecam(!state.preferences().keepFreecam());
    }

    /** Shared by screen, HUD, and world markers; never changes the debugger's pause state. */
    public boolean isUiHidden() {
        synchronizeUiVisibility(Minecraft.getInstance());
        return uiHide.isHidden();
    }

    public void resetUiVisibility() {
        uiHide.reset();
        if (hideUiKey != null) {
            hideUiKey.setDown(false);
            while (hideUiKey.consumeClick()) { }
        }
    }

    private boolean acceptsHideInput(Minecraft client) {
        var screen = client.gui.screen();
        return client.player != null && client.level != null && client.isWindowActive()
            && client.gui.overlay() == null
            && (screen == null || screen instanceof CodonScreen
                && ScreenLayers.get(screen) == null && !(screen.getFocused() instanceof EditBox));
    }

    private void synchronizeUiVisibility(Minecraft client) {
        var binding = hideUiKey == null ? null : KeyMappingHelper.getBoundKeyOf(hideUiKey);
        if (hideLevel != client.level || !java.util.Objects.equals(hideBinding, binding)) {
            resetUiVisibility();
            // Any pending release belongs to the previous world/binding.
            uiHide.release(System.nanoTime());
            hideLevel = client.level;
            hideBinding = binding;
        }
        if (!acceptsHideInput(client) || hideUiKey == null || hideUiKey.isUnbound()) resetUiVisibility();
        // SDL may not deliver a release after focus loss. Re-arm only once the physical key is up.
        if (uiHide.awaitingRelease() && binding != null && binding.getType() == InputConstants.Type.KEYBOARD
            && !InputConstants.isKeyDown(binding.getValue())) uiHide.release(System.nanoTime());
    }

    public boolean handleHideKey(KeyEvent event, int action) {
        synchronizeUiVisibility(Minecraft.getInstance());
        if (hideUiKey == null || !hideUiKey.matches(event)) return false;
        return handleHideAction(action);
    }

    public boolean handleHideMouse(MouseButtonEvent event, int action) {
        synchronizeUiVisibility(Minecraft.getInstance());
        if (hideUiKey == null || !hideUiKey.matchesMouse(event)) return false;
        // Mouse buttons do not repeat PRESS. A new press follows a physical release,
        // even when that release happened outside the window and its callback was lost.
        if (action == InputConstants.PRESS && uiHide.awaitingRelease()
            && acceptsHideInput(Minecraft.getInstance())) uiHide.release(System.nanoTime());
        return handleHideAction(action);
    }

    private boolean handleHideAction(int action) {
        if (action == InputConstants.RELEASE) uiHide.release(System.nanoTime());
        else if (action == InputConstants.PRESS && acceptsHideInput(Minecraft.getInstance())) uiHide.press(System.nanoTime());
        // Repeats cannot restart the timer or toggle. Only a physical release classifies a press.
        return acceptsHideInput(Minecraft.getInstance());
    }

    public void control(Control action) {
        Minecraft client = Minecraft.getInstance();
        var snapshot = state.snapshot();
        if (client.player != null && snapshot != null && snapshot.pauseId() > 0
            && (action == Control.RESUME || !state.watchReadsFailed())
            && (action == Control.RESUME && state.controlAwaitingReads() || state.beginControlRequest())) {
            ClientNetworking.requestControl(client, state, snapshot.pauseId(), action.command, action != Control.RESUME);
        }
    }

    public Component keyLabel(Control action) {
        return switch (action) {
            case RESUME -> resumeKey.getTranslatedKeyMessage();
            case OVER -> stepOverKey.getTranslatedKeyMessage();
            case INTO -> stepIntoKey.getTranslatedKeyMessage();
            case OUT -> Component.translatable("codon.ui.control.stepout_shortcut", stepIntoKey.getTranslatedKeyMessage());
        };
    }

    private @Nullable Control controlFor(KeyEvent event) {
        // Resolve the chord first and use physical Ctrl, including on macOS (not Cmd).
        if (stepIntoKey.matches(event) && event.hasControlDown()) return Control.OUT;
        return resumeKey.matches(event) ? Control.RESUME
            : stepOverKey.matches(event) ? Control.OVER
            : stepIntoKey.matches(event) ? Control.INTO : null;
    }

    private void drainControlClicks() {
        while (resumeKey.consumeClick()) { }
        while (stepOverKey.consumeClick()) { }
        while (stepIntoKey.consumeClick()) { }
    }

    /** Keep the press-time modifier: releasing Ctrl before the next tick cannot turn Out into Into. */
    public boolean handleWorldControlKey(KeyEvent event, int keyAction) {
        Minecraft client = Minecraft.getInstance();
        if (keyAction == InputConstants.RELEASE || client.player == null || !state.isPaused()
            || client.gui.screen() != null || client.gui.overlay() != null || !client.isWindowActive()) return false;
        Control action = controlFor(event);
        if (action == null) return false;
        if (keyAction == InputConstants.PRESS) {
            drainControlClicks();
            control(action);
        }
        // Cancel native queueing and key repeats so a chord cannot also enqueue plain Step Into.
        return true;
    }

    /** Screens and world key presses resolve the same exclusive action. */
    public boolean handleScreenKey(KeyEvent event) {
        Control action = controlFor(event);
        if (action != null) {
            drainControlClicks();
            control(action);
            return true;
        }
        if (keepFreecamKey.matches(event)) {
            while (keepFreecamKey.consumeClick()) { }
            toggleKeepFreecam();
            return true;
        }
        if (breakpointKey.matches(event)) {
            while (breakpointKey.consumeClick()) { }
            toggleTargetBreakpoint();
            return true;
        }
        return false;
    }

    private void toggleTargetBreakpoint() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        var viewpoint = client.getCameraEntity() != null ? client.getCameraEntity() : client.player;
        HitResult hit = viewpoint.pick(20.0, 1.0F, false);
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = block.getBlockPos();
            client.player.connection.sendCommand("codon breakpoint block %d %d %d".formatted(pos.getX(), pos.getY(), pos.getZ()));
        } else {
            client.player.sendOverlayMessage(Component.translatable("codon.breakpoint.no_block_target"));
        }
    }

    public void registerKeyMappings() {
        KeyMapping.Category category = new KeyMapping.Category(CATEGORY_ID);
        this.keepFreecamKey = register("key.codon.keep_freecam", InputConstants.KEY_G, category);
        this.hideUiKey = register("key.codon.hide_ui", InputConstants.KEY_H, category);
        this.menuKey = register("key.codon.open_menu", InputConstants.KEY_V, category);
        this.breakpointKey = register("key.codon.breakpoint", InputConstants.KEY_F10, category);
        this.resumeKey = register("key.codon.resume", InputConstants.KEY_F9, category);
        this.stepOverKey = register("key.codon.step_over", InputConstants.KEY_F8, category);
        this.stepIntoKey = register("key.codon.step_into", InputConstants.KEY_F7, category);
    }

    private static KeyMapping register(String translationKey, int key, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(translationKey, InputConstants.Type.KEYBOARD, key, category));
    }

    @FunctionalInterface
    public interface OpenScreen {
        void open(InputManager inputManager);
    }
}
