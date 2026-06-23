package works.nuty.bastion.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;
import works.nuty.bastion.client.state.ClientDebuggerState;

/**
 * Client keybinds for the debugger. Control actions (resume/step) are issued as {@code /bastion}
 * commands — the server is the source of truth — and gated on the synced pause state. Opening the
 * window editor is purely client-side.
 */
public final class InputManager implements ClientTickEvents.EndTick {
    public static final Identifier CATEGORY_ID = Identifier.fromNamespaceAndPath("bastion", "debugger");

    private final ClientDebuggerState state;
    private final OpenScreen openScreen;

    public KeyMapping menuKey;
    public KeyMapping breakpointKey;
    public KeyMapping resumeKey;
    public KeyMapping stepOverKey;
    public KeyMapping stepIntoKey;

    public InputManager(ClientDebuggerState state, OpenScreen openScreen) {
        this.state = state;
        this.openScreen = openScreen;
    }

    @Override
    public void onEndTick(Minecraft client) {
        if (client.player == null) {
            return;
        }

        if (state.isPaused()) {
            while (resumeKey.consumeClick()) {
                client.player.connection.sendCommand("bastion resume");
            }
            while (stepOverKey.consumeClick()) {
                client.player.connection.sendCommand("bastion stepover");
            }
            while (stepIntoKey.consumeClick()) {
                client.player.connection.sendCommand(client.hasShiftDown() ? "bastion stepout" : "bastion stepinto");
            }
        } else {
            // Drain clicks so they don't fire later when paused.
            resumeKey.consumeClick();
            stepOverKey.consumeClick();
            stepIntoKey.consumeClick();
        }

        while (breakpointKey.consumeClick()) {
            HitResult hit = client.player.pick(20.0, 0.0F, false);
            if (hit.getType() == HitResult.Type.BLOCK) {
                BlockPos pos = ((BlockHitResult) hit).getBlockPos();
                client.player.connection.sendCommand("bastion breakpoint block %d %d %d".formatted(pos.getX(), pos.getY(), pos.getZ()));
            }
        }

        while (menuKey.consumeClick()) {
            openScreen.open(this);
        }
    }

    public void registerKeyMappings() {
        KeyMapping.Category category = new KeyMapping.Category(CATEGORY_ID);
        this.menuKey = register("key.bastion.open_menu", GLFW.GLFW_KEY_B, category);
        this.breakpointKey = register("key.bastion.breakpoint", GLFW.GLFW_KEY_F10, category);
        this.resumeKey = register("key.bastion.resume", GLFW.GLFW_KEY_F7, category);
        this.stepOverKey = register("key.bastion.step_over", GLFW.GLFW_KEY_F8, category);
        this.stepIntoKey = register("key.bastion.step_into", GLFW.GLFW_KEY_F9, category);
    }

    private static KeyMapping register(String translationKey, int key, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(translationKey, InputConstants.Type.KEYSYM, key, category));
    }

    @FunctionalInterface
    public interface OpenScreen {
        void open(InputManager inputManager);
    }
}
