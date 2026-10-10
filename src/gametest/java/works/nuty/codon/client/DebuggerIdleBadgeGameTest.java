package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import works.nuty.codon.client.config.ClientSettingsStore;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.render.DebugHudElement;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.DebuggerPreferences;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.client.ui.DebuggerTheme;
import works.nuty.codon.client.ui.ScaledCodonScreen;
import works.nuty.codon.client.ui.layout.DebuggerLayout;
import works.nuty.codon.core.model.PauseSnapshot;

/**
 * Native HUD, cursor-mode and View-menu check of the saved idle-badge preference in the smallest
 * supported viewport, using a temporary settings file and a test-owned state. The production HUD
 * element would draw its own idle badge at the same place, so it is replaced once, in its own
 * id and order, by a wrapper that draws the active test fixture and otherwise delegates to the
 * original production element. Fabric's HUD registry keeps removed ids and rejects adding them
 * again, so the test never removes or re-adds an element: the wrapper stays registered after
 * cleanup, but then only renders the original production element. The paused phases inject a
 * client pause snapshot: they do not exercise a server breakpoint, and world markers are covered
 * by {@link DebuggerWorldMarkerVisibilityGameTest}.
 */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerIdleBadgeGameTest implements FabricClientGameTest {
    private static final Identifier PRODUCTION_HUD = Identifier.fromNamespaceAndPath("codon", "debug_overlay");
    private static final String SAVED_HIDDEN = "\"idleBadgeVisible\": false";
    private static final String SAVED_SHOWN = "\"idleBadgeVisible\": true";

    private record Fixture(DebuggerPreferences preferences, ClientDebuggerState state, InputManager input,
                           DebuggerOverlay overlay, DebugHudElement hud) { }

    @Override public void runTest(ClientGameTestContext context) {
        int[] oldWindowSize = context.computeOnClient(client ->
            new int[]{client.getWindow().getScreenWidth(), client.getWindow().getScreenHeight()});
        int oldScale = context.computeOnClient(client -> client.options.guiScale().get());
        String oldLanguage = context.computeOnClient(client -> client.getLanguageManager().getSelected());
        boolean oldDebug = context.computeOnClient(client -> client.debugEntries.isOverlayVisible());
        Path directory;
        try { directory = Files.createTempDirectory("codon-idle-badge"); }
        catch (IOException exception) { throw new UncheckedIOException(exception); }
        Path file = directory.resolve("codon.json");
        Fixture[] activeFixture = new Fixture[1];
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            try {
                check(context, world, file, activeFixture);
            } finally {
                context.runOnClient(client -> {
                    // First, so the wrapper renders the original production element again even if a later step throws.
                    activeFixture[0] = null;
                    client.setScreenAndShow(null);
                    client.debugEntries.setOverlayVisible(oldDebug);
                    client.options.guiScale().set(oldScale);
                    client.resizeGui();
                    DebuggerTheme.usePreferences(CodonClientMod.state().preferences());
                });
            }
        } finally {
            // Fabric's resizeWindow writes the game window and framebuffer dimensions together.
            context.getInput().resizeWindow(oldWindowSize[0], oldWindowSize[1]);
            language(context, oldLanguage);
            try {
                Files.deleteIfExists(file);
                Files.deleteIfExists(directory);
            } catch (IOException exception) { throw new UncheckedIOException(exception); }
        }
    }

    private static void check(ClientGameTestContext context, TestSingleplayerContext world, Path file,
                              Fixture[] activeFixture) {
        context.getInput().resizeWindow(640, 480);
        context.runOnClient(client -> {
            // The smallest viewport Codon supports (320x240 GUI pixels), whatever the display density.
            client.options.guiScale().set(Math.max(1, client.getWindow().getWidth() / 320));
            client.debugEntries.setOverlayVisible(false);
            client.gui.hud.getChat().clearMessages(false);
            client.resizeGui();
        });
        world.getConnection().waitForChunksRender();
        world.getServer().runCommand("time set noon");
        world.getServer().runCommand("weather clear");
        context.getInput().lookAt(0, -55);
        context.runOnClient(client -> {
            var window = client.getWindow();
            require(window.getGuiScaledWidth() >= 320 && window.getGuiScaledWidth() < 420
                && window.getGuiScaledHeight() >= 240 && window.getGuiScaledHeight() < 300,
                "Compact viewport: " + window.getGuiScaledWidth() + "x" + window.getGuiScaledHeight());
        });

        Fixture first = context.computeOnClient(client -> {
            // Composed lazily every frame: `production` is the real prior element, and no id is ever removed or re-added.
            HudElementRegistry.replaceElement(PRODUCTION_HUD, production -> (graphics, tracker) ->
                (activeFixture[0] == null ? production : activeFixture[0].hud()).extractRenderState(graphics, tracker));
            return install(client, ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); }), activeFixture);
        });
        context.waitTicks(3);
        context.runOnClient(client -> require(client.gui.screen() == null && !first.state().isPaused()
            && first.preferences().idleBadgeVisible(), "Fresh settings run with no screen and nothing paused"));
        badge(context, "codon-idle-badge-visible-default", true, "Default idle badge stays visible");

        // Hide through the real View menu, in cursor mode while nothing is paused.
        context.runOnClient(client -> client.setScreenAndShow(new CodonScreen(first.input(), first.overlay())));
        context.waitTicks(3);
        click(context, "codon.ui.view");
        context.runOnClient(client -> {
            require(selected(client, "codon.ui.idle_badge"), "View marks the visible idle badge");
            requireReachable(client, "codon.ui.idle_badge");
            requireReachable(client, "codon.ui.scale.title");
        });
        click(context, "codon.ui.idle_badge");
        context.runOnClient(client -> require(!first.preferences().idleBadgeVisible(),
            "The View row clears the idle-badge preference"));
        require(read(file).contains(SAVED_HIDDEN), "The change listener saved the hidden preference");
        click(context, "codon.ui.view");
        context.runOnClient(client -> require(!selected(client, "codon.ui.idle_badge"), "View no longer marks the hidden badge"));
        context.takeScreenshot("codon-idle-badge-view-menu-hidden-en_us");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitTicks(2);
        context.runOnClient(client -> require(client.gui.screen() instanceof CodonScreen && !first.preferences().idleBadgeVisible(),
            "Escape closes only the menu; the preference is unchanged"));
        badge(context, "codon-idle-badge-cursor-idle-hidden-pref", true, "Cursor mode keeps its header while the badge is hidden");
        context.runOnClient(client -> client.setScreenAndShow(null));
        context.waitTicks(3);
        badge(context, "codon-idle-badge-hidden", false, "Hidden idle badge is not drawn");

        // State reset (what disconnect does) leaves the preference; a new settings instance reads the saved file.
        context.runOnClient(client -> {
            first.state().reset();
            require(!first.preferences().idleBadgeVisible(), "State reset retains the preference");
        });
        Fixture reloaded = context.computeOnClient(client ->
            install(client, ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); }), activeFixture));
        context.waitTicks(3);
        context.runOnClient(client -> require(!reloaded.preferences().idleBadgeVisible(), "The saved file restores the hidden preference"));
        badge(context, "codon-idle-badge-hidden-after-reload", false, "A new settings instance still hides the badge");

        // Injected client pause: the paused HUD and cursor mode ignore the idle-badge preference.
        PauseSnapshot[] paused = new PauseSnapshot[1];
        context.runOnClient(client -> {
            reloaded.state().applyPause(DebuggerPresentationGameTest.fixture(client));
            paused[0] = reloaded.state().snapshot();
        });
        context.waitTicks(3);
        context.runOnClient(client -> require(reloaded.state().isPaused() && !reloaded.preferences().idleBadgeVisible(),
            "Injected pause with the badge preference hidden"));
        badge(context, "codon-idle-badge-paused-hud-synthetic-pause", true, "Paused HUD ignores the idle-badge preference");
        context.runOnClient(client -> client.setScreenAndShow(new CodonScreen(reloaded.input(), reloaded.overlay())));
        context.waitTicks(3);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            require(screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
                .anyMatch(button -> button.visible && button.active && button.getMessage().getString()
                    .startsWith(Component.translatable("codon.ui.control.resume").getString())),
                "Cursor mode keeps its enabled Continue control");
            require(reloaded.state().snapshot() == paused[0], "Cursor mode retains the injected snapshot");
        });
        badge(context, "codon-idle-badge-cursor-paused-synthetic-pause", true, "Paused cursor mode ignores the idle-badge preference");
        context.runOnClient(client -> {
            client.setScreenAndShow(null);
            reloaded.state().applyResume();
        });
        context.waitTicks(3);
        badge(context, "codon-idle-badge-resumed-hidden", false, "Resumed idle HUD returns to the hidden preference");

        // Restore through the View menu, then F3 must still win over a visible preference.
        context.runOnClient(client -> client.setScreenAndShow(new CodonScreen(reloaded.input(), reloaded.overlay())));
        context.waitTicks(3);
        click(context, "codon.ui.view");
        click(context, "codon.ui.idle_badge");
        context.runOnClient(client -> {
            require(reloaded.preferences().idleBadgeVisible(), "The View row restores the idle-badge preference");
            client.setScreenAndShow(null);
        });
        require(read(file).contains(SAVED_SHOWN), "The restored preference was saved");
        context.waitTicks(3);
        badge(context, "codon-idle-badge-restored", true, "Restored idle badge is drawn again");
        context.runOnClient(client -> client.debugEntries.setOverlayVisible(true));
        context.waitTicks(3);
        context.runOnClient(client -> require(reloaded.preferences().idleBadgeVisible()
            && client.gui.hud.getDebugOverlay().showDebugScreen(), "F3 is shown while the preference says visible"));
        badge(context, "codon-idle-badge-f3-visible-pref", false, "F3 still suppresses a visible idle badge");
        context.runOnClient(client -> client.debugEntries.setOverlayVisible(false));

        // Korean has its own row label, and the menu remains clickable.
        language(context, "ko_kr");
        context.runOnClient(client -> client.setScreenAndShow(new CodonScreen(reloaded.input(), reloaded.overlay())));
        context.waitTicks(3);
        click(context, "codon.ui.view");
        context.runOnClient(client -> {
            String label = Component.translatable("codon.ui.idle_badge").getString();
            require(!label.equals("codon.ui.idle_badge") && !label.equals("Idle badge"), "Korean idle-badge label: " + label);
            require(selected(client, "codon.ui.idle_badge"), "Korean View marks the visible idle badge");
            requireReachable(client, "codon.ui.idle_badge");
            requireReachable(client, "codon.ui.scale.title");
        });
        context.takeScreenshot("codon-idle-badge-view-menu-visible-ko_kr");
        click(context, "codon.ui.idle_badge");
        context.runOnClient(client -> require(!reloaded.preferences().idleBadgeVisible(), "Korean row hides the badge preference"));
        require(read(file).contains(SAVED_HIDDEN), "The Korean toggle was saved");
    }

    private static Fixture install(Minecraft client, DebuggerPreferences preferences, Fixture[] activeFixture) {
        var state = new ClientDebuggerState(preferences);
        var input = DebuggerPresentationGameTest.input(client, state);
        var overlay = new DebuggerOverlay(state);
        DebuggerTheme.usePreferences(preferences);
        var fixture = new Fixture(preferences, state, input, overlay, new DebugHudElement(overlay, input));
        activeFixture[0] = fixture;
        return fixture;
    }

    /**
     * Both the idle badge and the paused/cursor header start with an opaque dark panel at the same
     * corner. A thin strip left of the text is therefore dark when either is drawn and bright sky
     * (or F3's translucent text strips) when not.
     */
    private static void badge(ClientGameTestContext context, String name, boolean drawn, String message) {
        int[] box = context.computeOnClient(client -> {
            var window = client.getWindow();
            var header = DebuggerLayout.create(window.getGuiScaledWidth(), window.getGuiScaledHeight(), false).header();
            return new int[]{header.x() + 1, header.y() + 1, header.x() + 6, header.y() + header.height() - 1,
                window.getGuiScaledWidth()};
        });
        BufferedImage image;
        try { image = ImageIO.read(context.takeScreenshot(name).toFile()); }
        catch (IOException exception) { throw new UncheckedIOException(exception); }
        double scale = image.getWidth() / (double) box[4];
        int dark = 0, total = 0;
        for (int y = (int) Math.ceil(box[1] * scale); y < (int) (box[3] * scale); y++) {
            for (int x = (int) Math.ceil(box[0] * scale); x < (int) (box[2] * scale); x++) {
                int rgb = image.getRGB(x, y);
                total++;
                if (Math.max(rgb >> 16 & 0xFF, Math.max(rgb >> 8 & 0xFF, rgb & 0xFF)) < 80) dark++;
            }
        }
        require(total > 0, "Sampled panel pixels in " + name);
        double fraction = dark / (double) total;
        require(drawn ? fraction >= 0.9 : fraction <= 0.5, message + " (" + name + ", dark fraction " + fraction + ")");
    }

    private static DebuggerButton button(Screen screen, String key) {
        String label = Component.translatable(key).getString();
        var buttons = screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> button.visible).toList();
        return buttons.stream().filter(button -> button.getMessage().getString().equals(label)).findFirst()
            .or(() -> buttons.stream().filter(button -> button.getMessage().getString().endsWith(" " + label)).findFirst())
            .orElseThrow(() -> new AssertionError("Visible control: " + key));
    }

    private static boolean selected(Minecraft client, String key) {
        return button(client.gui.screen(), key).getMessage().getString().startsWith("●");
    }

    private static void requireReachable(Minecraft client, String key) {
        Screen screen = client.gui.screen();
        DebuggerButton row = button(screen, key);
        require(row.getX() >= 0 && row.getY() >= 0 && row.getRight() <= screen.width && row.getBottom() <= screen.height,
            "View menu row stays inside the compact viewport: " + key);
    }

    private static void click(ClientGameTestContext context, String key) {
        double[] point = context.computeOnClient(client -> {
            Screen screen = client.gui.screen();
            DebuggerButton widget = button(screen, key);
            var scale = ((ScaledCodonScreen) screen).uiScale();
            var window = client.getWindow();
            return new double[]{scale.toGame(widget.getX() + widget.getWidth() / 2.0)
                * window.getScreenWidth() / window.getGuiScaledWidth(),
                scale.toGame(widget.getY() + widget.getHeight() / 2.0)
                * window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(point[0], point[1]);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(3);
    }

    private static String read(Path file) {
        try { return Files.readString(file); }
        catch (IOException exception) { throw new UncheckedIOException(exception); }
    }

    private static void language(ClientGameTestContext context, String language) {
        if (context.computeOnClient(client -> client.getLanguageManager().getSelected()).equals(language)) return;
        var reload = context.computeOnClient(client -> {
            client.getLanguageManager().setSelected(language);
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 200);
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
