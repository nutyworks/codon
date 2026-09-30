package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.lang.reflect.Field;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.InputType;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.render.DebugHudElement;
import works.nuty.codon.client.state.*;
import works.nuty.codon.client.ui.*;
import works.nuty.codon.core.model.*;

/** Native pointer/keyboard and renderer regression with a synthetic pause, not server stepping. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerUiScaleGameTest implements FabricClientGameTest {
    private static final Identifier HUD = Identifier.fromNamespaceAndPath("codon", "ui_scale_test");

    @Override public void runTest(ClientGameTestContext context) {
        int oldGuiScale = context.computeOnClient(client -> client.options.guiScale().get());
        String oldLanguage = context.computeOnClient(client -> client.getLanguageManager().getSelected());
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1280, 800);
            setGameScale(context, 3);
            world.getConnection().waitForChunksRender();
            ClientDebuggerState state = new ClientDebuggerState();
            Fixture fixture = context.computeOnClient(client -> {
                state.applyPause(DebuggerPresentationGameTest.fixture(client));
                state.watches().add(new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:scale", "long.path"));
                InputManager input = DebuggerPresentationGameTest.input(client, state);
                var overlay = new DebuggerOverlay(state);
                var screen = new CodonScreen(input, overlay);
                HudElementRegistry.addLast(HUD, new DebugHudElement(overlay, input));
                client.setScreenAndShow(screen);
                return new Fixture(state, input, overlay, screen);
            });
            context.waitTicks(3);
            context.runOnClient(client -> require(fixture.screen().width == client.getWindow().getGuiScaledWidth(), "Default uses game GUI dimensions"));
            context.takeScreenshot("codon-scale-follow-game-3");
            click(context, fixture.screen(), "View");
            click(context, fixture.screen(), "Codon UI scale");
            UiScaleScreen settings = context.computeOnClient(client -> (UiScaleScreen) client.gui.screen());
            click(context, settings, "Custom scale");
            context.runOnClient(client -> {
                require(state.preferences().uiScaleMode() == DebuggerPreferences.UiScaleMode.CUSTOM, "Native click selects custom mode");
                require(client.options.guiScale().get() == 3, "Custom mode does not change game GUI option");
                require(state.preferences().customUiScale() == client.getWindow().getGuiScale() * 4,
                    "First custom selection starts from the actual applied manual game scale");
                require(settings.width == client.getWindow().getGuiScaledWidth() && settings.height == client.getWindow().getGuiScaledHeight(),
                    "First custom selection retains exact game GUI dimensions");
            });
            context.takeScreenshot("codon-scale-first-custom-manual-3");
            click(context, settings, "+");
            context.runOnClient(client -> require(state.preferences().customUiScale() == 13, "Native plus increments by 0.25x"));
            context.takeScreenshot("codon-scale-settings-custom-3_25");
            context.runOnClient(client -> {
                client.setLastInputType(InputType.KEYBOARD_TAB);
                settings.setFocused(button(settings, "+"));
            });
            context.getInput().pressKey(InputConstants.KEY_RETURN);
            context.waitTicks(2);
            context.runOnClient(client -> {
                require(state.preferences().customUiScale() == 14, "Keyboard activation adjusts the same scale");
                require(settings.getFocused() == button(settings, "+"), "Scale relayout retains keyboard focus");
            });
            click(context, settings, "−");
            click(context, settings, "Follow game GUI scale");
            setGameScale(context, 2);
            click(context, settings, "Custom scale");
            context.runOnClient(client -> require(state.preferences().customUiScale() == 13, "Mode round trip preserves the user request"));
            click(context, settings, "Restore defaults");
            setGameScale(context, 0);
            context.takeScreenshot("codon-scale-follow-auto-before-first-custom");
            click(context, settings, "Custom scale");
            context.runOnClient(client -> {
                require(client.options.guiScale().get() == 0 && client.getWindow().getGuiScale() > 1, "Game Auto is applied rather than the option value zero");
                require(state.preferences().customUiScale() == client.getWindow().getGuiScale() * 4,
                    "Reset clears initialization and first custom captures the actual Auto scale");
                require(settings.width == client.getWindow().getGuiScaledWidth() && settings.height == client.getWindow().getGuiScaledHeight(),
                    "Auto mode switching does not move or shrink the UI");
            });
            context.takeScreenshot("codon-scale-first-custom-auto");
            setGameScale(context, 3);
            context.runOnClient(client -> state.preferences().setCustomUiScale(9));
            context.waitTicks(3);
            context.takeScreenshot("codon-scale-settings-custom-2_25");
            click(context, settings, "Done");
            context.waitTicks(2);
            int customWidth = context.computeOnClient(client -> fixture.screen().width);
            context.takeScreenshot("codon-scale-cursor-custom-2_25-game-3");
            setGameScale(context, 2);
            context.waitTicks(3);
            context.runOnClient(client -> require(fixture.screen().width == customWidth, "Custom UI size survives game GUI-scale change"));
            context.takeScreenshot("codon-scale-cursor-custom-2_25-game-2");
            hover(context, fixture.screen(), buttonPosition(context, fixture.screen(), "Information"));
            context.waitTicks(12);
            context.takeScreenshot("codon-scale-tooltip-custom-2_25");
            checkHelpScroll(context, fixture);
            checkScreens(context, fixture);
            checkVanillaLayer(context, fixture);

            context.runOnClient(client -> {
                state.preferences().setCustomUiScale(16);
                client.setScreenAndShow(new UiScaleScreen(fixture.screen(), state.preferences()));
            });
            context.getInput().resizeWindow(640, 480);
            context.waitTicks(4);
            context.runOnClient(client -> {
                var screen = (ScaledCodonScreen) client.gui.screen();
                require(screen.uiScale().effective() < 4, "Small viewport limits the applied scale");
                require(state.preferences().customUiScale() == 16, "Small viewport retains the requested scale");
                require(screen.width >= 320 && screen.height >= 240, "Limited scale keeps settings reachable");
            });
            context.takeScreenshot("codon-scale-small-settings-limited");
            click(context, context.computeOnClient(client -> client.gui.screen()), "Done");
            context.takeScreenshot("codon-scale-small-overlay-limited");
            context.runOnClient(client -> state.preferences().setCustomUiScale(6));
            context.waitTicks(3);
            click(context, fixture.screen(), "View");
            context.takeScreenshot("codon-scale-small-view-menu-1_50");
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitTicks(2);
            context.takeScreenshot("codon-scale-small-overlay-1_50");
            click(context, fixture.screen(), "View");
            click(context, fixture.screen(), "Watches");
            context.takeScreenshot("codon-scale-small-watches-1_50");
            context.runOnClient(client -> client.setScreenAndShow(null));
            context.waitTicks(3);
            context.takeScreenshot("codon-scale-small-passive-1_50");
            context.getInput().resizeWindow(320, 240);
            context.runOnClient(client -> state.preferences().setCustomUiScale(4));
            context.waitTicks(3);
            context.takeScreenshot("codon-scale-smallest-passive-1_00");
            context.getInput().resizeWindow(1920, 1080);
            context.runOnClient(client -> {
                state.preferences().setCustomUiScale(16);
                client.setScreenAndShow(new UiScaleScreen(fixture.screen(), state.preferences()));
            });
            context.waitTicks(3);
            context.runOnClient(client -> require(((ScaledCodonScreen) client.gui.screen()).uiScale().effective() == 4, "Larger viewport restores requested scale"));
            click(context, context.computeOnClient(client -> client.gui.screen()), "Restore defaults");
            context.waitTicks(3);
            context.runOnClient(client -> {
                require(state.preferences().uiScaleMode() == DebuggerPreferences.UiScaleMode.FOLLOW_GAME, "Defaults restore follow mode");
                require(state.preferences().customUiScale() == 8, "Defaults restore custom request");
                require(!state.preferences().customUiScaleInitialized(), "Defaults clear first-use initialization");
                require(client.options.guiScale().get() == 2, "Reset leaves game GUI setting alone");
            });
            context.takeScreenshot("codon-scale-settings-defaults-restored");
            context.getInput().resizeWindow(640, 480);
            var korean = context.computeOnClient(client -> {
                client.getLanguageManager().setSelected("ko_kr");
                return client.reloadResourcePacks();
            });
            context.waitFor(client -> korean.isDone() && client.gui.overlay() == null, 200);
            context.runOnClient(client -> {
                state.preferences().setCustomUiScale(16);
                state.preferences().setUiScaleMode(DebuggerPreferences.UiScaleMode.CUSTOM);
                client.setScreenAndShow(new UiScaleScreen(fixture.screen(), state.preferences()));
            });
            context.waitTicks(3);
            context.takeScreenshot("codon-scale-settings-korean-limited");
            click(context, context.computeOnClient(client -> client.gui.screen()), "기본값 복원");
            context.runOnClient(client -> require(state.preferences().uiScaleMode() == DebuggerPreferences.UiScaleMode.FOLLOW_GAME,
                "Localized native reset restores defaults"));
            context.takeScreenshot("codon-scale-settings-korean-follow");
        } finally {
            context.runOnClient(client -> {
                ScreenLayers.close(ScreenLayers.get(client.gui.screen()));
                client.setScreenAndShow(null);
                HudElementRegistry.removeElement(HUD);
                client.options.guiScale().set(oldGuiScale);
                client.resizeGui();
            });
            if (!context.computeOnClient(client -> client.getLanguageManager().getSelected()).equals(oldLanguage)) {
                var restored = context.computeOnClient(client -> {
                    client.getLanguageManager().setSelected(oldLanguage);
                    return client.reloadResourcePacks();
                });
                context.waitFor(client -> restored.isDone() && client.gui.overlay() == null, 200);
            }
        }
    }

    private static void checkHelpScroll(ClientGameTestContext context, Fixture fixture) {
        var help = context.computeOnClient(client -> {
            var screen = new DebuggerHelpScreen(fixture.screen(), fixture.input());
            client.setScreenAndShow(screen);
            return screen;
        });
        context.waitTicks(3);
        click(context, help, "Controls");
        hover(context, help, new double[]{help.width / 2.0, help.height / 2.0});
        context.getInput().scroll(-1);
        context.waitTicks(2);
        context.runOnClient(client -> require(integer(help, "offset") > 0, "Native wheel scrolls scaled help"));
        context.takeScreenshot("codon-scale-help-scroll-2_25");
        double[] track = context.computeOnClient(client -> new double[]{integer(help, "left") + integer(help, "panelWidth") - 4,
            integer(help, "top") + 55});
        hover(context, help, new double[]{track[0], track[1] + 8});
        context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
        hover(context, help, new double[]{track[0], track[1] + 90});
        context.waitTicks(2);
        context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.runOnClient(client -> require(integer(help, "offset") > 3, "Native drag scrolls scaled track"));
        context.takeScreenshot("codon-scale-help-drag-2_25");
        click(context, help, "Done");
    }

    private static void checkScreens(ClientGameTestContext context, Fixture f) {
        long id = f.state().watches().addOrFind(new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:scale", "long.path"));
        List<ScaledCodonScreen> screens = context.computeOnClient(client -> List.of(
            new WatchScreen(f.input(), f.state(), f.overlay()),
            new WatchDetailsScreen(f.input(), f.state(), f.overlay(), id),
            new WatchPickerScreen(f.screen(), f.state(), new WatchEditorQuery(WatchEditorQuery.Mode.STORAGES,
                WatchSpec.Kind.STORAGE_NBT, "", "", null, "", 0), ignored -> { }),
            new WatchGroupingScreen(f.screen(), WatchGrouping.Mode.NONE, ignored -> { }),
            new BreakpointListScreen(f.screen(), f.state()),
            new FunctionSourceScreen(f.screen(), new ClientFunctionSourceState())));
        for (ScaledCodonScreen screen : screens) {
            context.runOnClient(client -> client.setScreenAndShow(screen));
            context.waitTicks(3);
            context.runOnClient(client -> require(screen.width == f.screen().width && screen.height == f.screen().height,
                "Every Codon screen shares logical scale: " + screen.getClass().getSimpleName()));
            if (screen instanceof WatchScreen) {
                EditBox field = context.computeOnClient(client -> screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow());
                hover(context, screen, new double[]{field.getX() + 8, field.getY() + 8});
                context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
                context.runOnClient(client -> require(field.isFocused(), "Native pointer focuses scaled text field"));
                context.getInput().typeChars("scaled_focus");
                context.getInput().resizeWindow(1200, 800);
                context.waitTicks(3);
                context.runOnClient(client -> require(screen.getFocused() instanceof EditBox resized
                    && resized.getValue().equals("scaled_focus"), "Text and field focus survive a window resize"));
                setGameScale(context, 3);
                context.waitTicks(3);
                context.runOnClient(client -> require(screen.getFocused() instanceof EditBox resized
                    && resized.getValue().equals("scaled_focus"), "Text and field focus survive a game GUI-scale change"));
                setGameScale(context, 2);
                context.getInput().resizeWindow(1280, 800);
                context.waitTicks(3);
            }
            context.takeScreenshot("codon-scale-screen-" + screen.getClass().getSimpleName());
            click(context, screen, "Close");
            context.runOnClient(client -> require(client.gui.screen() instanceof CodonScreen, "Native Close hitbox returns from " + screen.getClass().getSimpleName()));
        }
    }

    private static void checkVanillaLayer(ClientGameTestContext context, Fixture fixture) {
        Screen parent = context.computeOnClient(client -> {
            Screen screen = new Screen(Component.literal("Vanilla-size parent")) { };
            client.setScreenAndShow(screen);
            var target = BreakpointTarget.whole(fixture.state().snapshot().location());
            ScreenLayers.open(screen, new BreakpointConditionScreen(screen, fixture.state(), BreakpointDefinition.plain(target),
                new BreakpointConditionScreen.Anchor(150, 120, 20, 20)));
            return screen;
        });
        context.waitTicks(3);
        var layer = context.computeOnClient(client -> (ScaledCodonScreen) ScreenLayers.get(parent));
        context.runOnClient(client -> require(parent.width == client.getWindow().getGuiScaledWidth() && layer.width == fixture.screen().width,
            "Vanilla parent keeps game dimensions while modal uses Codon dimensions"));
        click(context, layer, Component.translatable("codon.breakpoint.condition_select", BreakpointUi.kindLabel(BreakpointCondition.Kind.ALWAYS)).getString());
        context.takeScreenshot("codon-scale-condition-layer-menu-2_25");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitTicks(2);
        click(context, layer, "Cancel");
        context.runOnClient(client -> {
            require(ScreenLayers.get(parent) == null && client.gui.screen() == parent, "Native modal click closes only the layer");
            client.setScreenAndShow(fixture.screen());
        });
    }

    private static void setGameScale(ClientGameTestContext context, int value) {
        context.runOnClient(client -> { client.options.guiScale().set(value); client.resizeGui(); });
    }

    private static DebuggerButton button(Screen screen, String label) {
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> button.visible && button.getMessage().getString().replace("● ", "").strip().equals(label)).findFirst()
            .orElseThrow(() -> new AssertionError("Missing " + label + " in " + screen.getClass().getSimpleName()));
    }
    private static double[] buttonPosition(ClientGameTestContext context, Screen screen, String label) {
        return context.computeOnClient(client -> { var button = button(screen, label); return new double[]{button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0}; });
    }
    private static void click(ClientGameTestContext context, Screen screen, String label) {
        hover(context, screen, buttonPosition(context, screen, label));
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(3);
    }
    private static void hover(ClientGameTestContext context, Screen screen, double[] local) {
        double[] position = context.computeOnClient(client -> {
            var window = client.getWindow();
            var scale = ((ScaledCodonScreen) screen).uiScale();
            return new double[]{scale.toGame(local[0]) * window.getScreenWidth() / window.getGuiScaledWidth(),
                scale.toGame(local[1]) * window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(position[0], position[1]);
    }
    private static int integer(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.getInt(object); }
        catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private record Fixture(ClientDebuggerState state, InputManager input, DebuggerOverlay overlay, CodonScreen screen) { }
}
