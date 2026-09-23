package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.InputType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.resources.Identifier;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.render.DebugHudElement;
import works.nuty.codon.client.render.DebugLevelRenderer;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.core.model.PauseSnapshot;

/**
 * Held peek-key regression coverage with a synthetic client pause. It verifies rendering and
 * screen input only; it is not an acceptance test for a server breakpoint or OS focus loss.
 */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerPeekUiGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1280, 800);
            world.getConnection().waitForChunksRender();
            Fixture fixture = context.computeOnClient(DebuggerPeekUiGameTest::prepare);
            try {
                context.waitTicks(3);
                context.takeScreenshot("codon-peek-passive-before-hold");
                context.getInput().holdKey(options -> fixture.input().hideUiKey);
                context.waitTicks(2);
                context.runOnClient(client -> require(fixture.input().isUiHidden(), "world key press hides the debugger"));
                context.takeScreenshot("codon-peek-passive-held");
                context.getInput().releaseKey(options -> fixture.input().hideUiKey);
                context.runOnClient(client -> require(!fixture.input().isUiHidden(), "world key release restores the debugger"));
                context.runOnClient(client -> client.setScreenAndShow(fixture.screen()));
                context.waitTicks(3);
                context.takeScreenshot("codon-peek-cursor-before-hold");

                checkKeyboardHoldAndBlockedInput(context, fixture);
                context.waitTicks(2);
                context.takeScreenshot("codon-peek-cursor-held");

                context.getInput().releaseKey(options -> fixture.input().hideUiKey);
                context.runOnClient(client -> {
                    require(!fixture.input().isUiHidden(), "releasing H restores debugger presentation immediately");
                    preserved(fixture, "keyboard release");
                });
                context.waitTicks(2);
                context.takeScreenshot("codon-peek-cursor-released");

                checkMouseRebind(context, fixture);
                context.getInput().holdKey(options -> fixture.input().hideUiKey);
                context.getInput().pressKey(options -> fixture.input().menuKey);
                context.runOnClient(client -> {
                    require(client.gui.screen() == null, "menu changes from cursor mode to world mode while held");
                    require(fixture.input().isUiHidden(), "screen close preserves a still-held peek key");
                    preserved(fixture, "menu close while held");
                });
                context.waitTicks(2);
                context.takeScreenshot("codon-peek-world-held");
                context.getInput().releaseKey(options -> fixture.input().hideUiKey);
                context.runOnClient(client -> {
                    require(!InputConstants.isKeyDown(InputConstants.KEY_H), "world release lifts the physical H key");
                    // A screen transition can leave this mapping down after the physical key is released.
                    fixture.input().hideUiKey.setDown(true);
                    require(!fixture.input().isUiHidden(), "world release restores the passive debugger HUD");
                    preserved(fixture, "world release");
                });
                context.waitTicks(2);
                context.takeScreenshot("codon-peek-passive-released");
            } finally {
                context.runOnClient(client -> {
                    fixture.input().hideUiKey.setDown(false);
                    fixture.input().hideUiKey.setKey(fixture.defaultHideKey());
                    KeyMapping.resetMapping();
                    fixture.state().reset();
                    client.setScreenAndShow(null);
                });
            }
        }
    }

    private static Fixture prepare(Minecraft client) {
        ClientDebuggerState state = new ClientDebuggerState();
        state.applyPause(DebuggerPresentationGameTest.fixture(client));
        state.selectSource(1);
        InputManager input = DebuggerPresentationGameTest.input(client, state);
        require(input.hideUiKey.matches(key(InputConstants.KEY_H)), "default peek binding is H");
        DebuggerOverlay overlay = new DebuggerOverlay(state);
        LevelRenderEvents.END_MAIN.register(new DebugLevelRenderer(state, input));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("codon", "peek_ui_test"),
            new DebugHudElement(overlay, input));
        return new Fixture(state, input, new CodonScreen(input, overlay), state.snapshot(), state.selectedSourceIndex(),
            input.hideUiKey.getDefaultKey());
    }

    private static void checkKeyboardHoldAndBlockedInput(ClientGameTestContext context, Fixture fixture) {
        context.runOnClient(client -> {
            fixture.screen().setFocused(button(fixture.screen(), "View"));
            client.setLastInputType(InputType.KEYBOARD_TAB);
        });
        context.getInput().holdKey(options -> fixture.input().hideUiKey);
        context.runOnClient(client -> {
            CodonScreen screen = fixture.screen();
            DebuggerButton details = button(screen, "View");
            require(fixture.input().isUiHidden(), "holding H hides panels, labels, HUD, and world markers");
            Boolean inspectorVisible = fixture.state().preferences().inspectorVisible();

            MouseButtonEvent left = mouse(details.getX() + 2, details.getY() + 2, InputConstants.MOUSE_BUTTON_LEFT);
            require(screen.mouseClicked(left, false), "hidden UI consumes a click");
            require(screen.mouseDragged(left, 2, 0), "hidden UI consumes a drag");
            require(screen.mouseReleased(left), "hidden UI consumes a release");
            require(screen.mouseScrolled(left.x(), left.y(), 0, -1), "hidden UI consumes scrolling");
            require(screen.keyPressed(key(InputConstants.KEY_RETURN)), "hidden UI consumes focused-button activation");
            require(screen.getFocused() == details, "hiding does not discard focused control state");
            require(java.util.Objects.equals(inspectorVisible, fixture.state().preferences().inspectorVisible()),
                "clicking or activating hidden View must not change inspector visibility");
            preserved(fixture, "hidden interaction");
        });
    }

    private static void checkMouseRebind(ClientGameTestContext context, Fixture fixture) {
        context.runOnClient(client -> {
            fixture.input().hideUiKey.setKey(InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_RIGHT));
            KeyMapping.resetMapping();
            MouseButtonEvent right = mouse(400, 300, InputConstants.MOUSE_BUTTON_RIGHT);
            require(fixture.screen().mouseClicked(right, false), "mouse-rebound peek press is consumed");
            require(fixture.input().isUiHidden(), "mouse-rebound peek press hides debugger presentation");
            require(fixture.screen().mouseReleased(right), "mouse-rebound peek release is consumed");
            require(!fixture.input().isUiHidden(), "mouse-rebound peek release restores debugger presentation");
            preserved(fixture, "mouse-rebound peek");
            fixture.input().hideUiKey.setKey(fixture.defaultHideKey());
            KeyMapping.resetMapping();
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-peek-mouse-rebound-released");
    }

    private static void preserved(Fixture fixture, String phase) {
        require(fixture.state().isPaused(), phase + " must not resume the debugger");
        require(fixture.state().snapshot() == fixture.snapshot(), phase + " must retain the pause snapshot");
        require(fixture.state().selectedSourceIndex() == fixture.selectedSource(), phase + " must retain selection");
    }

    private static DebuggerButton button(CodonScreen screen, String label) {
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> button.getMessage().getString().equals(label)).findFirst()
            .orElseThrow(() -> new AssertionError("Missing fixture control: " + label));
    }

    private static KeyEvent key(int key) { return new KeyEvent(key, 0, 0); }

    private static MouseButtonEvent mouse(double x, double y, int button) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Fixture(ClientDebuggerState state, InputManager input, CodonScreen screen, PauseSnapshot snapshot,
                           int selectedSource, InputConstants.Key defaultHideKey) { }
}
