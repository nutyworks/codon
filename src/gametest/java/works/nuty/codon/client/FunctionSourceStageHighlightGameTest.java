package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.InputType;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.state.ClientFunctionSourceState;
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.client.state.DebuggerPreferences;
import works.nuty.codon.client.ui.FunctionSourceScreen;
import works.nuty.codon.client.ui.ScaledCodonScreen;
import works.nuty.codon.core.model.*;

/** Selection must leave native source glyph pixels and original stage hit targets intact. */
@SuppressWarnings("UnstableApiUsage")
public final class FunctionSourceStageHighlightGameTest implements FabricClientGameTest {
    private static final FunctionId FUNCTION = new FunctionId("codon_test", "stage_highlight");
    private static final String COMMAND = "execute as @s at @s if score @s charge matches 40.. run say \""
        + "readable_adjacent_stage_".repeat(15) + "\"";
    private static final SourceLocation.Function LOCATION = new SourceLocation.Function(new FunctionLocation(FUNCTION, 2));
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
                client.setScreenAndShow(new FunctionSourceScreen(new ScaledCodonScreen(Component.empty(), preferences) { }, sources));
            });
            context.waitTicks(2); // Let the absent fixture's initial source-preview reply settle.
            context.runOnClient(client -> {
                var previews = CodonClientMod.state().stagePreviews();
                int run = COMMAND.indexOf("run say");
                require(previews.accept(previews.begin(LOCATION), LOCATION, ClientStagePreviewState.Status.READY, COMMAND,
                    List.of(new ClientStagePreviewState.StageSpan(0, 0, 13, false),
                        new ClientStagePreviewState.StageSpan(1, 14, 19, false),
                        new ClientStagePreviewState.StageSpan(2, 20, run - 1, true),
                        new ClientStagePreviewState.StageSpan(3, run, COMMAND.length(), false))), "fixture spans accepted");
            });
            // Representative minimum/maximum and fractional cases, without a Cartesian matrix.
            for (Scenario scenario : List.of(
                new Scenario(4, 0, "minimum-adjacent", List.of(0, 1, 2)),
                new Scenario(5, 10, "fractional-left-right-clipped", List.of(2, 3)),
                new Scenario(9, 10, "fractional-left-right-clipped", List.of(2, 3)),
                new Scenario(18, 0, "maximum-adjacent", List.of(0, 1, 2)),
                new Scenario(18, 1000, "maximum-tail", List.of(3)))) {
                preferences.setCustomUiScale(scenario.scale);
                context.getInput().setCursorPos(0, 0);
                context.runOnClient(client -> {
                    sources.rememberBrowseView(0, 0, 2, -1, 0, 0);
                    client.setScreenAndShow(new FunctionSourceScreen(new ScaledCodonScreen(Component.empty(), preferences) { }, sources));
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
        } finally {
            context.runOnClient(client -> {
                client.setScreenAndShow(null);
                CodonClientMod.state().applyResume();
                client.options.guiScale().set(oldScale);
                client.resizeGui();
            });
        }
    }

    private static void checkSelection(ClientGameTestContext context, String label, List<Integer> stages) {
        // Opening a screen can restore the pointer inside Find even if it was moved before init.
        context.getInput().setCursorPos(0, 0);
        context.runOnClient(client -> {
            // New compact screens may focus Find and display its tooltip over the code.
            // Compare the same unfocused-code state that native stage clicks leave behind.
            client.setLastInputType(InputType.MOUSE);
            client.gui.screen().setFocused(null);
            set(client.gui.screen(), "selectedStageIndex", -1);
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            var find = (AbstractWidget) field(client.gui.screen(), "sourceSearch");
            require(!find.isHovered() && !find.isFocused(), "reference Find has neither pointer nor keyboard focus");
        });
        List<Hit> before = context.computeOnClient(client -> hits(client.gui.screen()));
        require(before.stream().anyMatch(value -> !value.control && stages.contains(value.target.stageIndex())), label + " viewport contains a tested stage");
        BufferedImage reference = capture(context, label + "-reference");
        for (int stage : stages) {
            Hit hit = before.stream().filter(value -> !value.control && value.target.stageIndex() == stage).findFirst().orElse(null);
            if (hit == null) continue; // A stage fully outside this viewport has no clickable region.
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
                    require(integer(client.gui.screen(), "selectedStageIndex") == stage, "native click selects original stage " + stage);
                    require(hits(client.gui.screen()).equals(before), "selection preserves every stage/marker hitbox");
                    var definition = CodonClientMod.state().breakpoints().get(hit.target);
                    require(definition != null && definition.enabled()
                        && definition.condition().equals(BreakpointCondition.event(BreakpointCondition.Kind.CREATED))
                        && !CodonClientMod.state().breakpoints().pending(hit.target), "text selection preserves breakpoint identity and condition");
                });
            }
            BufferedImage selected = capture(context, label + "-stage-" + stage);
            double scale = context.computeOnClient(client -> ((ScaledCodonScreen) client.gui.screen()).uiScale().effective());
            int[] viewport = context.computeOnClient(client -> new int[]{invoke(client.gui.screen(), "sourceLeft") + invoke(client.gui.screen(), "gutterWidth"),
                invoke(client.gui.screen(), "codeRight")});
            int fromX = (int) Math.floor(viewport[0] * scale), toX = (int) Math.ceil(viewport[1] * scale);
            int fromY = (int) Math.floor((hit.y + 4) * scale), toY = (int) Math.floor((hit.y + 15) * scale);
            int ink = 0;
            // Includes antialiasing room, syntax colors, adjacent text and both clipped edges.
            for (int y = fromY; y < toY; y++)
                for (int x = fromX; x < toX; x++) {
                    int pixel = reference.getRGB(x, y);
                    if ((pixel & 0xffffff) > 0x606060) ink++;
                }
            int changed = changedPixels(reference, selected, fromX, toX, fromY, toY);
            require(ink > 20, "reference contains visible source glyphs");
            require(changed == 0, label + " stage " + stage + " selection altered " + changed + " native glyph-band pixels");
            int highlight = 0;
            for (int row : new int[]{2, 15})
                for (int y = (int) Math.ceil((hit.y + row) * scale); y < (int) Math.ceil((hit.y + row + 1) * scale); y++)
                    for (int x = (int) Math.ceil((hit.x + 1) * scale); x < (int) Math.floor((hit.x + hit.width - 1) * scale); x++)
                        if (reference.getRGB(x, y) != selected.getRGB(x, y)) highlight++;
            require(highlight > 0, "selected stage retains a visible highlight outside the glyph band");
            require(sourcesText(context).equals(COMMAND), "source text and original offsets remain unchanged");
        }
    }

    private static int changedPixels(BufferedImage reference, BufferedImage selected, int fromX, int toX, int fromY, int toY) {
        int changed = 0;
        for (int y = fromY; y < toY; y++)
            for (int x = fromX; x < toX; x++)
                if (reference.getRGB(x, y) != selected.getRGB(x, y)) changed++;
        return changed;
    }

    private static void checkEdgeCoverage() {
        // A one-pixel side stroke at either clipping edge must fail this same comparator.
        for (double scale : new double[]{1, 1.25, 2.25, 4.5}) {
            int from = (int) Math.floor(3 * scale), to = (int) Math.ceil(11 * scale);
            var reference = new BufferedImage(64, 4, BufferedImage.TYPE_INT_ARGB);
            var selected = new BufferedImage(64, 4, BufferedImage.TYPE_INT_ARGB);
            require(changedPixels(reference, selected, from, to, 0, 4) == 0, "identical edge pixels pass");
            for (int edge : new int[]{from, to - 1}) {
                selected.setRGB(edge, 1, 0xff75dfd6);
                require(changedPixels(reference, selected, from, to, 0, 4) == 1, "clipped edge pixel mutation is detected at " + scale);
                selected.setRGB(edge, 1, 0);
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
