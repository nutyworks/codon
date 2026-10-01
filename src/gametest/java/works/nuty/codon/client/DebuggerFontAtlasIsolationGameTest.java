package works.nuty.codon.client;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.font.FontTexture;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import net.minecraft.client.renderer.state.gui.GlyphRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import works.nuty.codon.client.state.DebuggerPreferences;
import works.nuty.codon.client.ui.CodonGuiGraphics;
import works.nuty.codon.client.ui.ScaledCodonScreen;

/** Native framebuffer proof that real atlas neighbors cannot contribute extra ink. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerFontAtlasIsolationGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        int oldScale = context.computeOnClient(client -> client.options.guiScale().get());
        var preferences = new DebuggerPreferences();
        preferences.selectCustomUiScale(2);
        try {
            context.getInput().resizeWindow(1280, 800);
            context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeGui(); });
            for (boolean colored : new boolean[]{true, false}) {
                var atlases = context.computeOnClient(client -> createAtlases(colored));
                try {
                    // 1.25 first: the established allocator/phase counterexample, now rendered natively.
                    for (int request : colored ? new int[]{5, 4, 6, 7, 9} : new int[]{5}) {
                        BufferedImage isolated = capture(context, preferences, request, atlases.isolatedGlyph, "isolated", colored);
                        BufferedImage neighbor = capture(context, preferences, request, atlases.neighborGlyph, "neighbor", colored);
                        int extra = 0, largestDifference = 0, occupied = 0;
                        double scale = request / 4.0;
                        for (int y = 0; y < Math.ceil(13 * scale); y++) for (int x = (int) (18 * scale); x < Math.ceil(30 * scale); x++) {
                            int expected = isolated.getRGB(x, y) & 255, actual = neighbor.getRGB(x, y) & 255;
                            if (expected > 20) occupied++;
                            if (expected == 0 && actual > 2) extra++;
                            largestDifference = Math.max(largestDifference, Math.abs(actual - expected));
                        }
                        int knownPixel = neighbor.getRGB(28, 3) & 255;
                        System.out.printf("Atlas isolation colored=%s scale=%.2f extra=%d maxDifference=%d knownPixel=%d%n", colored, scale, extra, largestDifference, knownPixel);
                        require(occupied > 10, "Native isolated glyph must contain ink");
                        require(extra == 0 && largestDifference <= 2, "Adjacent atlas glyph contributed pixels at " + scale);
                        if (request == 5) require((isolated.getRGB(28, 3) & 255) == 0 && knownPixel == 0,
                            "1.25 counterexample pixel must remain black");
                    }
                } finally {
                    context.runOnClient(client -> { client.setScreenAndShow(null); atlases.isolated.close(); atlases.neighbor.close(); });
                }
            }
        } finally {
            context.runOnClient(client -> { client.setScreenAndShow(null); client.options.guiScale().set(oldScale); client.resizeGui(); });
        }
    }

    private static Atlases createAtlases(boolean colored) {
        var id = Identifier.fromNamespaceAndPath("codon", "font/atlas_isolation_fixture");
        var types = colored ? GlyphRenderTypes.createForColorTexture(id) : GlyphRenderTypes.createForGrayscaleTexture(id);
        var isolated = new FontTexture(() -> "Codon isolated glyph fixture", types, colored);
        var neighbor = new FontTexture(() -> "Codon neighboring glyph fixture", types, colored);
        try {
            Map<Integer, String> hex = new HashMap<>();
            try (var zip = new ZipInputStream(net.minecraft.client.Minecraft.getInstance().getResourceManager().open(Identifier.withDefaultNamespace("font/unifont.zip")))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.getName().endsWith(".hex")) continue;
                    new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines()
                        .filter(line -> line.startsWith("C548:") || line.startsWith("AC00:"))
                        .forEach(line -> hex.put(Integer.parseInt(line.substring(0, 4), 16), line.substring(5)));
                }
            }
            require(hex.size() == 2 && hex.values().stream().allMatch(value -> value.length() == 64), "Default Korean glyph source must be present");
            GlyphInfo info = () -> 8;
            var aloneBitmap = new Bitmap(hex.get(0xAC00), colored);
            var priorBitmap = new Bitmap(hex.get(0xC548), colored);
            var neighborBitmap = new Bitmap(hex.get(0xAC00), colored);
            var aloneGlyph = isolated.add(info, aloneBitmap);
            // Also exercise signed packed bounds near the bottom of the atlas on grayscale text.
            int preceding = colored ? 0 : 13;
            for (int i = 0; i < preceding; i++) neighbor.add(info, new Bitmap(hex.get(0xAC00), colored));
            neighbor.add(info, priorBitmap);
            var neighborGlyph = neighbor.add(info, neighborBitmap);
            require(aloneBitmap.x == 0 && aloneBitmap.y == 0 && priorBitmap.x == 0
                && priorBitmap.y == preceding * 17 && neighborBitmap.x == 0 && neighborBitmap.y == (preceding + 1) * 17,
                "Real FontTexture allocator must retain the one-texel counterexample gap");
            return new Atlases(isolated, neighbor, aloneGlyph, neighborGlyph);
        } catch (IOException | RuntimeException failure) {
            isolated.close(); neighbor.close();
            throw new AssertionError("Cannot create native atlas fixtures", failure);
        }
    }

    private static BufferedImage capture(ClientGameTestContext context, DebuggerPreferences preferences, int request, BakedSheetGlyph glyph, String label, boolean colored) {
        context.runOnClient(client -> { preferences.setCustomUiScale(request); client.setScreenAndShow(new AtlasScreen(preferences, glyph)); });
        context.waitTicks(3);
        Path path = context.takeScreenshot("codon-atlas-" + colored + "-" + label + "-" + request);
        try { return ImageIO.read(path.toFile()); }
        catch (IOException failure) { throw new AssertionError(failure); }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private record Atlases(FontTexture isolated, FontTexture neighbor, BakedSheetGlyph isolatedGlyph, BakedSheetGlyph neighborGlyph) { }

    private static final class Bitmap implements GlyphBitmap {
        private final String hex;
        private final boolean colored;
        int x, y;
        Bitmap(String hex, boolean colored) { this.hex = hex; this.colored = colored; }
        @Override public int getPixelWidth() { return 15; }
        @Override public int getPixelHeight() { return 16; }
        @Override public float getOversample() { return 2; }
        @Override public boolean isColored() { return colored; }
        @Override public void upload(int x, int y, GpuTexture texture) {
            this.x = x; this.y = y;
            ByteBuffer data = ByteBuffer.allocateDirect(15 * 16 * (colored ? 4 : 1));
            for (int row = 0; row < 16; row++) {
                int bits = Integer.parseInt(hex.substring(row * 4, row * 4 + 4), 16);
                for (int column = 1; column <= 15; column++) {
                    int value = (bits & 1 << (15 - column)) != 0 ? 255 : 0;
                    if (colored) data.put((byte) value).put((byte) value).put((byte) value);
                    data.put((byte) value);
                }
            }
            data.flip();
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, data, 0, 0, x, y, 15, 16);
        }
    }

    private static final class AtlasScreen extends ScaledCodonScreen {
        private final BakedSheetGlyph glyph;
        AtlasScreen(DebuggerPreferences preferences, BakedSheetGlyph glyph) { super(Component.literal("Atlas isolation"), preferences); this.glyph = glyph; }
        @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            graphics.fill(0, 0, width, height, 0xff000000);
            var pose = ((CodonGuiGraphics) graphics).textPose(graphics.pose());
            graphics.guiRenderState.up();
            graphics.guiRenderState.addGlyphToCurrentLayer(new GlyphRenderState(pose, glyph.createGlyph(20, 3, 0xffffffff, 0, Style.EMPTY, 0, 0), null));
        }
    }
}
