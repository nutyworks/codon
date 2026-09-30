package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.InputType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.testmixin.WindowFocusAccessor;
import works.nuty.codon.client.input.UiHideGesture;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.core.model.PauseSnapshot;

/**
 * Native input and rendering coverage with a synthetic client pause. Server breakpoint execution
 * and actual OS focus changes need separate verification.
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
                context.runOnClient(client -> require(fixture.input().isUiHidden(), "native world press hides immediately"));
                waitForLongHold(context);
                context.takeScreenshot("codon-peek-passive-held");
                context.getInput().releaseKey(options -> fixture.input().hideUiKey);
                context.runOnClient(client -> require(!fixture.input().isUiHidden(), "long world release restores visible state"));
                tap(context, fixture);
                context.runOnClient(client -> require(fixture.input().isUiHidden(), "short world release latches hidden state"));
                context.getInput().holdKey(options -> fixture.input().hideUiKey);
                waitForLongHold(context);
                context.getInput().releaseKey(options -> fixture.input().hideUiKey);
                context.runOnClient(client -> require(fixture.input().isUiHidden(), "long hold restores already hidden state"));
                context.takeScreenshot("codon-peek-passive-toggled");
                tap(context, fixture);
                context.runOnClient(client -> require(!fixture.input().isUiHidden(), "second world tap restores visibility"));
                context.runOnClient(client -> client.setScreenAndShow(fixture.screen()));
                context.waitTicks(3);
                context.takeScreenshot("codon-peek-cursor-before-hold");

                checkKeyboardHoldAndBlockedInput(context, fixture);
                waitForLongHold(context);
                context.takeScreenshot("codon-peek-cursor-held");
                context.getInput().releaseKey(options -> fixture.input().hideUiKey);
                context.runOnClient(client -> {
                    require(!fixture.input().isUiHidden(), "long screen release restores debugger presentation");
                    preserved(fixture, "keyboard release");
                });
                tap(context, fixture);
                context.runOnClient(client -> require(fixture.input().isUiHidden(), "native screen tap toggles hidden"));
                context.takeScreenshot("codon-peek-cursor-toggled");
                tap(context, fixture);
                context.runOnClient(client -> require(!fixture.input().isUiHidden(), "second screen tap restores visibility"));
                context.waitTicks(2);
                context.takeScreenshot("codon-peek-cursor-released");

                checkMouseRebind(context, fixture);
                checkTypingGuard(context, fixture);
                checkFocusReset(context, fixture);
                context.getInput().holdKey(options -> fixture.input().hideUiKey);
                context.getInput().pressKey(options -> fixture.input().menuKey);
                context.runOnClient(client -> {
                    require(client.gui.screen() == null, "menu closes cursor mode while held");
                    require(!fixture.input().isUiHidden(), "screen transition safely resets visibility");
                    preserved(fixture, "menu close while held");
                });
                context.getInput().releaseKey(options -> fixture.input().hideUiKey);
                context.runOnClient(client -> require(!fixture.input().isUiHidden(), "cancelled release cannot toggle"));
                tap(context, fixture);
                context.runOnClient(client -> {
                    require(fixture.input().isUiHidden(), "fresh native press works after cancelled gesture");
                    client.setScreenAndShow(new ChatScreen("", false));
                    require(!fixture.input().isUiHidden(), "chat transition resets latched visibility");
                });
                tap(context, fixture);
                context.runOnClient(client -> {
                    require(!fixture.input().isUiHidden(), "typing H in chat cannot hide debugger UI");
                    client.setScreenAndShow(null);
                    preserved(fixture, "chat input");
                    fixture.input().hideUiKey.setKey(InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_RIGHT));
                    KeyMapping.resetMapping();
                    MouseButtonInfo right = new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0);
                    client.mouseHandler.onButton(client.getWindow().handle(), right, InputConstants.PRESS);
                    require(fixture.input().isUiHidden(), "native world mouse press hides immediately");
                    client.mouseHandler.onButton(client.getWindow().handle(), right, InputConstants.RELEASE);
                    require(fixture.input().isUiHidden(), "native world mouse tap toggles hidden");
                    fixture.input().hideUiKey.setKey(fixture.defaultHideKey());
                    KeyMapping.resetMapping();
                });
                context.waitTicks(2);
                context.takeScreenshot("codon-peek-passive-released");
                tap(context, fixture);
                context.runOnClient(client -> require(fixture.input().isUiHidden(), "leave a hidden session before disconnect"));
            } finally {
                context.runOnClient(client -> {
                    fixture.input().hideUiKey.setKey(fixture.defaultHideKey());
                    KeyMapping.resetMapping();
                    fixture.state().reset();
                    // Keep the final hidden world session until teardown so disconnect is observed.
                    if (client.gui.screen() != null) client.setScreenAndShow(null);
                });
            }
        }
        context.runOnClient(client -> require(!CodonClientMod.input().isUiHidden(), "disconnect clears hidden session"));
        try (TestSingleplayerContext rejoined = context.worldBuilder().create()) {
            rejoined.getConnection().waitForChunksRender();
            context.runOnClient(client -> require(!CodonClientMod.input().isUiHidden(), "rejoin starts with visible UI"));
        }
    }

    private static void checkFocusReset(ClientGameTestContext context, Fixture fixture) {
        context.getInput().holdKey(options -> fixture.input().hideUiKey);
        context.runOnClient(client -> {
            WindowFocusAccessor window = (WindowFocusAccessor) (Object) client.getWindow();
            try {
                window.codon$focus(false);
                require(!client.isWindowActive(), "simulated focus flag reaches the production query");
                require(!fixture.input().isUiHidden(), "focus loss cancels the held gesture and shows UI");
            } finally {
                window.codon$focus(true);
            }
            require(!fixture.input().isUiHidden(), "focus regain cannot restart a still-held key");
        });
        context.getInput().releaseKey(options -> fixture.input().hideUiKey);
        context.runOnClient(client -> require(!fixture.input().isUiHidden(), "release after focus loss cannot toggle"));
        tap(context, fixture);
        context.runOnClient(client -> {
            WindowFocusAccessor window = (WindowFocusAccessor) (Object) client.getWindow();
            try {
                window.codon$focus(false);
                require(!client.isWindowActive(), "simulated focus flag reaches the production query");
                require(!fixture.input().isUiHidden(), "focus loss clears latched visibility");
            } finally {
                window.codon$focus(true);
            }
        });
    }

    private static Fixture prepare(Minecraft client) {
        ClientDebuggerState state = CodonClientMod.state();
        require(state != null, "production debugger state is initialized");
        state.reset();
        state.applyPause(DebuggerPresentationGameTest.fixture(client));
        state.selectSource(1);
        InputManager input = CodonClientMod.input();
        require(input != null, "production input manager is initialized");
        input.resetUiVisibility();
        require(input.hideUiKey.matches(key(InputConstants.KEY_H)), "default peek binding is H");
        DebuggerOverlay overlay = new DebuggerOverlay(state);
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

    private static void tap(ClientGameTestContext context, Fixture fixture) {
        context.runOnClient(client -> {
            KeyEvent event = key(net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
                .getBoundKeyOf(fixture.input().hideUiKey).getValue());
            client.keyboardHandler.keyPress(client.getWindow().handle(), InputConstants.PRESS, event);
            client.keyboardHandler.keyPress(client.getWindow().handle(), InputConstants.REPEAT, event);
            client.keyboardHandler.keyPress(client.getWindow().handle(), InputConstants.RELEASE, event);
        });
    }

    private static void waitForLongHold(ClientGameTestContext context) {
        long start = System.nanoTime();
        context.waitFor(client -> System.nanoTime() - start >= UiHideGesture.HOLD_NANOS, 200);
    }

    private static void checkTypingGuard(ClientGameTestContext context, Fixture fixture) {
        context.runOnClient(client -> fixture.screen().setFocused(
            new EditBox(client.font, 0, 0, 100, 20, Component.empty())));
        tap(context, fixture);
        context.runOnClient(client -> {
            require(!fixture.input().isUiHidden(), "focused text input ignores the hide binding");
            fixture.screen().setFocused(null);
        });
    }

    private static void checkMouseRebind(ClientGameTestContext context, Fixture fixture) {
        tap(context, fixture);
        context.runOnClient(client -> {
            fixture.input().hideUiKey.setKey(InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_RIGHT));
            KeyMapping.resetMapping();
            require(!fixture.input().isUiHidden(), "rebind resets latched visibility");
            MouseButtonEvent right = mouse(400, 300, InputConstants.MOUSE_BUTTON_RIGHT);
            require(fixture.screen().mouseClicked(right, false), "mouse-rebound press is consumed");
            require(fixture.input().isUiHidden(), "mouse-rebound press hides immediately");
            require(fixture.screen().mouseReleased(right), "mouse-rebound release is consumed");
            require(fixture.input().isUiHidden(), "mouse-rebound short release toggles hidden");
            fixture.screen().mouseClicked(right, false);
            fixture.screen().mouseReleased(right);
            require(!fixture.input().isUiHidden(), "second mouse-rebound tap restores visibility");
            preserved(fixture, "mouse-rebound tap");
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
