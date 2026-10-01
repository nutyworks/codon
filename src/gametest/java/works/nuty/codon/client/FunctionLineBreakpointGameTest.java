package works.nuty.codon.client;

import java.util.List;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.input.KeyEvent;
import works.nuty.codon.client.ui.ScaledCodonScreen;
import works.nuty.codon.client.ui.ScreenLayers;
import works.nuty.codon.client.ui.BreakpointConditionScreen;
import works.nuty.codon.client.ui.DebuggerTheme;
import works.nuty.codon.client.state.DebuggerPreferences;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.state.ClientFunctionSourceState;
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.client.ui.FunctionSourceScreen;
import works.nuty.codon.core.model.*;

/** Actual Source rendering/input regression; the source document is an explicit UI fixture. */
@SuppressWarnings("UnstableApiUsage")
public final class FunctionLineBreakpointGameTest implements FabricClientGameTest {
    private static final FunctionId FUNCTION = new FunctionId("codon_test", "line_breakpoints");
    private static final String COMMAND = "say single_stage_" + "long_tail_".repeat(80);
    private static final SourceLocation LOCATION = new SourceLocation.Function(new FunctionLocation(FUNCTION, 1));

    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1280, 720);
            context.getInput().setCursorPos(0, 0);
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
                var sources = new ClientFunctionSourceState();
                sources.select(FUNCTION);
                long request = sources.drainRequests().getFirst().requestId();
                sources.accept(new ClientFunctionSourceState.SourcePage(request, ClientFunctionSourceState.Status.READY,
                    FUNCTION, "gametest", "single-stage", false, 0, true, List.of(COMMAND, "# 한국어 comment")));
                var state = CodonClientMod.state();
                state.breakpoints().reset();
                state.breakpoints().acceptPage(1, 0, true, List.of(BreakpointDefinition.plain(BreakpointTarget.whole(LOCATION))));
                client.setScreenAndShow(new FunctionSourceScreen(new Screen(Component.empty()) { }, sources));
            });
            world.getConnection().waitForChunksRender();
            context.waitTicks(2);
            context.runOnClient(client -> {
                var previews = CodonClientMod.state().stagePreviews();
                long request = previews.begin(LOCATION);
                previews.accept(request, LOCATION, ClientStagePreviewState.Status.READY, COMMAND,
                    List.of(new ClientStagePreviewState.StageSpan(0, 0, COMMAND.length(), true)));
            });
            context.waitTicks(2);
            context.takeScreenshot("codon-line-breakpoint-placement");
            context.runOnClient(client -> {
                Screen screen = client.gui.screen();
                int marker = value(screen, "lineMarkerX"), sourceLeft = value(screen, "sourceLeft");
                int codeLeft = sourceLeft + value(screen, "gutterWidth");
                require(marker + 10 <= codeLeft - 6 - client.font.width("1"),
                    "line breakpoint artwork and hit target must be LEFT of the line number; marker=" + marker + " code=" + codeLeft);
            });
            verifyPreviewTransitions(context);
            verifySingleStage(context, "en-default");
            context.runOnClient(client -> {
                var screen = (ScaledCodonScreen) client.gui.screen();
                screen.uiPreferences().setUiScaleMode(DebuggerPreferences.UiScaleMode.CUSTOM);
                screen.uiPreferences().setCustomUiScale(6);
                client.resizeGui();
            });
            context.waitTicks(2);
            verifySingleStage(context, "en-custom-1.5");
            String language = context.computeOnClient(client -> client.getLanguageManager().getSelected());
            var reload = context.computeOnClient(client -> {
                client.getLanguageManager().setSelected("ko_kr");
                return client.reloadResourcePacks();
            });
            context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 200);
            verifySingleStage(context, "ko-custom-1.5");
            var restore = context.computeOnClient(client -> {
                client.getLanguageManager().setSelected(language);
                ((ScaledCodonScreen) client.gui.screen()).uiPreferences().resetUiScale();
                return client.reloadResourcePacks();
            });
            context.waitFor(client -> restore.isDone() && client.gui.overlay() == null, 200);
            context.runOnClient(client -> client.setScreenAndShow(null));
        }
    }

    private static void verifyPreviewTransitions(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var state = CodonClientMod.state();
            var screen = client.gui.screen();
            int x = value(screen, "lineMarkerX"), y = value(screen, "sourceLineTop") + 9;
            var whole = BreakpointTarget.whole(LOCATION);
            var legacy = BreakpointDefinition.plain(BreakpointTarget.stage(LOCATION, 0, COMMAND));
            state.breakpoints().reset();
            click(screen, x, y, false);
            click(screen, x, y, true);
            require(!state.breakpoints().pending(whole) && ScreenLayers.get(screen) == null,
                "READY preview cannot edit a line before the saved-breakpoint snapshot arrives");
            state.breakpoints().acceptPage(1, 0, true, List.of(legacy));
            state.stagePreviews().reset();
            for (String phase : List.of("missing", "loading", "stale READY")) {
                click(screen, x, y, false);
                require(!state.breakpoints().pending(whole) && !state.breakpoints().pending(legacy.target()),
                    "Source " + phase + " preview must defer legacy-sensitive toggle without creating a whole target");
                click(screen, x, y, true);
                require(ScreenLayers.get(screen) == null,
                    "Source " + phase + " preview must defer legacy-sensitive condition editing");
                long request = state.stagePreviews().begin(LOCATION);
                if (phase.equals("loading")) state.stagePreviews().accept(request, LOCATION,
                    ClientStagePreviewState.Status.READY, "say old", List.of(new ClientStagePreviewState.StageSpan(0, 0, 7, true)));
            }
            long ready = state.stagePreviews().begin(LOCATION);
            state.stagePreviews().accept(ready, LOCATION, ClientStagePreviewState.Status.READY, COMMAND,
                List.of(new ClientStagePreviewState.StageSpan(0, 0, COMMAND.length(), true)));
            click(screen, x, y, false);
            require(state.breakpoints().pending(legacy.target()) && !state.breakpoints().pending(whole),
                "READY single-stage toggle edits the original legacy target");
            state.breakpoints().reset();
            state.breakpoints().acceptPage(1, 0, true, List.of(legacy));
            click(screen, x, y, true);
            var condition = (BreakpointConditionScreen) ScreenLayers.get(screen);
            require(legacy.equals(field(condition, "original")), "READY condition editor retains the legacy definition");
            condition.setFocused((net.minecraft.client.gui.components.AbstractWidget) field(condition, "saveButton"));
            condition.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
            require(state.breakpoints().pending(legacy.target()) && !state.breakpoints().pending(whole),
                "condition Save edits the original legacy target, never a new whole target");
            condition.onClose();
            state.breakpoints().reset();
            state.breakpoints().acceptPage(1, 0, true, List.of());
            state.stagePreviews().begin(LOCATION);
            click(screen, x, y, false);
            require(state.breakpoints().pending(whole), "ordinary whole-line toggle remains available while preview loads");
            state.breakpoints().reset();
            state.breakpoints().acceptPage(1, 0, true, List.of());
            click(screen, x, y, true);
            condition = (BreakpointConditionScreen) ScreenLayers.get(screen);
            require(((BreakpointDefinition) field(condition, "original")).target().equals(whole),
                "ordinary whole-line conditions remain available while preview loads");
            condition.onClose();
            state.breakpoints().reset();
            state.breakpoints().acceptPage(1, 0, true, List.of(legacy));
            state.stagePreviews().begin(LOCATION);
        });
        context.waitTicks(2);
        context.runOnClient(client -> require(!((net.minecraft.client.gui.components.AbstractWidget)
            field(client.gui.screen(), "lineCondition")).active,
            "Source condition button is disabled while its legacy target remains ambiguous"));
        context.takeScreenshot("codon-line-preview-deferred-legacy");
    }

    private static void verifySingleStage(ClientGameTestContext context, String name) {
        context.getInput().setCursorPos(0, 0);
        context.runOnClient(client -> {
            var state = CodonClientMod.state();
            state.breakpoints().reset();
            state.breakpoints().acceptPage(1, 0, true, List.of());
            long request = state.stagePreviews().begin(LOCATION);
            state.stagePreviews().accept(request, LOCATION, ClientStagePreviewState.Status.READY, COMMAND,
                List.of(new ClientStagePreviewState.StageSpan(0, 0, COMMAND.length(), true)));
            var screen = client.gui.screen();
            screen.mouseScrolled(value(screen, "sourceLeft") + value(screen, "gutterWidth") + 10,
                value(screen, "sourceLineTop") + 9, -1000, 0);
        });
        context.waitTicks(2);
        int[] geometry = context.computeOnClient(client -> {
            var screen = client.gui.screen();
            return new int[]{value(screen, "lineMarkerX"), value(screen, "sourceLineTop"),
                value(screen, "sourceLeft") + value(screen, "gutterWidth")};
        });
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            click(screen, geometry[2] - 7, geometry[1] + 9, false);
            require(!CodonClientMod.state().breakpoints().pending(BreakpointTarget.whole(LOCATION)),
                "clicking the line number only selects the line");
            require(screen.children().stream().filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance)
                .map(net.minecraft.client.gui.components.AbstractWidget.class::cast)
                .noneMatch(widget -> widget.visible && widget.getMessage().equals(Component.translatable("codon.source.stage_condition"))),
                "single-stage selection exposes no Stage condition button");
        });
        move(context, geometry[2] + 5, geometry[1] + 9);
        context.waitTicks(2);
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            require(((List<?>) field(screen, "stageHits")).isEmpty(), "one-stage text has no separate stage hit box, even on hover");
            require((int) field(screen, "expandedWidth") == client.font.width(COMMAND), "one-stage hover preserves every original source advance");
            click(screen, geometry[0], geometry[1] + 18, false);
            require(!CodonClientMod.state().breakpoints().pending(BreakpointTarget.whole(LOCATION)),
                "the half-open next comment row cannot toggle the first line");
            click(screen, geometry[0], geometry[1] + 9, false);
            require(CodonClientMod.state().breakpoints().pending(BreakpointTarget.whole(LOCATION)),
                "gutter native click requests only the whole-line target");
            require(!CodonClientMod.state().breakpoints().pending(BreakpointTarget.stage(LOCATION, 0, COMMAND)),
                "no single-stage request is sent");
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            var state = CodonClientMod.state();
            state.breakpoints().reset();
            state.breakpoints().acceptPage(1, 0, true, List.of(BreakpointDefinition.plain(BreakpointTarget.whole(LOCATION))));
            var screen = client.gui.screen();
            screen.mouseScrolled(geometry[2] + 10, geometry[1] + 9, 10, 0);
        });
        context.getInput().setCursorPos(0, 0);
        context.waitTicks(2);
        assertMarker(context, geometry, name + "-enabled-scrolled", true);
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            click(screen, geometry[0], geometry[1] + 9, true);
            require(ScreenLayers.get(screen) instanceof BreakpointConditionScreen, "right gutter click edits the line after horizontal scroll");
            var layer = ScreenLayers.get(screen);
            layer.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
            require(ScreenLayers.get(screen) == null && client.gui.screen() == screen, "Escape preserves the source and code viewport");
            screen.setFocused(null);
            screen.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
            require(screen.getFocused() != null, "single-stage suppression retains keyboard traversal");
            var state = CodonClientMod.state();
            state.breakpoints().reset();
            state.breakpoints().acceptPage(1, 0, true, List.of(BreakpointDefinition.plain(BreakpointTarget.whole(LOCATION)).withEnabled(false)));
        });
        context.waitTicks(2);
        assertMarker(context, geometry, name + "-disabled-hidden", false);
        move(context, geometry[0] + 4, geometry[1] + 9);
        context.waitTicks(2);
        context.takeScreenshot("codon-line-" + name + "-disabled-hover");
        context.runOnClient(client -> require(((List<?>) field(client.gui.screen(), "stageHits")).isEmpty(),
            "disabled line hover never invents a sole-stage control"));
    }

    private static void assertMarker(ClientGameTestContext context, int[] geometry, String name, boolean enabled) {
        try {
            var dimensions = context.computeOnClient(client -> new int[]{client.gui.screen().width, client.gui.screen().height});
            var image = javax.imageio.ImageIO.read(context.takeScreenshot("codon-line-" + name).toFile());
            double sx = (double) image.getWidth() / dimensions[0], sy = (double) image.getHeight() / dimensions[1];
            boolean red = false;
            for (int x = (int) Math.ceil(geometry[0] * sx); x < (int) Math.floor((geometry[0] + 9) * sx); x++)
                for (int y = (int) Math.ceil((geometry[1] + 5) * sy); y < (int) Math.floor((geometry[1] + 14) * sy); y++)
                    red |= (image.getRGB(x, y) & 0xFFFFFF) == (DebuggerTheme.RED & 0xFFFFFF);
            require(red == enabled, "enabled line markers stay visible outside hover: " + name);
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
    private static void click(Screen screen, double x, double y, boolean right) {
        var event = new MouseButtonEvent(x, y, new MouseButtonInfo(right ? InputConstants.MOUSE_BUTTON_RIGHT : InputConstants.MOUSE_BUTTON_LEFT, 0));
        require(screen.mouseClicked(event, false), "source consumes native row click");
        screen.mouseReleased(event);
    }
    static Object field(Object object, String name) {
        try {
            var field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(object);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    static int value(Object object, String name) {
        try {
            var method = object.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            return (int) method.invoke(object);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
