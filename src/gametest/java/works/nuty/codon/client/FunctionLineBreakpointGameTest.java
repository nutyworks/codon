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
            verifyStaleWarning(context);
            verifyStopCue(context);
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
                "A marker cannot edit before the authoritative breakpoint snapshot arrives");
            for (String phase : List.of("missing", "loading", "stale READY", "matching READY")) {
                state.breakpoints().reset();
                state.breakpoints().acceptPage(1, 0, true, List.of(legacy));
                state.stagePreviews().reset();
                if (!phase.equals("missing")) {
                    long request = state.stagePreviews().begin(LOCATION);
                    if (phase.endsWith("READY")) {
                        String command = phase.equals("matching READY") ? COMMAND : "say old";
                        state.stagePreviews().accept(request, LOCATION, ClientStagePreviewState.Status.READY, command,
                            List.of(new ClientStagePreviewState.StageSpan(0, 0, command.length(), true)));
                    }
                }
                click(screen, x, y, true);
                require(ScreenLayers.get(screen) instanceof BreakpointConditionScreen, "Gutter opens its editor directly: " + phase);
                var editor = (BreakpointConditionScreen) ScreenLayers.get(screen);
                require(((BreakpointDefinition) field(editor, "original")).target().equals(whole),
                    "Source gutter always addresses the whole line, never a legacy stage-zero save: " + phase);
                editor.onClose();
                require(state.breakpoints().definitions().equals(List.of(legacy)) && !state.breakpoints().pending(whole),
                    "Open/cancel leaves the legacy definition and unset line unchanged");
                screen.keyPressed(new KeyEvent(InputConstants.KEY_F10, 0, InputConstants.MOD_SHIFT));
                require(ScreenLayers.get(screen) instanceof BreakpointConditionScreen
                    && ((BreakpointConditionScreen) ScreenLayers.get(screen)).editsMarker(whole, COMMAND),
                    "Shift+F10 returns to the same gutter target after cancel");
                ScreenLayers.get(screen).onClose();
                click(screen, x, y, false);
                require(state.breakpoints().pending(whole) && !state.breakpoints().pending(legacy.target()),
                    "Source gutter toggle uses only the exact line target: " + phase);
            }
            state.breakpoints().reset();
            state.breakpoints().acceptPage(1, 0, true, List.of());
        });
        context.waitTicks(2);
        context.runOnClient(client -> require(client.gui.screen().children().stream()
            .filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance)
            .map(net.minecraft.client.gui.components.AbstractWidget.class::cast)
            .noneMatch(widget -> widget.visible && (widget.getMessage().equals(Component.translatable("codon.source.line_condition"))
                || widget.getMessage().equals(Component.translatable("codon.source.stage_condition")))),
            "Source has no removed line/stage condition header controls"));
    }

    private static void verifyStaleWarning(ClientGameTestContext context) {
        int[] point = context.computeOnClient(client -> {
            var state = CodonClientMod.state();
            var obsolete = BreakpointDefinition.plain(BreakpointTarget.stage(LOCATION, 0, "say obsolete")).withStaleSource(true);
            state.breakpoints().reset();
            state.breakpoints().acceptPage(1, 0, true, List.of(BreakpointDefinition.plain(BreakpointTarget.whole(LOCATION)), obsolete));
            return new int[]{value(client.gui.screen(), "sourceLeft") + 23, value(client.gui.screen(), "sourceLineTop") + 9};
        });
        move(context, point[0], point[1]);
        context.runOnClient(client -> DebuggerTooltipGameTest.beginObservation());
        context.waitTicks(3);
        context.runOnClient(client -> {
            String text = DebuggerTooltipGameTest.endObservation().replaceAll("\\s", "");
            String expected = Component.translatable("codon.breakpoint.error.stale_source").getString().replaceAll("\\s", "");
            require(text.contains(expected), "Obsolete-stage warning keeps its own tooltip beside the enlarged gutter target");
            click(client.gui.screen(), point[0], point[1], false);
            require(!CodonClientMod.state().breakpoints().pending(BreakpointTarget.whole(LOCATION)),
                "Clicking the obsolete-stage warning only selects the line, never toggles its whole breakpoint");
        });
        context.takeScreenshot("codon-line-stale-warning-target");
        context.getInput().setCursorPos(0, 0);
    }

    private static void verifyStopCue(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var state = CodonClientMod.state();
            state.breakpoints().reset();
            state.breakpoints().acceptPage(1, 0, true, List.of());
            state.applyPause(new PauseSnapshot(LOCATION, CommandSnippet.plain(COMMAND), 0, List.of(), List.of(), PauseReason.BREAKPOINT));
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            int y = value(screen, "sourceLineTop") + 9;
            click(screen, value(screen, "sourceLeft") + 3, y, false);
            require(!CodonClientMod.state().breakpoints().pending(BreakpointTarget.whole(LOCATION)),
                "Clicking the stopped line's amber cue only selects the line, never toggles its breakpoint");
            click(screen, value(screen, "lineMarkerX"), y, false);
            require(CodonClientMod.state().breakpoints().pending(BreakpointTarget.whole(LOCATION)),
                "The stopped line's breakpoint marker still requests the whole-line target");
        });
        context.takeScreenshot("codon-line-stop-cue-target");
        context.runOnClient(client -> CodonClientMod.state().applyResume());
        context.waitTicks(2);
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
            click(screen, geometry[0] + 10, geometry[1] + 9, false);
            require(!CodonClientMod.state().breakpoints().pending(BreakpointTarget.whole(LOCATION)),
                "the half-open right edge selects the line without toggling");
            click(screen, geometry[0] - 8, geometry[1], false);
            require(CodonClientMod.state().breakpoints().pending(BreakpointTarget.whole(LOCATION)),
                "expanded 18×18 gutter corner requests only the whole-line target");
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
