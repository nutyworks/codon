package works.nuty.codon.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.CodonMod;
import works.nuty.codon.client.camera.DebuggerFreecam;
import works.nuty.codon.client.config.ClientSettingsStore;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.network.ClientNetworking;
import works.nuty.codon.client.network.ClientSourceBrowseNetworking;
import works.nuty.codon.client.render.DebugHudElement;
import works.nuty.codon.client.render.DebugLevelRenderer;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientFunctionSourceState;
import works.nuty.codon.client.state.DebuggerPreferences;
import works.nuty.codon.client.state.ClientPauseEffects;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.client.ui.ScreenLayers;

/**
 * Client composition root. Builds the synced {@link ClientDebuggerState} and wires the client
 * adapters (network receivers, windows, HUD, in-world renderer, keybinds) to it.
 */
public final class CodonClientMod implements ClientModInitializer {
    private static @Nullable ClientDebuggerState debuggerState;
    private static @Nullable DebuggerFreecam freecam;
    private static volatile @Nullable ClientPauseEffects pauseEffects;
    private static @Nullable ClientFunctionSourceState sourceState;

    /** Client composition seams, including the service used by framework-created mixins. */
    public static @Nullable ClientDebuggerState state() { return debuggerState; }
    public static @Nullable DebuggerFreecam freecam() { return freecam; }
    public static @Nullable ClientPauseEffects pauseEffects() { return pauseEffects; }
    public static @Nullable ClientFunctionSourceState sources() { return sourceState; }

    public static boolean isAudioPaused() {
        return pauseEffects != null && pauseEffects.isPaused();
    }

    public static boolean isWorldPaused() {
        return debuggerState != null && debuggerState.isPaused() && Minecraft.getInstance().level != null;
    }

    public static long effectTimeMillis(long realTimeMillis) {
        return pauseEffects == null ? realTimeMillis : pauseEffects.effectTimeMillis(realTimeMillis);
    }

    @Override
    public void onInitializeClient() {
        ScreenLayers.register();
        DebuggerPreferences preferences = ClientSettingsStore.open(
            FabricLoader.getInstance().getConfigDir().resolve("codon.json"),
            exception -> CodonMod.LOGGER.error("Could not load or save Codon client settings", exception));
        works.nuty.codon.client.ui.DebuggerTheme.usePreferences(preferences);
        ClientDebuggerState state = new ClientDebuggerState(preferences);
        debuggerState = state;
        DebuggerFreecam camera = new DebuggerFreecam(state);
        freecam = camera;
        ClientPauseEffects effects = new ClientPauseEffects(state);
        pauseEffects = effects;
        DebuggerOverlay overlay = new DebuggerOverlay(state);

        InputManager inputManager = new InputManager(state,
            im -> Minecraft.getInstance().gui.setScreen(new CodonScreen(im, overlay)));
        inputManager.registerKeyMappings();

        ClientNetworking.register(state, camera, effects);
        sourceState = ClientSourceBrowseNetworking.register();
        ClientTickEvents.START_CLIENT_TICK.register(camera::tick);
        ClientTickEvents.END_CLIENT_TICK.register(inputManager);
        LevelRenderEvents.END_MAIN.register(new DebugLevelRenderer(state, inputManager));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("codon", "debug_overlay"), new DebugHudElement(overlay, inputManager));
    }
}
