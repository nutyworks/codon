package works.nuty.codon.client;

import java.nio.file.Path;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.state.DebuggerPreferences;
import works.nuty.codon.client.ui.ScaledCodonScreen;
import works.nuty.codon.client.ui.DebuggerButton;

/** Native glyph evidence; reference and fractional samples use the player's Minecraft font. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerFontSamplingGameTest implements FabricClientGameTest {
    private static final String SAMPLE = "사용 안내 표시 상태 일시 정지 실행 중 실행 완료 컨텍스트";

    @Override public void runTest(ClientGameTestContext context) {
        int oldScale = context.computeOnClient(client -> client.options.guiScale().get());
        String oldLanguage = context.computeOnClient(client -> client.getLanguageManager().getSelected());
        boolean oldUnicode = context.computeOnClient(client -> client.options.forceUnicodeFont().get());
        try {
            context.getInput().resizeWindow(1280, 800);
            context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeGui(); });
            var preferences = new DebuggerPreferences();
            preferences.selectCustomUiScale(2);
            for (String language : new String[]{"ko_kr", "en_us"}) {
                language(context, language);
                BufferedImage reference = capture(context, preferences, 8, language);
                for (int request : language.equals("ko_kr") ? new int[]{6, 4, 5, 7, 9} : new int[]{6, 9}) {
                    BufferedImage sample = capture(context, preferences, request, language);
                    checkCoverage(reference, sample, request / 4.0);
                    // The vanilla control is drawn at a fixed game scale, outside Codon's sampler scope.
                    for (int y = 720; y < 740; y++) for (int x = 40; x < 600; x++) {
                        require(reference.getRGB(x, y) == sample.getRGB(x, y), "Vanilla font pixels must remain unchanged");
                    }
                }
                if (language.equals("ko_kr")) {
                    context.getInput().resizeWindow(640, 480);
                    checkCoverage(reference, capture(context, preferences, 6, "ko_kr-narrow"), 1.5);
                    context.getInput().resizeWindow(1280, 800);
                }
            }
            context.runOnClient(client -> client.options.forceUnicodeFont().set(true));
            context.waitTicks(3);
            BufferedImage unicode = capture(context, preferences, 8, "unicode");
            checkCoverage(unicode, capture(context, preferences, 6, "unicode"), 1.5);
        } catch (Throwable failure) {
            failure.printStackTrace();
            throw failure;
        } finally {
            context.runOnClient(client -> {
                client.setScreenAndShow(null);
                client.options.guiScale().set(oldScale);
                client.options.forceUnicodeFont().set(oldUnicode);
                client.resizeGui();
            });
            language(context, oldLanguage);
        }
    }

    private static void language(ClientGameTestContext context, String language) {
        var reload = context.computeOnClient(client -> {
            client.getLanguageManager().setSelected(language);
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 400);
    }

    private static BufferedImage capture(ClientGameTestContext context, DebuggerPreferences preferences, int request, String label) {
        context.runOnClient(client -> {
            preferences.setCustomUiScale(request);
            client.setScreenAndShow(new SampleScreen(preferences));
        });
        context.waitTicks(3);
        Path screenshot = context.takeScreenshot("codon-font-" + label + "-" + request);
        try { return ImageIO.read(screenshot.toFile()); }
        catch (IOException exception) { throw new AssertionError("Cannot read native font pixels", exception); }
    }

    private static void checkCoverage(BufferedImage reference, BufferedImage sample, double scale) {
        // Integrate the complete native 2x font into each destination pixel. This
        // reference is independent of the sampler implementation and includes all strokes.
        for (int top : new int[]{30, 55, 80, 105, 130, 155, 185, 205, 225}) {
            int occupied = 0, missing = 0, extra = 0;
            double error = 0;
            for (int y = (int) Math.floor(top * scale) - 1; y < Math.ceil((top + 10) * scale) + 1; y++) {
                for (int x = (int) (20 * scale) - 1; x < Math.ceil(300 * scale); x++) {
                    double expected = area(reference, x * 2 / scale, y * 2 / scale, (x + 1) * 2 / scale, (y + 1) * 2 / scale);
                    int actual = sample.getRGB(x, y) & 255;
                    if (expected == 0 && actual > 20) extra++;
                    if (expected < 80) continue;
                    occupied++;
                    if (actual < 20) missing++;
                    error += Math.abs(expected - actual);
                }
            }
            require(occupied > 100, "Font reference must contain meaningful glyph evidence");
            double missingFraction = missing / (double) occupied;
            double meanError = error / occupied;
            System.out.printf("Font coverage scale=%.2f row=%d missing=%.4f meanError=%.2f extra=%d%n", scale, top, missingFraction, meanError, extra);
            require(missingFraction < 0.08 && meanError < 55,
                "Glyph strokes/contrast lost at " + scale + " row " + top + ": missing=" + missingFraction + ", error=" + meanError);
            require(extra == 0, "Extra ink outside native reference at " + scale + " row " + top + ": " + extra);
        }
    }

    private static double area(BufferedImage image, double left, double top, double right, double bottom) {
        double sum = 0;
        for (int y = (int) Math.floor(top); y < Math.ceil(bottom); y++) {
            for (int x = (int) Math.floor(left); x < Math.ceil(right); x++) {
                double weight = (Math.min(x + 1, right) - Math.max(x, left)) * (Math.min(y + 1, bottom) - Math.max(y, top));
                sum += (image.getRGB(x, y) & 255) * weight;
            }
        }
        return sum / ((right - left) * (bottom - top));
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class SampleScreen extends ScaledCodonScreen {
        SampleScreen(DebuggerPreferences preferences) { super(Component.literal("Font sampling"), preferences); }

        @Override protected void init() {
            var button = new DebuggerButton();
            button.configure(15, 179, 290, 20, Component.literal(SAMPLE), true, false, true, false, () -> { });
            addRenderableWidget(button);
            var field = new EditBox(font, 20, 205, 280, 12, Component.literal("Font field"));
            field.setBordered(false);
            field.setValue(SAMPLE);
            field.setTextColor(0xffffffff);
            addRenderableWidget(field);
        }

        @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            graphics.fill(0, 0, width, height, 0xff000000);
            graphics.text(font, SAMPLE, 20, 30, 0xffffffff, false);
            graphics.text(font, Component.literal(SAMPLE), 20, 55, 0xffffffff, false);
            graphics.text(font, Component.literal(SAMPLE).getVisualOrderText(), 20, 80, 0xffffffff, false);
            graphics.text(font, "Running status / View / Help 0123456789", 20, 105, 0xffffffff, false);
            graphics.textRenderer().accept(TextAlignment.LEFT, 20, 130, Component.literal(SAMPLE));
            graphics.text(font, SAMPLE, 20, 155, 0xffffffff, true);
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            graphics.enableScissor(20, 225, 160, 235);
            graphics.text(font, SAMPLE, 20, 225, 0xffffffff, false);
            graphics.disableScissor();
            graphics.setTooltipForNextFrame(font, Component.literal(SAMPLE), 20, 260);
            var client = Minecraft.getInstance();
            new GuiGraphicsExtractor(client, graphics.guiRenderState, -1, -1).text(font, SAMPLE, 20, 360, 0xffffffff, false);
        }
    }
}
