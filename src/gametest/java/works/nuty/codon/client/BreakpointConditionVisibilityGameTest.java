package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import works.nuty.codon.CodonMod;
import works.nuty.codon.client.network.ClientNetworking;
import works.nuty.codon.client.state.*;
import works.nuty.codon.client.ui.*;
import works.nuty.codon.core.model.*;

/** Native Source pixels, modal input and real server acknowledgements for line/stage/legacy Save. */
@SuppressWarnings("UnstableApiUsage")
public final class BreakpointConditionVisibilityGameTest implements FabricClientGameTest {
    private static final FunctionId FUNCTION = new FunctionId("codon_test", "condition_visibility");
    private static final List<String> LINES = List.of("# Condition editor visibility fixture", "# Rows stay below the condition form",
        "# Native client rendering and server edits", "# Each command is parsed by the real server",
        "say line_condition", "execute as @s run say stage_condition", "say legacy_condition", "say new_condition");

    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                server.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
                CodonMod.engine().clearBreakpoints();
            });
            context.getInput().resizeWindow(1800, 1100);
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                CodonClientMod.state().preferences().resetUiScale();
                client.resizeGui();
                var sources = new ClientFunctionSourceState();
                sources.select(FUNCTION);
                long request = sources.drainRequests().getFirst().requestId();
                sources.accept(new ClientFunctionSourceState.SourcePage(request, ClientFunctionSourceState.Status.READY,
                    FUNCTION, "gametest", "condition-visibility", false, 0, true, LINES));
                client.setScreenAndShow(new FunctionSourceScreen(new Screen(Component.empty()) { }, sources));
                for (int line = 5; line <= 8; line++) ClientNetworking.requestStagePreview(CodonClientMod.state(), location(line));
            });
            context.waitFor(client -> CodonClientMod.state().breakpoints().ready()
                && java.util.stream.IntStream.rangeClosed(5, 8).allMatch(line -> {
                    var preview = CodonClientMod.state().stagePreviews().get(location(line));
                    return preview != null && preview.status() == ClientStagePreviewState.Status.READY;
                }), 200);
            verify(context, world, 5, false, "line", true);
            verify(context, world, 6, true, "stage", true);
            verify(context, world, 7, true, "legacy-line", true);
            verify(context, world, 8, false, "new-line", true);
            String language = context.computeOnClient(client -> client.getLanguageManager().getSelected());
            var reload = context.computeOnClient(client -> {
                client.getLanguageManager().setSelected("ko_kr");
                CodonClientMod.state().preferences().setUiScaleMode(DebuggerPreferences.UiScaleMode.CUSTOM);
                CodonClientMod.state().preferences().setCustomUiScale(6);
                client.resizeGui();
                return client.reloadResourcePacks();
            });
            context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 200);
            verify(context, world, 6, true, "ko-custom-stage", false);
            verify(context, world, 7, true, "ko-custom-legacy-line", false);
            world.getServer().runOnServer(server -> CodonMod.engine().clearBreakpoints());
            var restore = context.computeOnClient(client -> {
                client.getLanguageManager().setSelected(language);
                CodonClientMod.state().preferences().resetUiScale();
                client.setScreenAndShow(null);
                return client.reloadResourcePacks();
            });
            context.waitFor(client -> restore.isDone() && client.gui.overlay() == null, 200);
        }
    }

    private static void verify(ClientGameTestContext context, TestSingleplayerContext world,
                               int line, boolean savedStage, String name, boolean save) {
        var target = savedStage ? BreakpointTarget.stage(location(line), 0, LINES.get(line - 1))
            : BreakpointTarget.whole(location(line));
        var definition = BreakpointDefinition.plain(target).withEnabled(false);
        if (line == 6) definition = definition.withCondition(BreakpointCondition.count(
            BreakpointCondition.Kind.INPUT_COUNT, BreakpointCondition.Comparison.EQ, 1));
        var savedDefinition = definition;
        if (line != 8) {
            world.getServer().runOnServer(server -> CodonMod.engine().saveBreakpoint(savedDefinition));
            context.waitFor(client -> savedDefinition.equals(CodonClientMod.state().breakpoints().get(target)), 200);
        }
        context.getInput().setCursorPos(0, 0);
        context.waitTicks(2);
        int[] point = context.computeOnClient(client -> {
            var screen = client.gui.screen();
            int x = FunctionLineBreakpointGameTest.value(screen, "lineMarkerX");
            if (line == 6) x = FunctionLineBreakpointGameTest.value(screen, "sourceLeft")
                + FunctionLineBreakpointGameTest.value(screen, "gutterWidth") + client.font.width("execute ") + 2;
            return new int[]{x, FunctionLineBreakpointGameTest.value(screen, "sourceLineTop") + (line - 1) * 18 + 5};
        });
        assertInk(context, point, name + "-inactive", false);
        move(context, point[0] + 3, point[1] + 3);
        context.waitTicks(2);
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            var event = new MouseButtonEvent(point[0] + 3, point[1] + 3, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0));
            require(screen.mouseClicked(event, false), "right click is consumed by Source");
            screen.mouseReleased(event);
            var layer = (BreakpointConditionScreen) ScreenLayers.get(screen);
            require(layer != null, "right click opens the condition editor for " + name);
            require(((BreakpointDefinition) FunctionLineBreakpointGameTest.field(layer, "original")).target().equals(target),
                "editor retains exact original identity for " + name);
            require(!CodonClientMod.state().breakpoints().pending(target), "Open does not edit or enable " + name);
        });
        context.getInput().setCursorPos(0, 0);
        context.waitTicks(3);
        assertInk(context, point, name + "-editing", true);
        if (line == 5) {
            int[] other = context.computeOnClient(client -> new int[]{point[0],
                FunctionLineBreakpointGameTest.value(client.gui.screen(), "sourceLineTop") + 7 * 18 + 5});
            move(context, other[0] + 3, other[1] + 3);
            context.waitTicks(2);
            assertInk(context, other, name + "-unrelated-hidden", false);
            context.getInput().setCursorPos(0, 0);
        }
        context.runOnClient(client -> {
            var layer = ScreenLayers.get(client.gui.screen());
            layer.setFocused((AbstractWidget) FunctionLineBreakpointGameTest.field(layer, "kindButton"));
            layer.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        });
        context.waitTicks(2);
        assertInk(context, point, name + "-menu", true);
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            var layer = ScreenLayers.get(screen);
            layer.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
            require(ScreenLayers.get(screen) == layer, "Escape first closes the menu");
            layer.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
            require(ScreenLayers.get(screen) == null, "Cancel closes only the editor");
            var current = CodonClientMod.state().breakpoints().get(target);
            require(line == 8 ? current == null : savedDefinition.equals(current), "Cancel preserves disabled/absent state for " + name);
            require(!CodonClientMod.state().breakpoints().pending(target), "Cancel sends no edit for " + name);
        });
        context.waitTicks(2);
        assertInk(context, point, name + "-cancelled", false);
        if (line == 6) {
            // Presentation-only pending edit; no server mutation is inferred from this fixture.
            long pending = context.computeOnClient(client -> CodonClientMod.state().breakpoints()
                .begin(ClientBreakpointState.Action.SAVE, savedDefinition).requestId());
            context.waitTicks(2);
            context.takeScreenshot("codon-condition-" + name + "-pending-stage-action");
            context.runOnClient(client -> {
                var screen = client.gui.screen();
                var action = (AbstractWidget) FunctionLineBreakpointGameTest.field(screen, "stageCondition");
                require(action.visible && !action.active, "pending edit disables the visible Stage condition action");
                BreakpointUi.openCondition(screen, CodonClientMod.state(), target, LINES.get(line - 1), 2, null);
                require(ScreenLayers.get(screen) == null, "pending stage cannot open a second condition editor");
                CodonClientMod.state().breakpoints().finish(pending, ClientBreakpointState.Result.APPLIED);
            });
            context.waitTicks(2);
            context.runOnClient(client -> require(((AbstractWidget) FunctionLineBreakpointGameTest
                .field(client.gui.screen(), "stageCondition")).active, "acknowledgement restores the Stage condition action"));
        }
        if (!save) return;
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            BreakpointUi.openCondition(screen, CodonClientMod.state(), line == 7 ? BreakpointTarget.whole(location(line)) : target,
                LINES.get(line - 1), line == 6 ? 2 : 1, null);
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            var layer = ScreenLayers.get(client.gui.screen());
            layer.setFocused((AbstractWidget) FunctionLineBreakpointGameTest.field(layer, "saveButton"));
            layer.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        });
        context.waitFor(client -> {
            var state = CodonClientMod.state().breakpoints();
            return !state.pending(target) && state.get(target) != null && state.get(target).enabled()
                && ScreenLayers.get(client.gui.screen()) == null;
        }, 200);
        context.runOnClient(client -> {
            require(CodonClientMod.state().breakpoints().get(target).condition().equals(savedDefinition.condition()),
                "Save preserves the condition on the exact target");
            if (line == 7) require(CodonClientMod.state().breakpoints().get(BreakpointTarget.whole(location(line))) == null,
                "legacy Save enables in place without adding a whole-line definition");
        });
        context.takeScreenshot("codon-condition-" + name + "-saved-enabled");
    }

    private static void assertInk(ClientGameTestContext context, int[] point, String name, boolean expected) {
        try {
            double[] scale = context.computeOnClient(client -> {
                var screen = (ScaledCodonScreen) client.gui.screen();
                return new double[]{screen.uiScale().toGame(1) * client.getWindow().getWidth() / client.getWindow().getGuiScaledWidth(),
                    screen.uiScale().toGame(1) * client.getWindow().getHeight() / client.getWindow().getGuiScaledHeight()};
            });
            var image = ImageIO.read(context.takeScreenshot("codon-condition-" + name).toFile());
            int ink = 0;
            for (int x = (int) Math.ceil(point[0] * scale[0]); x < Math.floor((point[0] + 8) * scale[0]); x++)
                for (int y = (int) Math.ceil(point[1] * scale[1]); y < Math.floor((point[1] + 8) * scale[1]); y++)
                    // The modal dims the parent. Both the ordinary muted outline
                    // and its dimmed strokes remain brighter than this blank gutter/slot.
                    {
                        int rgb = image.getRGB(x, y), red = rgb >> 16 & 255, green = rgb >> 8 & 255, blue = rgb & 255;
                        if (red >= 75 && green >= red + 8 && blue >= green && blue - red <= 45) ink++;
                    }
            // Fractional text/selection edges can contribute one or two matching
            // pixels after a slot disappears; a breakpoint outline contributes many strokes.
            System.out.println("Condition marker pixels " + name + ": " + ink);
            require((ink >= 6) == expected, "inactive marker pixel visibility " + name + ": ink=" + ink + " expected=" + expected);
        } catch (java.io.IOException error) { throw new AssertionError(error); }
    }

    private static void move(ClientGameTestContext context, int x, int y) {
        double[] point = context.computeOnClient(client -> {
            var screen = (ScaledCodonScreen) client.gui.screen();
            var window = client.getWindow();
            return new double[]{screen.uiScale().toGame(x) * window.getScreenWidth() / window.getGuiScaledWidth(),
                screen.uiScale().toGame(y) * window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(point[0], point[1]);
    }
    private static SourceLocation location(int line) { return new SourceLocation.Function(new FunctionLocation(FUNCTION, line)); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
