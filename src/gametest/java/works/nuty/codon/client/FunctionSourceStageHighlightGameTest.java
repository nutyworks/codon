package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.InputType;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2f;
import works.nuty.codon.client.state.ClientFunctionSourceState;
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.client.state.DebuggerPreferences;
import works.nuty.codon.client.ui.FunctionSourceScreen;
import works.nuty.codon.client.ui.DebuggerTheme;
import works.nuty.codon.client.ui.ScaledCodonScreen;
import works.nuty.codon.core.model.*;

/** Background highlighting must preserve source glyph colors and original stage hit targets. */
@SuppressWarnings("UnstableApiUsage")
public final class FunctionSourceStageHighlightGameTest implements FabricClientGameTest {
    private static final FunctionId FUNCTION = new FunctionId("codon_test", "stage_highlight");
    private static final String COMMAND = "execute as @s at @s if score @s charge matches 40.. run say \""
        + "readable_adjacent_stage_".repeat(15) + "\"";
    private static final SourceLocation.Function LOCATION = new SourceLocation.Function(new FunctionLocation(FUNCTION, 2));
    private static final int[] SOURCE_COLORS = {DebuggerTheme.TEXT, DebuggerTheme.TEAL, DebuggerTheme.PURPLE,
        DebuggerTheme.GREEN, DebuggerTheme.AMBER, DebuggerTheme.MUTED, 0xffb3d5ff};
    private static final int SELECTED_TINT = 0x5075dfd6, STOPPED_TINT = 0x70f3c171;
    private record Hit(int x, int y, int width, int height, BreakpointTarget target, boolean control) { }
    private record Scenario(int scale, int scroll, String name, List<Integer> stages) { }

    @Override public void runTest(ClientGameTestContext context) {
        checkEdgeCoverage();
        int oldScale = context.computeOnClient(client -> client.options.guiScale().get());
        try (var world = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1920, 1080);
            context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeGui(); });
            world.getConnection().waitForChunksRender();
            var sources = source();
            var preferences = new DebuggerPreferences();
            preferences.selectCustomUiScale(1);
            context.runOnClient(client -> {
                var state = CodonClientMod.state();
                state.applyResume();
                state.breakpoints().reset();
                state.breakpoints().acceptPage(1, 0, true, java.util.stream.IntStream.range(0, 4)
                    .mapToObj(stage -> BreakpointDefinition.plain(BreakpointTarget.stage(LOCATION, stage, COMMAND))
                        .withCondition(BreakpointCondition.event(BreakpointCondition.Kind.CREATED))).toList());
                openSource(client, preferences, sources);
            });
            context.waitTicks(2); // Let the absent fixture's initial source-preview reply settle.
            context.runOnClient(client -> installPreview());
            // Representative minimum/maximum and fractional cases, without a Cartesian matrix.
            for (Scenario scenario : List.of(
                new Scenario(4, 0, "minimum-adjacent", List.of(0, 1)),
                new Scenario(5, 10, "fractional-left-right-clipped", List.of(2, 3)),
                new Scenario(9, 10, "fractional-left-right-clipped", List.of(2, 3)),
                new Scenario(18, 0, "maximum-adjacent", List.of(0, 1, 2)),
                new Scenario(18, 1000, "maximum-tail", List.of(3)))) {
                preferences.setCustomUiScale(scenario.scale);
                context.getInput().setCursorPos(0, 0);
                context.runOnClient(client -> {
                    sources.rememberBrowseView(0, 0, -1, -1, 0, 0);
                    openSource(client, preferences, sources);
                });
                context.waitTicks(2);
                require(context.computeOnClient(client -> ((ScaledCodonScreen) client.gui.screen()).uiScale().effective()) == scenario.scale / 4.0,
                    "requested scale is applied");
                if (scenario.scroll != 0) {
                    // Cut through text, rather than aligning the viewport with a stage boundary.
                    context.runOnClient(client -> {
                        var screen = client.gui.screen();
                        screen.mouseScrolled(invoke(screen, "codeRight") - 10, invoke(screen, "sourceLineTop") + 27, scenario.scroll, 0);
                    });
                    context.waitTicks(2);
                }
                checkSelection(context, scenario.name + "-" + scenario.scale, scenario.stages);
            }
            preferences.setCustomUiScale(5);
            context.runOnClient(client -> {
                sources.rememberBrowseView(0, 0, -1, -1, 0, 0);
                openSource(client, preferences, sources);
            });
            context.waitTicks(2);
            context.runOnClient(client -> {
                var screen = client.gui.screen();
                screen.mouseScrolled(invoke(screen, "codeRight") - 10, invoke(screen, "sourceLineTop") + 27, 10, 0);
            });
            checkPauseStates(context);
            checkReloadSelection(context, sources);
        } finally {
            context.runOnClient(client -> {
                client.setScreenAndShow(null);
                CodonClientMod.state().applyResume();
                client.options.guiScale().set(oldScale);
                client.resizeGui();
            });
        }
    }

    private static void openSource(Minecraft client, DebuggerPreferences preferences, ClientFunctionSourceState sources) {
        var screen = new FunctionSourceScreen(new ScaledCodonScreen(Component.empty(), preferences) { }, sources);
        client.setScreenAndShow(screen);
        // Keep the entire pixel oracle, including the column outside the rounded scissor.
        // That column exposes the translucent panel, so an animated world is not a stable reference.
        ScreenEvents.beforeExtract(screen).register((current, graphics, mouseX, mouseY, delta) ->
            graphics.fill(0, 0, Math.max(current.width, graphics.guiWidth()),
                Math.max(current.height, graphics.guiHeight()), DebuggerTheme.SURFACE));
    }

    private static void prepareReference(ClientGameTestContext context) {
        // Screen init can restore the pointer inside Find; its tooltip must not cover code.
        context.getInput().setCursorPos(0, 0);
        context.runOnClient(client -> {
            client.setLastInputType(InputType.MOUSE);
            client.gui.screen().setFocused(null);
            set(client.gui.screen(), "selectedLine", -1);
            set(client.gui.screen(), "selectedStageIndex", -1);
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            var find = (AbstractWidget) field(client.gui.screen(), "sourceSearch");
            require(!find.isHovered() && !find.isFocused(), "reference Find has neither pointer nor keyboard focus");
        });
    }

    private static void checkSelection(ClientGameTestContext context, String label, List<Integer> stages) {
        prepareReference(context);
        List<Hit> before = context.computeOnClient(client -> hits(client.gui.screen()));
        require(before.stream().anyMatch(value -> !value.control && stages.contains(value.target.stageIndex())), label + " viewport contains a tested stage");
        BufferedImage reference = capture(context, label + "-reference");
        for (int stage : stages) {
            Hit hit = before.stream().filter(value -> !value.control && value.target.stageIndex() == stage).findFirst()
                .orElseThrow(() -> new AssertionError(label + " is missing expected visible stage " + stage));
            selectStage(context, hit, before);
            BufferedImage selected = capture(context, label + "-stage-" + stage);
            assertBackground(context, reference, selected, hit, SELECTED_TINT, label + " stage " + stage);
            int background = backgroundPixel(context, selected, hit);
            require((background >> 8 & 255) > (background >> 16 & 255), "manual selection has a distinct teal background");
        }
    }

    private static void selectStage(ClientGameTestContext context, Hit hit, List<Hit> before) {
        for (boolean farEdge : new boolean[]{false, true}) {
            double[] point = context.computeOnClient(client -> {
                var screen = (ScaledCodonScreen) client.gui.screen();
                var window = client.getWindow();
                return new double[]{screen.uiScale().toGame(hit.x + (farEdge ? hit.width - .25 : .25))
                    * window.getScreenWidth() / window.getGuiScaledWidth(),
                    screen.uiScale().toGame(hit.y + hit.height / 2.0) * window.getScreenHeight() / window.getGuiScaledHeight()};
            });
            context.getInput().setCursorPos(point[0], point[1]);
            context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
            context.getInput().setCursorPos(0, 0);
            context.waitTicks(2);
            context.runOnClient(client -> {
                require(integer(client.gui.screen(), "selectedStageIndex") == hit.target.stageIndex(), "native click selects original stage " + hit.target.stageIndex());
                require(hits(client.gui.screen()).equals(before), "selection preserves every stage/marker hitbox");
                var definition = CodonClientMod.state().breakpoints().get(hit.target);
                require(definition != null && definition.enabled()
                    && definition.condition().equals(BreakpointCondition.event(BreakpointCondition.Kind.CREATED))
                    && !CodonClientMod.state().breakpoints().pending(hit.target), "text selection preserves breakpoint identity and condition");
            });
        }
    }

    private static int assertBackground(ClientGameTestContext context, BufferedImage reference, BufferedImage selected, Hit hit,
                                        int tint, String label) {
        double scale = scale(context);
        int[] viewport = context.computeOnClient(client -> new int[]{invoke(client.gui.screen(), "sourceLeft") + invoke(client.gui.screen(), "gutterWidth"),
            invoke(client.gui.screen(), "codeRight")});
        int fromX = (int) Math.floor(viewport[0] * scale), toX = (int) Math.ceil(viewport[1] * scale);
        int fromY = (int) Math.floor((hit.y + 4) * scale), toY = (int) Math.floor((hit.y + 15) * scale);
        double[] clip = context.computeOnClient(client -> {
            var screen = (ScaledCodonScreen) client.gui.screen();
            var uiScale = screen.uiScale();
            var pose = new Matrix3x2f().scale((float) uiScale.renderFactor());
            int left = invoke(screen, "sourceLeft") + 3, top = invoke(screen, "sourceLineTop");
            var outer = new ScreenRectangle(left, top, viewport[1] - left, invoke(screen, "sourceRows") * 18).transformAxisAligned(pose);
            var row = new ScreenRectangle(viewport[0], hit.y, viewport[1] - viewport[0], hit.height).transformAxisAligned(pose);
            var visible = row.intersection(outer);
            return new double[]{visible.left() * uiScale.gameScale(), visible.right() * uiScale.gameScale()};
        });
        int background = backgroundPixel(context, selected, hit);
        int referenceBackground = backgroundPixel(context, reference, hit);
        int ink = 0;
        for (int y = fromY; y < toY; y++)
            for (int x = fromX; x < toX; x++)
                if (isInk(reference.getRGB(x, y))) ink++;
        int unexpected = unexpectedPixels(reference, selected, fromX, toX, fromY, toY,
            Math.max(hit.x * scale, clip[0]), Math.min((hit.x + hit.width) * scale, clip[1]), referenceBackground, background);
        require(ink > 20, "reference contains visible source glyphs");
        require(unexpected == 0, label + " altered " + unexpected + " glyph, neighboring or clipped-edge pixels");
        require(matchesBlend(background, tint, referenceBackground, tint >>> 24),
            label + " uses the expected background hue and opacity");
        int contrast = colorDistance(referenceBackground, background);
        require(contrast >= 120, "stage background is clearly distinct from the row behind it");
        require(sourcesText(context).equals(COMMAND), "source text and original offsets remain unchanged");
        return contrast;
    }

    private static void checkPauseStates(ClientGameTestContext context) {
        var definitions = context.computeOnClient(client -> CodonClientMod.state().breakpoints().definitions());
        context.runOnClient(client -> {
            var state = CodonClientMod.state();
            state.breakpoints().reset();
            state.stagePreviews().reset();
            state.applyPause(pause(2, COMMAND));
        });
        prepareReference(context);
        context.runOnClient(client -> {
            var state = CodonClientMod.state();
            require(state.stagePreviews().get(LOCATION) != null,
                "an unselected live stop requests its preview without hover or enabled breakpoints");
            require(state.breakpoints().acceptPage(1, 0, true, definitions), "restore acknowledged stage definitions");
            installPreview();
            state.applyPause(pause(-1, COMMAND));
        });
        context.waitTicks(2);
        List<Hit> before = context.computeOnClient(client -> hits(client.gui.screen()));
        Hit stopped = before.stream().filter(hit -> !hit.control && hit.target.stageIndex() == 2).findFirst().orElseThrow();
        Hit inspected = before.stream().filter(hit -> !hit.control && hit.target.stageIndex() == 3).findFirst().orElseThrow();
        BufferedImage unknown = capture(context, "pause-unknown-stage-reference");
        require(backgroundPixel(context, unknown, stopped) == rowPaddingPixel(context, unknown, stopped),
            "a pause without an authoritative stage does not guess a stopped stage");
        selectStage(context, inspected, before);
        BufferedImage selected = capture(context, "pause-unknown-stage-selected-3");
        int selectedContrast = assertBackground(context, unknown, selected, inspected, SELECTED_TINT, "paused manual selection");
        context.runOnClient(client -> {
            var state = CodonClientMod.state();
            state.applyPause(pause(2, COMMAND));
            state.selectFrame(1);
            require(state.selectedFrameIndex() == 1, "fixture inspects another frame while the top frame remains paused");
        });
        context.waitTicks(2);
        BufferedImage actual = capture(context, "pause-actual-stage-2-other-frame-selected-3");
        int pausedContrast = assertBackground(context, selected, actual, stopped, STOPPED_TINT, "authoritative stopped stage");
        int background = backgroundPixel(context, actual, stopped);
        require((background >> 16 & 255) > (background >> 8 & 255), "actual stop retains amber semantics");
        require(pausedContrast > selectedContrast, "actual stopped stage is stronger than manual selection");
        context.runOnClient(client -> CodonClientMod.state().applyPause(pause(2, COMMAND + " changed")));
        context.waitTicks(2);
        BufferedImage stale = capture(context, "pause-stale-command-selected-3");
        requireSameRow(context, selected, stale, stopped, "a stale command cannot highlight a current source stage");
        context.runOnClient(client -> CodonClientMod.state().applyPause(pause(2, COMMAND, PauseReason.EXECUTION_COMPLETE)));
        context.waitTicks(2);
        BufferedImage complete = capture(context, "execution-complete-selected-3");
        requireSameRow(context, selected, complete, stopped, "execution completion does not highlight the retained final stage as live");
        context.runOnClient(client -> CodonClientMod.state().applyResume());
        context.waitTicks(2);
        BufferedImage resumed = capture(context, "resumed-selected-3");
        require(backgroundPixel(context, resumed, stopped) == rowPaddingPixel(context, resumed, stopped),
            "resume clears actual stopped stage highlighting while retaining manual selection");
        require(hitsFromContext(context).equals(before), "pause, frame inspection and resume preserve stage hitboxes");
    }

    private static PauseSnapshot pause(int stage, String command) {
        return pause(stage, command, PauseReason.BREAKPOINT);
    }

    private static PauseSnapshot pause(int stage, String command, PauseReason reason) {
        var snippet = CommandSnippet.plain(command);
        return new PauseSnapshot(LOCATION, snippet, 0, List.of(new CallFrame(0, LOCATION, snippet, 41, stage),
            new CallFrame(1, LOCATION, snippet, 42, 3)), List.of(), reason);
    }

    private static void checkReloadSelection(ClientGameTestContext context, ClientFunctionSourceState sources) {
        context.runOnClient(client -> {
            sources.drainRequests();
            sources.select(FUNCTION);
            long request = sources.drainRequests().getFirst().requestId();
            sources.accept(new ClientFunctionSourceState.SourcePage(request, ClientFunctionSourceState.Status.READY,
                FUNCTION, "gametest", "reloaded-single-stage", false, 0, true,
                List.of("# reloaded", "say reloaded", "say below")));
        });
        context.waitTicks(2);
        BufferedImage reloaded = capture(context, "reload-single-stage-selection");
        double scale = scale(context);
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            require(integer(screen, "selectedLine") == 2 && integer(screen, "selectedStageIndex") == 3,
                "reload fixture preserves the now-unresolved selected stage");
            require(hits(screen).isEmpty(), "single-stage replacement has no inline stage targets");
            int x = invoke(screen, "sourceLeft") + invoke(screen, "gutterWidth") + 2;
            int y = invoke(screen, "sourceLineTop") + 18 + 2;
            require(reloaded.getRGB((int) Math.ceil(x * scale), (int) Math.ceil(y * scale)) == DebuggerTheme.TEAL_SURFACE,
                "an unresolved stage selection falls back to the visible selected row");
        });
    }

    private static void installPreview() {
        var previews = CodonClientMod.state().stagePreviews();
        int run = COMMAND.indexOf("run say");
        require(previews.accept(previews.begin(LOCATION), LOCATION, ClientStagePreviewState.Status.READY, COMMAND,
            List.of(new ClientStagePreviewState.StageSpan(0, 0, 13, false),
                new ClientStagePreviewState.StageSpan(1, 14, 19, false),
                new ClientStagePreviewState.StageSpan(2, 20, run - 1, true),
                new ClientStagePreviewState.StageSpan(3, run, COMMAND.length(), false))), "fixture spans accepted");
    }

    private static List<Hit> hitsFromContext(ClientGameTestContext context) {
        return context.computeOnClient(client -> hits(client.gui.screen()));
    }
    private static double scale(ClientGameTestContext context) {
        return context.computeOnClient(client -> ((ScaledCodonScreen) client.gui.screen()).uiScale().effective());
    }
    private static int backgroundPixel(ClientGameTestContext context, BufferedImage image, Hit hit) {
        double scale = scale(context);
        return image.getRGB((int) ((hit.x + hit.width / 2.0) * scale), (int) Math.floor((hit.y + 3) * scale) + 1);
    }
    private static int rowPaddingPixel(ClientGameTestContext context, BufferedImage image, Hit hit) {
        double scale = scale(context);
        return image.getRGB((int) ((hit.x + hit.width / 2.0) * scale), (int) Math.floor((hit.y + 1) * scale));
    }
    private static int colorDistance(int first, int second) {
        return Math.abs((first >> 16 & 255) - (second >> 16 & 255))
            + Math.abs((first >> 8 & 255) - (second >> 8 & 255)) + Math.abs((first & 255) - (second & 255));
    }
    private static boolean isInk(int pixel) { return (pixel & 0xffffff) > 0x606060; }

    private static void requireSameRow(ClientGameTestContext context, BufferedImage reference, BufferedImage selected, Hit hit, String message) {
        double scale = scale(context);
        int[] viewport = context.computeOnClient(client -> new int[]{invoke(client.gui.screen(), "sourceLeft") + invoke(client.gui.screen(), "gutterWidth"),
            invoke(client.gui.screen(), "codeRight")});
        require(changedPixels(reference, selected, (int) Math.floor(viewport[0] * scale), (int) Math.ceil(viewport[1] * scale),
            (int) Math.floor((hit.y + 4) * scale), (int) Math.floor((hit.y + 15) * scale)) == 0, message);
    }

    private static int changedPixels(BufferedImage reference, BufferedImage selected, int fromX, int toX, int fromY, int toY) {
        int changed = 0;
        for (int y = fromY; y < toY; y++)
            for (int x = fromX; x < toX; x++)
                if (reference.getRGB(x, y) != selected.getRGB(x, y)) changed++;
        return changed;
    }

    private static int unexpectedPixels(BufferedImage reference, BufferedImage selected, int fromX, int toX, int fromY, int toY,
                                        double backgroundFrom, double backgroundTo, int referenceBackground, int background) {
        int unexpected = 0;
        for (int y = fromY; y < toY; y++)
            for (int x = fromX; x < toX; x++) {
                int original = reference.getRGB(x, y), actual = selected.getRGB(x, y);
                if (x < Math.floor(backgroundFrom) || x >= Math.ceil(backgroundTo)) {
                    if (original != actual) unexpected++;
                } else if (original == actual && (x < Math.ceil(backgroundFrom) || x >= Math.floor(backgroundTo))) {
                    // A partially clipped framebuffer pixel may lie outside the rasterized fill.
                } else if (original == referenceBackground) {
                    if (actual != background) unexpected++;
                } else if (original != actual && !sameGlyphBlend(original, actual, referenceBackground, background)) unexpected++;
            }
        return unexpected;
    }

    private static boolean sameGlyphBlend(int original, int actual, int before, int after) {
        // Font atlas edge texels can have partial alpha. Preserve the same syntax color
        // and coverage over both backgrounds, allowing only 8-bit blend rounding.
        for (int foreground : SOURCE_COLORS)
            for (int alpha = 254; alpha > 0; alpha--)
                if (matchesBlend(original, foreground, before, alpha) && matchesBlend(actual, foreground, after, alpha)) return true;
        return false;
    }

    private static boolean matchesBlend(int pixel, int foreground, int background, int alpha) {
        for (int shift = 16; shift >= 0; shift -= 8) {
            double expected = ((foreground >> shift & 255) * alpha + (background >> shift & 255) * (255 - alpha)) / 255.0;
            if (Math.abs((pixel >> shift & 255) - expected) > 1) return false;
        }
        return true;
    }

    private static void checkEdgeCoverage() {
        // The allowed dark fill must never hide a one-pixel stroke over clipped glyphs or background.
        for (double scale : new double[]{1, 1.25, 2.25, 4.5}) {
            double start = 3 * scale, end = 11 * scale;
            int from = (int) Math.floor(start), to = (int) Math.ceil(end), before = 0xff172126, background = 0xff345d5d;
            int clippedEnd = to - 2; // Native scissor rounding can leave unchanged columns inside the logical viewport.
            var reference = new BufferedImage(64, 4, BufferedImage.TYPE_INT_ARGB);
            var selected = new BufferedImage(64, 4, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 4; y++) for (int x = from; x < to; x++) {
                reference.setRGB(x, y, before);
                selected.setRGB(x, y, x < clippedEnd ? background : before);
            }
            for (int edge : new int[]{from, clippedEnd - 1, to - 1}) {
                reference.setRGB(edge, 1, 0xffc7a0ff);
                selected.setRGB(edge, 1, 0xffc7a0ff);
            }
            reference.setRGB(from + 1, 0, 0xffc59efc);
            selected.setRGB(from + 1, 0, 0xffc59ffd);
            require(unexpectedPixels(reference, selected, from, to, 0, 4, start, clippedEnd, before, background) == 0,
                "background with intact opaque and partial-alpha glyphs passes");
            for (int edge : new int[]{from, clippedEnd - 1, to - 1}) for (int y : new int[]{1, 2}) {
                int original = selected.getRGB(edge, y);
                selected.setRGB(edge, y, 0xff75dfd6);
                require(unexpectedPixels(reference, selected, from, to, 0, 4, start, clippedEnd, before, background) == 1,
                    "clipped glyph/background edge mutation is detected at " + scale);
                selected.setRGB(edge, y, original);
            }
        }
    }

    private static String sourcesText(ClientGameTestContext context) {
        return context.computeOnClient(client -> ((ClientFunctionSourceState) field(client.gui.screen(), "sources")).document().lines().get(1));
    }
    private static BufferedImage capture(ClientGameTestContext context, String label) {
        try { return ImageIO.read(context.takeScreenshot("codon-stage-highlight-" + label).toFile()); }
        catch (IOException error) { throw new AssertionError(error); }
    }
    private static List<Hit> hits(Object screen) {
        return ((List<?>) field(screen, "stageHits")).stream().map(hit -> new Hit((int) accessor(hit, "x"), (int) accessor(hit, "y"),
            (int) accessor(hit, "width"), (int) accessor(hit, "height"), (BreakpointTarget) accessor(hit, "target"),
            (boolean) accessor(hit, "control"))).toList();
    }
    private static Object accessor(Object object, String name) {
        try { var method = object.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(object); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static int invoke(Object screen, String name) { return (int) accessor(screen, name); }
    private static Object field(Object screen, String name) {
        try { var field = screen.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(screen); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static int integer(Object screen, String name) { return (int) field(screen, name); }
    private static void set(Object screen, String name, int value) {
        try { var field = screen.getClass().getDeclaredField(name); field.setAccessible(true); field.setInt(screen, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static ClientFunctionSourceState source() {
        var state = new ClientFunctionSourceState();
        state.select(FUNCTION);
        long request = state.drainRequests().getFirst().requestId();
        state.accept(new ClientFunctionSourceState.SourcePage(request, ClientFunctionSourceState.Status.READY,
            FUNCTION, "gametest", "stage-highlight", false, 0, true, List.of("# adjacent stage glyphs", COMMAND, "say below")));
        state.rememberBrowseView(0, 0, 2, -1, 0);
        return state;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
