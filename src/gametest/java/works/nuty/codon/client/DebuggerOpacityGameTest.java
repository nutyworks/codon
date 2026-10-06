package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
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
import works.nuty.codon.core.model.PauseReason;
import works.nuty.codon.core.model.PauseSnapshot;

/** Real renderer and input checks using a synthetic pause, not server stepping acceptance. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerOpacityGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        String oldLanguage = context.computeOnClient(client -> client.getLanguageManager().getSelected());
        int oldScale = context.computeOnClient(client -> client.options.guiScale().get());
        int[] oldWindow = context.computeOnClient(client ->
            new int[]{client.getWindow().getScreenWidth(), client.getWindow().getScreenHeight()});
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
            capturePercent(context, screen, "codon-opacity-inline-100");
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
                require((DebuggerTheme.color(DebuggerTheme.SURFACE) >>> 24) == 128, "Surface alpha follows slider");
                client.setLastInputType(InputType.KEYBOARD_ARROW);
                screen.setFocused(slider);
                screen.keyPressed(new KeyEvent(InputConstants.KEY_RIGHT, InputConstants.KEYCODE_RIGHT, 0));
                require(state.preferences().backgroundOpacity() == 51, "Right arrow adjusts by one percent");
                screen.keyPressed(new KeyEvent(InputConstants.KEY_LEFT, InputConstants.KEYCODE_LEFT, 0));
                require(DebuggerLayout.create(screen.width, screen.height, true).header().height() == 18,
                    "Opacity does not add a header row");
                require(state.preferences().backgroundOpacity() == 50, "Left arrow returns by one percent");
                require(saves.get() == 3, "Release and each effective arrow press write once");
                // Shift moves by ten, clamps at both ends and an arrow that changes nothing writes nothing.
                int writes = saves.get();
                arrow(screen, InputConstants.KEY_RIGHT, true);
                require(state.preferences().backgroundOpacity() == 60 && saves.get() == writes + 1,
                    "Shift+Right adjusts by ten percent with one write");
                arrow(screen, InputConstants.KEY_LEFT, true);
                arrow(screen, InputConstants.KEY_LEFT, true);
                require(state.preferences().backgroundOpacity() == 40 && saves.get() == writes + 3,
                    "Shift+Left adjusts by ten percent");
                for (int i = 0; i < 4; i++) arrow(screen, InputConstants.KEY_LEFT, true);
                require(state.preferences().backgroundOpacity() == 0 && saves.get() == writes + 7,
                    "Shift+Left reaches zero opacity");
                arrow(screen, InputConstants.KEY_LEFT, true);
                arrow(screen, InputConstants.KEY_LEFT, false);
                require(state.preferences().backgroundOpacity() == 0 && saves.get() == writes + 7,
                    "Arrow presses at zero opacity write nothing");
                arrow(screen, InputConstants.KEY_RIGHT, false);
                require(state.preferences().backgroundOpacity() == 1 && saves.get() == writes + 8,
                    "Plain Right still adjusts by one percent");
                state.preferences().setBackgroundOpacity(95);
                writes = saves.get();
                arrow(screen, InputConstants.KEY_RIGHT, true);
                require(state.preferences().backgroundOpacity() == 100 && saves.get() == writes + 1,
                    "Shift+Right clamps a partial step at full opacity with one write");
                arrow(screen, InputConstants.KEY_RIGHT, true);
                arrow(screen, InputConstants.KEY_RIGHT, false);
                require(state.preferences().backgroundOpacity() == 100 && saves.get() == writes + 1,
                    "Arrow presses at full opacity write nothing");
                arrow(screen, InputConstants.KEY_LEFT, true);
                require(state.preferences().backgroundOpacity() == 90 && saves.get() == writes + 2,
                    "Shift+Left leaves full opacity by ten percent");
                writes = saves.get();
                require(!slider.keyPressed(new KeyEvent(InputConstants.KEY_UP, InputConstants.KEYCODE_UP,
                        InputConstants.MOD_SHIFT)) && state.preferences().backgroundOpacity() == 90
                        && saves.get() == writes, "Shift with other keys is left to navigation");
                state.preferences().setBackgroundOpacity(50);
                screen.setFocused(null);
                client.setLastInputType(InputType.MOUSE);
            });
            // A short sample through Minecraft's KeyboardHandler; the direct calls above remain the endpoint sweep.
            context.runOnClient(client -> {
                client.setLastInputType(InputType.KEYBOARD_ARROW);
                screen.setFocused(slider(screen));
            });
            int keyWrites = saves.get();
            context.getInput().pressKey(InputConstants.KEY_RIGHT);
            requireKeyboardStep(context, screen, state, saves, keyWrites + 1, 51, "Dispatched Right adjusts by one percent");
            context.getInput().pressKey(InputConstants.KEY_LEFT);
            requireKeyboardStep(context, screen, state, saves, keyWrites + 2, 50, "Dispatched Left returns by one percent");
            context.getInput().holdShift();
            try {
                pressShiftArrow(context, InputConstants.KEY_RIGHT);
                requireKeyboardStep(context, screen, state, saves, keyWrites + 3, 60,
                    "Dispatched Shift+Right adjusts by ten percent");
                pressShiftArrow(context, InputConstants.KEY_LEFT);
                requireKeyboardStep(context, screen, state, saves, keyWrites + 4, 50,
                    "Dispatched Shift+Left returns by ten percent");
            } finally {
                context.getInput().releaseShift();
            }
            context.runOnClient(client -> {
                screen.setFocused(null);
                client.setLastInputType(InputType.MOUSE);
            });
            moveCursor(context, screen, false);
            context.waitTicks(3);
            capturePercent(context, screen, "codon-opacity-inline-50");
            moveCursor(context, screen, true);
            context.waitTicks(3);
            context.takeScreenshot("codon-opacity-hover-50");
            moveCursor(context, screen, false);
            context.runOnClient(client -> {
                require((DebuggerTheme.foreground(DebuggerTheme.TEXT) >>> 24) == 255, "Rendered text retains full alpha");
                require((DebuggerTheme.color(DebuggerTheme.BORDER) >>> 24) == 128, "Borders follow the same opacity");
                state.preferences().setBackgroundOpacity(0);
            });
            context.waitTicks(3);
            capturePercent(context, screen, "codon-opacity-all-zero");
            context.runOnClient(client -> state.preferences().setBackgroundOpacity(1));
            context.waitTicks(3);
            capturePercent(context, screen, "codon-opacity-all-one");
            context.runOnClient(client -> state.preferences().setBackgroundOpacity(50));
            context.getInput().resizeWindow(640, 480);
            context.waitTicks(3);
            moveCursor(context, screen, false);
            context.runOnClient(client -> {
                require(screen.width == 320 && screen.height == 240, "Compact fixture reaches 320x240 GUI pixels");
                var slider = slider(screen);
                require(slider.getX() >= 0 && slider.getRight() <= screen.width
                    && slider.getY() >= 0 && slider.getBottom() <= 24,
                    "Compact slider stays inside the title row");
                require(slider.getRight() + client.font.width("100%") <= screen.width,
                    "Compact percentage stays inside the viewport");
                // The header panel spans the wider of the title row and the toolbar below it.
                var layout = DebuggerLayout.create(screen.width, screen.height, true);
                var title = layout.header();
                int panelRight = title.x() + Math.max(title.width(), layout.controls().width());
                require(slider.getX() >= title.x() && slider.getY() >= title.y()
                    && slider.getBottom() <= title.y() + title.height()
                    && slider.getRight() + client.font.width("100%") <= panelRight,
                    "Compact slider and percentage stay inside the header panel title row");
            });
            context.waitTicks(3);
            capturePercent(context, screen, "codon-opacity-inline-compact-50");
            context.runOnClient(client -> {
                var slider = slider(screen);
                int beforeClose = saves.get();
                screen.mouseClicked(mouse(slider.getX() + 4, slider.getY() + 8), false);
                require(saves.get() == beforeClose, "Pending drag remains a preview");
                client.setScreenAndShow(null);
                require(saves.get() == beforeClose + 1, "Closing during drag commits once");
            });
            context.getInput().resizeWindow(1280, 800);
            context.runOnClient(client -> {
                state.preferences().setBackgroundOpacity(50);
                var input = DebuggerPresentationGameTest.input(client, state);
                var editor = new works.nuty.codon.client.ui.WatchScreen(input, state, new DebuggerOverlay(state));
                client.setScreenAndShow(editor);
                var field = editor.children().stream().filter(net.minecraft.client.gui.components.EditBox.class::isInstance)
                    .map(net.minecraft.client.gui.components.EditBox.class::cast).findFirst().orElseThrow();
                field.setValue("demo:opacity");
                field.setHighlightPos(0);
            });
            context.waitTicks(3);
            context.takeScreenshot("codon-opacity-editor-50");
            context.runOnClient(client -> state.preferences().setBackgroundOpacity(0));
            context.waitTicks(3);
            context.takeScreenshot("codon-opacity-editor-zero");
            context.runOnClient(client -> client.setScreenAndShow(null));
            // The final-inspection status in each language shares the title row with the percentage at both endpoints.
            for (String language : new String[]{"en_us", "ko_kr"}) {
                language(context, language);
                for (int[] size : new int[][]{{1280, 800}, {640, 480}}) {
                    context.getInput().resizeWindow(size[0], size[1]);
                    CodonScreen header = context.computeOnClient(client -> {
                        state.applyPause(complete(DebuggerPresentationGameTest.fixture(client)));
                        var result = new CodonScreen(DebuggerPresentationGameTest.input(client, state),
                            new DebuggerOverlay(state));
                        client.setScreenAndShow(result);
                        return result;
                    });
                    context.waitTicks(3);
                    for (int opacity : new int[]{100, 0}) {
                        context.runOnClient(client -> state.preferences().setBackgroundOpacity(opacity));
                        moveCursor(context, header, false);
                        context.waitTicks(3);
                        capturePercent(context, header, "codon-opacity-header-" + language + "-" + size[0] + "-" + opacity);
                    }
                }
            }
            context.runOnClient(client -> client.setScreenAndShow(null));
        } finally {
            context.runOnClient(client -> {
                client.setScreenAndShow(null);
                DebuggerTheme.usePreferences(CodonClientMod.state().preferences());
            });
            context.getInput().resizeWindow(oldWindow[0], oldWindow[1]);
            context.runOnClient(client -> {
                client.options.guiScale().set(oldScale);
                client.resizeGui();
            });
            language(context, oldLanguage);
        }
    }

    /** The percentage is plain header text right of the slider; require its ink in a frame without hover. */
    private static void capturePercent(ClientGameTestContext context, CodonScreen screen, String name) {
        int[] box = context.computeOnClient(client -> {
            var slider = slider(screen);
            return new int[]{slider.getRight(), slider.getY(), client.font.width("100%"), slider.getHeight(), screen.width};
        });
        require(box[0] + box[2] <= box[4], "Percentage stays inside the viewport in " + name);
        Path screenshot = context.takeScreenshot(name);
        BufferedImage image;
        try { image = ImageIO.read(screenshot.toFile()); }
        catch (IOException exception) { throw new AssertionError("Cannot read native header pixels", exception); }
        double scale = image.getWidth() / (double) box[4];
        int ink = 0;
        int bottom = Math.min(image.getHeight(), (int) Math.ceil((box[1] + box[3]) * scale));
        int right = Math.min(image.getWidth(), (int) Math.ceil((box[0] + box[2]) * scale));
        for (int y = (int) (box[1] * scale); y < bottom; y++) {
            for (int x = (int) (box[0] * scale); x < right; x++) {
                if (isHeaderText(image.getRGB(x, y))) ink++;
            }
        }
        // "0%" alone has well over eight lit font pixels, each covering scale x scale framebuffer pixels.
        require(ink >= 8 * scale * scale, "Percentage text is visible beside the slider without hover in " + name);
    }

    private static boolean isHeaderText(int rgb) {
        int text = DebuggerTheme.TEXT;
        return Math.abs(((rgb >> 16) & 0xFF) - ((text >> 16) & 0xFF)) <= 4
            && Math.abs(((rgb >> 8) & 0xFF) - ((text >> 8) & 0xFF)) <= 4
            && Math.abs((rgb & 0xFF) - (text & 0xFF)) <= 4;
    }

    /** Final-inspection status coverage; the synthetic snapshot keeps pauseId zero, so no server pause is queryable. */
    private static PauseSnapshot complete(PauseSnapshot base) {
        return new PauseSnapshot(base.location(), base.command(), base.depth(), base.callStack(),
            base.pauseSources(), base.executionFlows(), PauseReason.EXECUTION_COMPLETE);
    }

    /** Fabric's pressKey builds KeyEvents without modifier bits, so Shift arrows enter the same handler with one. */
    private static void pressShiftArrow(ClientGameTestContext context, int key) {
        int scan = key == InputConstants.KEY_LEFT ? InputConstants.KEYCODE_LEFT : InputConstants.KEYCODE_RIGHT;
        context.runOnClient(client -> {
            for (int action : new int[]{InputConstants.PRESS, InputConstants.RELEASE}) {
                client.keyboardHandler.keyPress(client.getWindow().handle(), action,
                    new KeyEvent(key, scan, InputConstants.MOD_SHIFT));
            }
        });
        context.waitTick();
    }

    /** After the waited tick the same screen is open, the current slider still has focus, and one write happened. */
    private static void requireKeyboardStep(ClientGameTestContext context, CodonScreen screen, ClientDebuggerState state,
                                            java.util.concurrent.atomic.AtomicInteger saves, int expectedWrites,
                                            int expectedOpacity, String message) {
        context.runOnClient(client -> {
            require(client.gui.screen() == screen, "The same CodonScreen stays open: " + message);
            require(screen.getFocused() == slider(screen), "The current slider stays focused: " + message);
            require(state.preferences().backgroundOpacity() == expectedOpacity, message);
            require(saves.get() == expectedWrites, message + " with one settings write");
        });
    }

    private static void arrow(CodonScreen screen, int key, boolean shift) {
        int scan = key == InputConstants.KEY_LEFT ? InputConstants.KEYCODE_LEFT : InputConstants.KEYCODE_RIGHT;
        screen.keyPressed(new KeyEvent(key, scan, shift ? InputConstants.MOD_SHIFT : 0));
    }

    private static void language(ClientGameTestContext context, String language) {
        if (context.computeOnClient(client -> client.getLanguageManager().getSelected()).equals(language)) return;
        var reload = context.computeOnClient(client -> {
            client.getLanguageManager().setSelected(language);
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 200);
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
