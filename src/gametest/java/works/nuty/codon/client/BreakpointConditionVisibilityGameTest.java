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
            verify(context, world, 6, true, "unset-stage", false, true);
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
        verify(context, world, line, savedStage, name, save, line == 8);
    }

    private static void verify(ClientGameTestContext context, TestSingleplayerContext world,
                               int line, boolean savedStage, String name, boolean save, boolean absent) {
        var savedTarget = savedStage ? BreakpointTarget.stage(location(line), 0, LINES.get(line - 1))
            : BreakpointTarget.whole(location(line));
        var target = line == 7 ? BreakpointTarget.whole(location(line)) : savedTarget;
        var definition = BreakpointDefinition.plain(savedTarget).withEnabled(false);
        if (line == 6) definition = definition.withCondition(BreakpointCondition.count(
            BreakpointCondition.Kind.INPUT_COUNT, BreakpointCondition.Comparison.EQ, 1));
        var savedDefinition = definition;
        if (!absent) {
            world.getServer().runOnServer(server -> {
                if (!target.equals(savedTarget)) CodonMod.engine().deleteBreakpoint(target);
                CodonMod.engine().saveBreakpoint(savedDefinition);
            });
            context.waitFor(client -> savedDefinition.equals(CodonClientMod.state().breakpoints().get(savedTarget))
                && (target.equals(savedTarget) || CodonClientMod.state().breakpoints().get(target) == null), 200);
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
            if (line == 7) require(ScreenLayers.get(screen).getClass().getSimpleName().equals("DebuggerContextMenu"),
                "saved sole stage exposes explicit choices while retaining line Condition");
        });
        if (line == 7) context.takeScreenshot("codon-condition-" + name + "-saved-stage-options");
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            chooseLineCondition(screen);
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
        // SDL delivers the last cursor warp on a later frame. Settle it before
        // opening a keyboard menu; a subsequent pointer move correctly dismisses it.
        context.waitFor(client -> {
            var layer = ScreenLayers.get(client.gui.screen());
            return layer != null && (int) FunctionLineBreakpointGameTest.field(layer, "lastMouseX") == 0
                && (int) FunctionLineBreakpointGameTest.field(layer, "lastMouseY") == 0;
        }, 200);
        context.runOnClient(client -> {
            var layer = ScreenLayers.get(client.gui.screen());
            layer.setFocused((AbstractWidget) FunctionLineBreakpointGameTest.field(layer, "kindButton"));
            layer.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
            require(!FunctionLineBreakpointGameTest.field(layer, "menu").toString().equals("NONE"), "keyboard opens the selector menu");
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
            var current = CodonClientMod.state().breakpoints().get(savedTarget);
            require(absent ? current == null : savedDefinition.equals(current), "Cancel preserves disabled/absent state for " + name);
            require(!CodonClientMod.state().breakpoints().pending(target), "Cancel sends no edit for " + name);
        });
        context.waitTicks(2);
        assertRetainedFocus(context, point, target, line, name + "-cancelled");
        clearMarkerFocus(context, point, name + "-navigated");
        if (line == 6) {
            // Presentation-only pending edit; no server mutation is inferred from this fixture.
            long pending = context.computeOnClient(client -> CodonClientMod.state().breakpoints()
                .begin(ClientBreakpointState.Action.SAVE, savedDefinition).requestId());
            context.waitTicks(2);
            context.takeScreenshot("codon-condition-" + name + "-pending-stage-action");
            context.runOnClient(client -> {
                var screen = client.gui.screen();
                require(screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                    .noneMatch(widget -> widget.visible && widget.getMessage().equals(net.minecraft.network.chat.Component.translatable("codon.source.stage_condition"))),
                    "Pending state does not recreate the removed Stage condition header action");
                BreakpointUi.openCondition(screen, CodonClientMod.state(), target, LINES.get(line - 1), 2, null);
                require(ScreenLayers.get(screen) == null, "pending stage cannot open a second condition editor");
                CodonClientMod.state().breakpoints().finish(pending, ClientBreakpointState.Result.APPLIED);
            });
            context.waitTicks(2);
            context.runOnClient(client -> {
                var screen = client.gui.screen();
                BreakpointUi.openCondition(screen, CodonClientMod.state(), target, LINES.get(line - 1), 2, null);
                require(ScreenLayers.get(screen) instanceof BreakpointConditionScreen, "Acknowledgement restores condition-editor access");
                ScreenLayers.get(screen).onClose();
            });
        }
        if (!save) return;
        move(context, point[0] + 3, point[1] + 3);
        context.waitTicks(2);
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            var event = new MouseButtonEvent(point[0] + 3, point[1] + 3,
                new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0));
            require(screen.mouseClicked(event, false), "The exact Source marker reopens its editor for Save");
            screen.mouseReleased(event);
            chooseLineCondition(screen);
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
            if (line == 7) require(savedDefinition.equals(CodonClientMod.state().breakpoints().get(savedTarget)),
                "Saving the Source gutter target leaves the separate legacy stage-zero definition unchanged");
        });
        context.takeScreenshot("codon-condition-" + name + "-saved-enabled");
        if (line == 5 || line == 6) {
            context.getInput().setCursorPos(0, 0);
            context.runOnClient(client -> {
                var screen = client.gui.screen();
                screen.keyPressed(new KeyEvent(InputConstants.KEY_F10, 0, InputConstants.MOD_SHIFT));
                var layer = ScreenLayers.get(screen);
                require(layer instanceof BreakpointConditionScreen editor && editor.editsMarker(target, LINES.get(line - 1)),
                    "Delete opens the retained exact marker");
                layer.setFocused((AbstractWidget) FunctionLineBreakpointGameTest.field(layer, "deleteButton"));
                layer.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
            });
            context.waitFor(client -> !CodonClientMod.state().breakpoints().pending(target)
                && CodonClientMod.state().breakpoints().get(target) == null && ScreenLayers.get(client.gui.screen()) == null, 200);
            assertRetainedFocus(context, point, target, line, name + "-deleted");
            clearMarkerFocus(context, point, name + "-deleted-navigated");
        }
    }

    private static void assertRetainedFocus(ClientGameTestContext context, int[] point, BreakpointTarget target, int line, String name) {
        context.waitTicks(2);
        context.runOnClient(client -> require(target.equals(FunctionLineBreakpointGameTest.field(client.gui.screen(), "focusedBreakpoint")),
            "Retained Source focus is the exact line/stage/fingerprint for " + name));
        assertInk(context, point, name, true);
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            screen.keyPressed(new KeyEvent(InputConstants.KEY_F10, 0, InputConstants.MOD_SHIFT));
            chooseLineCondition(screen);
            var layer = ScreenLayers.get(screen);
            require(layer instanceof BreakpointConditionScreen editor && editor.editsMarker(target, LINES.get(line - 1)),
                "Shift+F10 reopens the visible retained marker for " + name);
            layer.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
            require(ScreenLayers.get(screen) == null, "Escape closes the reopened exact editor");
        });
        context.waitTicks(2);
        assertInk(context, point, name + "-escape", true);
    }

    private static void chooseLineCondition(Screen screen) {
        var layer = ScreenLayers.get(screen);
        if (layer != null && layer.getClass().getSimpleName().equals("DebuggerContextMenu"))
            layer.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
    }

    private static void clearMarkerFocus(ClientGameTestContext context, int[] point, String name) {
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            screen.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
            require(FunctionLineBreakpointGameTest.field(screen, "focusedBreakpoint") == null,
                "Explicit navigation clears retained marker focus");
        });
        context.waitTicks(2);
        assertInk(context, point, name, false);
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
                        int rgb = image.getRGB(x, y);
                        if (mutedStroke(rgb, 1.0) || mutedStroke(rgb, 143.0 / 255.0)) ink++;
                    }
            // Fractional text/selection edges can contribute one or two matching
            // pixels after a slot disappears; a breakpoint outline contributes many strokes.
            System.out.println("Condition marker pixels " + name + ": " + ink);
            require((ink >= 6) == expected, "inactive marker pixel visibility " + name + ": ink=" + ink + " expected=" + expected);
        } catch (java.io.IOException error) { throw new AssertionError(error); }
    }

    private static boolean mutedStroke(int pixel, double brightness) {
        for (int shift : new int[]{16, 8, 0}) {
            double expected = (works.nuty.codon.client.ui.DebuggerTheme.MUTED >> shift & 255) * brightness;
            if (Math.abs((pixel >> shift & 255) - expected) > 2) return false;
        }
        return true;
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
