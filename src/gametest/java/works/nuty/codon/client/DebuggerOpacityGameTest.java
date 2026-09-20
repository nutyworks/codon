package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.InputType;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.ui.BackgroundOpacitySlider;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.client.ui.DebuggerTheme;
import works.nuty.codon.client.ui.layout.DebuggerLayout;

/** Real renderer and input checks using a synthetic pause, not server stepping acceptance. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerOpacityGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1280, 800);
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
            });
            world.getConnection().waitForChunksRender();
            ClientDebuggerState state = new ClientDebuggerState();
            CodonScreen screen = context.computeOnClient(client -> {
                DebuggerTheme.usePreferences(state.preferences());
                state.applyPause(DebuggerPresentationGameTest.fixture(client));
                var result = new CodonScreen(DebuggerPresentationGameTest.input(client, state), new DebuggerOverlay(state));
                client.setScreenAndShow(result);
                return result;
            });
            context.waitTicks(3);
            context.takeScreenshot("codon-opacity-inline-100");
            var saves = new java.util.concurrent.atomic.AtomicInteger();
            state.preferences().setChangeListener(saves::incrementAndGet);
            context.runOnClient(client -> {
                var slider = slider(screen);
                double y = slider.getY() + slider.getHeight() / 2.0;
                screen.mouseClicked(mouse(slider.getX() + 4, y), false);
                require(state.preferences().backgroundOpacity() == 0, "Click reaches zero opacity");
                screen.mouseDragged(mouse(slider.getRight() - 4, y), slider.getWidth() - 8, 0);
                require(state.preferences().backgroundOpacity() == 100, "Drag reaches full opacity");
                screen.mouseDragged(mouse(slider.getX() + slider.getWidth() / 2.0, y), -slider.getWidth() / 2.0, 0);
                require(saves.get() == 0, "Drag previews do not write settings on every frame");
                screen.mouseReleased(mouse(slider.getX() + slider.getWidth() / 2.0, y));
                require(saves.get() == 1, "Release commits the final value once");
                require(state.preferences().backgroundOpacity() == 50, "Dragging updates opacity live");
                require((DebuggerTheme.background(DebuggerTheme.SURFACE) >>> 24) == 128, "Surface alpha follows slider");
                client.setLastInputType(InputType.KEYBOARD_ARROW);
                screen.setFocused(slider);
                screen.keyPressed(new KeyEvent(InputConstants.KEY_RIGHT, InputConstants.KEYCODE_RIGHT, 0));
                require(state.preferences().backgroundOpacity() == 51, "Right arrow adjusts by one percent");
                screen.keyPressed(new KeyEvent(InputConstants.KEY_LEFT, InputConstants.KEYCODE_LEFT, 0));
                require(DebuggerLayout.create(screen.width, screen.height, true).header().height() == 18,
                    "Opacity does not add a header row");
                screen.setFocused(null);
                client.setLastInputType(InputType.MOUSE);
            });
            moveCursor(context, screen, false);
            context.waitTicks(3);
            context.takeScreenshot("codon-opacity-inline-50");
            moveCursor(context, screen, true);
            context.waitTicks(3);
            context.takeScreenshot("codon-opacity-hover-50");
            context.getInput().resizeWindow(640, 480);
            context.waitTicks(3);
            moveCursor(context, screen, false);
            context.runOnClient(client -> {
                var slider = slider(screen);
                require(slider.getX() >= 0 && slider.getRight() <= screen.width
                    && slider.getY() >= 0 && slider.getBottom() <= 24,
                    "Compact slider stays inside the title row");
            });
            context.waitTicks(3);
            context.takeScreenshot("codon-opacity-inline-compact-50");
            context.runOnClient(client -> {
                var slider = slider(screen);
                int beforeClose = saves.get();
                screen.mouseClicked(mouse(slider.getX() + 4, slider.getY() + 8), false);
                require(saves.get() == beforeClose, "Pending drag remains a preview");
                client.setScreenAndShow(null);
                require(saves.get() == beforeClose + 1, "Closing during drag commits once");
            });
        } finally {
            context.runOnClient(client -> DebuggerTheme.usePreferences(CodonClientMod.state().preferences()));
        }
    }

    private static void moveCursor(ClientGameTestContext context, CodonScreen screen, boolean hover) {
        double[] physical = context.computeOnClient(client -> {
            var slider = slider(screen);
            double x = hover ? slider.getX() + slider.getWidth() / 2.0 : screen.width / 2.0;
            double y = hover ? slider.getY() + slider.getHeight() / 2.0 : screen.height / 2.0;
            return new double[]{x * client.getWindow().getScreenWidth() / screen.width,
                y * client.getWindow().getScreenHeight() / screen.height};
        });
        context.getInput().setCursorPos(physical[0], physical[1]);
    }

    private static BackgroundOpacitySlider slider(CodonScreen screen) {
        return screen.children().stream().filter(BackgroundOpacitySlider.class::isInstance)
            .map(BackgroundOpacitySlider.class::cast).findFirst().orElseThrow();
    }

    private static MouseButtonEvent mouse(double x, double y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
