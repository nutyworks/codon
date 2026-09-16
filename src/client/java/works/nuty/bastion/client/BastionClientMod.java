package works.nuty.bastion.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import works.nuty.bastion.client.input.InputManager;
import works.nuty.bastion.client.network.ClientNetworking;
import works.nuty.bastion.client.render.DebugHudElement;
import works.nuty.bastion.client.render.DebugLevelRenderer;
import works.nuty.bastion.client.state.ClientDebuggerState;
import works.nuty.bastion.client.ui.BastionScreen;
import works.nuty.bastion.client.ui.DebuggerOverlay;

/**
 * Client composition root. Builds the synced {@link ClientDebuggerState} and wires the client
 * adapters (network receivers, windows, HUD, in-world renderer, keybinds) to it.
 */
public final class BastionClientMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientDebuggerState state = new ClientDebuggerState();
        DebuggerOverlay overlay = new DebuggerOverlay(state);

        InputManager inputManager = new InputManager(state,
            im -> Minecraft.getInstance().setScreenAndShow(new BastionScreen(im, overlay)));
        inputManager.registerKeyMappings();

        ClientNetworking.register(state);
        ClientTickEvents.END_CLIENT_TICK.register(inputManager);
        LevelRenderEvents.END_MAIN.register(new DebugLevelRenderer(state));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("bastion", "debug_overlay"), new DebugHudElement(overlay, inputManager));
    }
}
