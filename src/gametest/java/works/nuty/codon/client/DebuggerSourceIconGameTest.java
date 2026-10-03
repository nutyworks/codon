package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.InputType;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.DebuggerPreferences;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.DebuggerIcon;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.client.ui.DebuggerTheme;
import works.nuty.codon.client.ui.FunctionSourceScreen;
import works.nuty.codon.client.ui.ScaledCodonScreen;
import works.nuty.codon.client.ui.layout.DebuggerLayout;

/** Native artwork/state evidence and activation of the unchanged Source toolbar action. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerSourceIconGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        int oldScale = context.computeOnClient(client -> client.options.guiScale().get());
        try {
            context.getInput().resizeWindow(1920, 1080);
            context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeGui(); });
            var preferences = new DebuggerPreferences();
            preferences.selectCustomUiScale(1);
            // Every quarter step in the usual range, plus this viewport's larger-window extension.
            for (int request = 4; request <= 18; request++) {
                preferences.setCustomUiScale(request);
                SampleScreen screen = context.computeOnClient(client -> {
                    var result = new SampleScreen(preferences);
                    client.setScreenAndShow(result);
                    return result;
                });
                hover(context, screen, 64, 49);
                context.runOnClient(client -> {
                    client.setLastInputType(InputType.KEYBOARD_TAB);
                    screen.setFocused(screen.buttons.get(2));
                });
                context.waitTicks(3);
                double scale = context.computeOnClient(client -> screen.uiScale().effective());
                require(scale == request / 4.0, "Scale request must actually be rendered");
                BufferedImage image;
                try { image = ImageIO.read(context.takeScreenshot("codon-source-icon-states-" + request).toFile()); }
                catch (IOException exception) { throw new AssertionError("Cannot read native icon evidence", exception); }
                boolean[] normal = null;
                for (int state = 0; state < 4; state++) {
                    int foreground = state == 3 ? DebuggerTheme.MUTED : DebuggerTheme.TEXT;
                    boolean[] mask = sampleIcon(image, 24 + state * 35, 44, scale, foreground);
                    checkCodeShape(mask);
                    if (normal == null) normal = mask;
                    else require(Arrays.equals(normal, mask), "All states retain the same code artwork at " + scale);
                }
                // Chrome continues to distinguish hover, keyboard focus and disabled controls.
                checkColor(image, 20, 40, scale, 0xff101010);
                checkColor(image, 55, 40, scale, DebuggerTheme.RAISED);
                checkColor(image, 90, 40, scale, DebuggerTheme.RAISED);
                checkColor(image, 91, 41, scale, DebuggerTheme.TEXT);
                checkColor(image, 125, 40, scale, 0xff101010);
            }
            context.runOnClient(client -> client.setScreenAndShow(null));
            checkSourceAction(context);
        } finally {
            context.runOnClient(client -> {
                client.setScreenAndShow(null);
                client.options.guiScale().set(oldScale);
                client.resizeGui();
            });
        }
    }

    private static void checkSourceAction(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1280, 960);
            world.getConnection().waitForChunksRender();
            var state = new ClientDebuggerState();
            state.preferences().selectCustomUiScale(2.25);
            CodonScreen screen = context.computeOnClient(client -> {
                var result = new CodonScreen(DebuggerPresentationGameTest.input(client, state), new DebuggerOverlay(state));
                client.setScreenAndShow(result);
                return result;
            });
            hover(context, screen, 0, 0);
            context.waitTicks(3);
            context.runOnClient(client -> {
                var source = source(screen);
                require(source.active && source.icon() == DebuggerIcon.SOURCE_FILE, "Source is the active code icon");
                require(source.getWidth() == DebuggerLayout.ICON_BUTTON_SIZE
                    && source.getHeight() == DebuggerLayout.ICON_BUTTON_SIZE, "Source retains its 20-pixel click target");
            });
            context.takeScreenshot("codon-source-toolbar-normal");
            double[] center = context.computeOnClient(client -> new double[]{source(screen).getX() + 10, source(screen).getY() + 10});
            hover(context, screen, center[0], center[1]);
            context.waitTicks(12);
            context.takeScreenshot("codon-source-toolbar-hover-tooltip");
            hover(context, screen, 0, 0);
            context.runOnClient(client -> {
                client.setLastInputType(InputType.KEYBOARD_TAB);
                screen.setFocused(source(screen));
            });
            context.waitTicks(3);
            context.takeScreenshot("codon-source-toolbar-keyboard-focus");
            context.getInput().pressKey(InputConstants.KEY_RETURN);
            context.waitFor(client -> client.gui.screen() instanceof FunctionSourceScreen, 100);
            context.takeScreenshot("codon-source-toolbar-enter-opens-viewer");
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitFor(client -> client.gui.screen() == screen, 100);
            // Native clicks at both corners prove that the retained target extends beyond the glyph.
            for (boolean farCorner : new boolean[]{false, true}) {
                double[] corner = context.computeOnClient(client -> {
                    var source = source(screen);
                    return new double[]{source.getX() + (farCorner ? source.getWidth() - 0.25 : 0.25),
                        source.getY() + (farCorner ? source.getHeight() - 0.25 : 0.25)};
                });
                hover(context, screen, corner[0], corner[1]);
                context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
                context.waitFor(client -> client.gui.screen() instanceof FunctionSourceScreen, 100);
                context.getInput().pressKey(InputConstants.KEY_ESCAPE);
                context.waitFor(client -> client.gui.screen() == screen, 100);
            }
        }
    }

    private static DebuggerButton source(CodonScreen screen) {
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> button.getMessage().equals(Component.translatable("codon.source.title")))
            .findFirst().orElseThrow(() -> new AssertionError("Source toolbar action is missing"));
    }

    private static void hover(ClientGameTestContext context, ScaledCodonScreen screen, double x, double y) {
        double[] nativePosition = context.computeOnClient(client -> {
            var window = client.getWindow();
            return new double[]{screen.uiScale().toGame(x) * window.getScreenWidth() / window.getGuiScaledWidth(),
                screen.uiScale().toGame(y) * window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(nativePosition[0], nativePosition[1]);
    }

    private static boolean[] sampleIcon(BufferedImage image, int left, int top, double scale, int foreground) {
        boolean[] mask = new boolean[144];
        for (int y = 0; y < 12; y++) for (int x = 0; x < 12; x++)
            mask[y * 12 + x] = rgbAt(image, left + x, top + y, scale) == (foreground & 0xffffff);
        return mask;
    }

    private static void checkCodeShape(boolean[] mask) {
        // Three disconnected strokes, with opposed chevrons and a forward slash.
        // Inspect rendered geometry rather than duplicating the production pixel table.
        boolean[] visited = new boolean[mask.length];
        List<int[]> bounds = new ArrayList<>();
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || visited[start]) continue;
            int[] box = {12, 12, -1, -1};
            var queue = new ArrayDeque<Integer>();
            queue.add(start);
            visited[start] = true;
            while (!queue.isEmpty()) {
                int point = queue.remove();
                int x = point % 12, y = point / 12;
                box[0] = Math.min(box[0], x); box[1] = Math.min(box[1], y);
                box[2] = Math.max(box[2], x); box[3] = Math.max(box[3], y);
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || nx >= 12 || ny < 0 || ny >= 12) continue;
                    int next = ny * 12 + nx;
                    if (mask[next] && !visited[next]) { visited[next] = true; queue.add(next); }
                }
            }
            bounds.add(box);
        }
        bounds.sort(java.util.Comparator.comparingInt(box -> box[0]));
        require(bounds.size() == 3, "Code symbol must have three separate readable strokes");
        require(Arrays.equals(bounds.get(0), new int[]{0, 3, 2, 8})
            && Arrays.equals(bounds.get(1), new int[]{4, 1, 7, 10})
            && Arrays.equals(bounds.get(2), new int[]{9, 3, 11, 8}), "Code strokes stay centered inside the icon canvas");
        require(mask[5 * 12] && mask[3 * 12 + 2] && mask[8 * 12 + 2]
            && mask[5 * 12 + 11] && mask[3 * 12 + 9] && mask[8 * 12 + 9]
            && mask[1 * 12 + 7] && mask[10 * 12 + 4], "Chevrons point outward around a forward slash");
    }

    private static int rgbAt(BufferedImage image, double x, double y, double scale) {
        return image.getRGB((int) Math.floor((x + 0.5) * scale), (int) Math.floor((y + 0.5) * scale)) & 0xffffff;
    }
    private static void checkColor(BufferedImage image, int x, int y, double scale, int color) {
        require(rgbAt(image, x, y, scale) == (color & 0xffffff), "Source button retains its state chrome");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class SampleScreen extends ScaledCodonScreen {
        final List<DebuggerButton> buttons = new ArrayList<>();
        SampleScreen(DebuggerPreferences preferences) { super(Component.literal("Source icon states"), preferences); }
        @Override protected void init() {
            buttons.clear();
            for (int state = 0; state < 4; state++) {
                var button = new DebuggerButton();
                button.configure(20 + state * 35, 40, 20, 20, Component.translatable("codon.source.title"),
                    state != 3, false, false, false, () -> { });
                buttons.add(addRenderableWidget(button.withIcon(DebuggerIcon.SOURCE_FILE).withFlatChrome().withOpaqueColors()));
            }
        }
        @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            graphics.fill(0, 0, width, height, 0xff101010);
            graphics.text(font, "Source: normal / hover / focus / disabled", 20, 20, DebuggerTheme.TEXT, false);
            super.extractRenderState(graphics, mouseX, mouseY, delta);
        }
    }
}
