package works.nuty.codon.client;

import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.InputType;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTextTooltip;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import works.nuty.codon.client.state.*;
import works.nuty.codon.client.ui.*;
import works.nuty.codon.core.model.*;

/** Native deferred text, edge placement, styles and actual saved-line Source hover. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerTooltipGameTest implements FabricClientGameTest {
    private static volatile boolean observing;
    private static volatile Capture capture;
    private record Capture(int width, int height, int x, int y, int rows, int viewportWidth, int viewportHeight, String text) { }
    private static final FunctionId FUNCTION = new FunctionId("codon_test", "tooltip_" + "long_path_".repeat(12));
    private static final String COMMAND = "say tooltip_fixture";

    public static void observe(GuiGraphicsExtractor graphics, Font font, List<ClientTooltipComponent> lines,
                               int x, int y, ClientTooltipPositioner positioner) {
        if (!observing || !(graphics instanceof CodonGuiGraphics)) return;
        int width = lines.stream().mapToInt(line -> line.getWidth(font)).max().orElse(0);
        int height = lines.stream().mapToInt(line -> line.getHeight(font)).sum() + (lines.size() > 1 ? 2 : 0);
        var point = positioner.positionTooltip(graphics.guiWidth(), graphics.guiHeight(), x, y, width, height);
        StringBuilder text = new StringBuilder();
        for (var line : lines) if (line instanceof ClientTextTooltip) {
            var sequence = (FormattedCharSequence) FunctionLineBreakpointGameTest.field(line, "text");
            sequence.accept((index, style, codePoint) -> { text.appendCodePoint(codePoint); return true; });
            text.append('\n');
        }
        capture = new Capture(width, height, point.x(), point.y(), lines.size(), graphics.guiWidth(), graphics.guiHeight(), text.toString());
    }

    public static void beginObservation() { capture = null; observing = true; }
    public static String endObservation() { observing = false; return capture == null ? "" : capture.text(); }

    @Override public void runTest(ClientGameTestContext context) {
        int oldScale = context.computeOnClient(client -> client.options.guiScale().get());
        String oldLanguage = context.computeOnClient(client -> client.getLanguageManager().getSelected());
        observing = true;
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            var preferences = new DebuggerPreferences();
            language(context, "en_us");
            scale(context, preferences, 4, 1280, 960);
            context.runOnClient(client -> checkText(client.font, "en_us"));
            source(context, "en_us-4");
            var fixture = openTooltips(context, preferences);
            // Cover every input path once; language/scale cases only repeat direct text.
            for (int mode : new int[]{0, 1, 2, 3, 6}) tooltip(context, fixture, mode, "en_us-4-mode" + mode);
            context.runOnClient(client -> { fixture.mode = 4; capture = null; });
            context.waitTicks(3);
            require(capture == null, "covered focused button cannot leak a tooltip");
            context.runOnClient(client -> { fixture.mode = 5; fixture.button.setFocused(false); capture = null; });
            context.waitTicks(3);
            require(capture == null, "clipped widget cannot hover outside its scissor");
            context.runOnClient(client -> { fixture.mode = 3; capture = null; });
            context.waitTicks(1);
            require(capture == null, "uncover starts a fresh hover delay");
            context.waitTicks(12);
            assertCapture("uncovered hover recovers");

            language(context, "ko_kr");
            context.runOnClient(client -> checkText(client.font, "ko_kr"));
            for (int request : new int[]{9, 16}) {
                scale(context, preferences, request, 1280, 960);
                if (request == 9) source(context, "ko_kr-9");
                tooltip(context, openTooltips(context, preferences), 0, "ko_kr-" + request + "-mode0");
            }
            scale(context, preferences, 16, 320, 240);
            tooltip(context, openTooltips(context, preferences), 0, "ko-minimum-clamped");
        } finally {
            observing = false;
            context.runOnClient(client -> {
                client.setScreenAndShow(null);
                CodonClientMod.state().preferences().resetUiScale();
                CodonClientMod.state().breakpoints().reset();
                CodonClientMod.state().stagePreviews().reset();
                client.options.guiScale().set(oldScale); client.resizeGui();
            });
            language(context, oldLanguage);
        }
    }

    private static void scale(ClientGameTestContext context, DebuggerPreferences preferences, int request, int width, int height) {
        context.getInput().resizeWindow(width, height);
        context.runOnClient(client -> {
            client.options.guiScale().set(2); client.resizeGui();
            preferences.selectCustomUiScale(2);
            preferences.setCustomUiScale(request);
            CodonClientMod.state().preferences().selectCustomUiScale(2);
            CodonClientMod.state().preferences().setCustomUiScale(request);
        });
    }

    private static TooltipScreen openTooltips(ClientGameTestContext context, DebuggerPreferences preferences) {
        return context.computeOnClient(client -> {
            var screen = new TooltipScreen(preferences);
            client.setScreenAndShow(screen);
            return screen;
        });
    }

    private static void tooltip(ClientGameTestContext context, TooltipScreen fixture, int mode, String name) {
        context.runOnClient(client -> { fixture.mode = mode; capture = null; });
        move(context, fixture, fixture.width - 8, fixture.height - 8);
        context.runOnClient(client -> client.setLastInputType(mode == 2 ? InputType.KEYBOARD_TAB : InputType.MOUSE));
        context.waitTicks(mode == 3 ? 12 : 3);
        assertCapture(name);
        if (mode == 0) require(capture.rows() == 3 && !capture.text().contains("ignored"), "deferred tooltip priority is unchanged");
        if (mode == 1) require(capture.text().startsWith("namespace:") && capture.text().endsWith("preview\n"),
            "component-list order is unchanged");
        context.takeScreenshot("codon-tooltip-" + name);
    }

    private static void source(ClientGameTestContext context, String name) {
        var screen = context.computeOnClient(client -> {
            var sources = new ClientFunctionSourceState();
            sources.select(FUNCTION);
            long id = sources.drainRequests().getFirst().requestId();
            sources.accept(new ClientFunctionSourceState.SourcePage(id, ClientFunctionSourceState.Status.READY,
                FUNCTION, "fixture", "tooltip", false, 0, true, List.of(COMMAND)));
            var state = CodonClientMod.state();
            var location = new SourceLocation.Function(new FunctionLocation(FUNCTION, 1));
            state.breakpoints().reset();
            state.breakpoints().acceptPage(1, 0, true, List.of(BreakpointDefinition.plain(BreakpointTarget.whole(location))));
            state.stagePreviews().reset();
            long preview = state.stagePreviews().begin(location);
            state.stagePreviews().accept(preview, location, ClientStagePreviewState.Status.READY, COMMAND,
                List.of(new ClientStagePreviewState.StageSpan(0, 0, COMMAND.length(), true)));
            var result = new FunctionSourceScreen(new Screen(Component.empty()) { }, sources);
            client.setScreenAndShow(result);
            capture = null;
            return result;
        });
        context.waitTicks(3);
        int x = method(screen, "lineMarkerX") + 3, y = method(screen, "sourceLineTop") + 9;
        move(context, screen, x, y);
        context.runOnClient(client -> capture = null);
        context.waitTicks(3);
        assertCapture("Source saved line " + name);
        require(capture.rows() >= 3 && capture.text().contains(BreakpointUi.condition(works.nuty.codon.core.model.BreakpointCondition.ALWAYS)),
            "Exact line condition and both mouse hints are visible on separate lines");
        context.takeScreenshot("codon-tooltip-source-" + name);
        context.runOnClient(client -> {
            var state = CodonClientMod.state();
            var definition = state.breakpoints().definitions().getFirst().withEnabled(false);
            state.breakpoints().acceptPage(2, 0, true, List.of(definition));
            BreakpointUi.openCondition(screen, state, BreakpointTarget.whole(definition.target().location()), COMMAND, 1,
                new BreakpointConditionScreen.Anchor(x, y, 9, 9));
            capture = null;
        });
        context.waitTicks(3);
        require(capture == null, "condition layer suppresses saved-line tooltip below it");
        context.runOnClient(client -> { ScreenLayers.close(ScreenLayers.get(screen)); capture = null; });
        context.waitTicks(3);
        assertCapture("disabled saved line recovers after modal closes " + name);
        context.runOnClient(client -> {
            var location = new SourceLocation.Function(new FunctionLocation(FUNCTION, 1));
            CodonClientMod.state().breakpoints().acceptPage(3, 0, true,
                List.of(BreakpointDefinition.plain(BreakpointTarget.stage(location, 0, COMMAND))));
            capture = null;
        });
        context.waitTicks(3);
        assertCapture("legacy stage stays separate from the gutter line " + name);
        require(capture.text().contains(Component.translatable("codon.source.no_breakpoint").getString())
            && capture.text().contains(Component.translatable("codon.source.stage_breakpoints", 1).getString()),
            "Legacy stage count remains visible without claiming a saved whole-line breakpoint");
    }

    private static void checkText(Font font, String language) {
        var styled = Component.literal("prefix ").withStyle(ChatFormatting.RED)
            .append(Component.literal("identifier_".repeat(35))).append("\n")
            .append(Component.translatable("codon.breakpoint.saved_line_definitions", 3));
        var lines = CodonTooltips.fit(font, List.of(styled.getVisualOrderText()), 320);
        require(lines.size() > 4 && lines.stream().allMatch(line -> font.width(line) <= 240), "unbroken identifiers wrap");
        StringBuilder text = new StringBuilder();
        boolean[] red = {false};
        for (var line : lines) line.accept((index, style, codePoint) -> {
            text.appendCodePoint(codePoint);
            if (style.getColor() != null && style.getColor().equals(styled.getStyle().getColor())) red[0] = true;
            return true;
        });
        require(red[0] && text.toString().contains(language.equals("ko_kr") ? "우클릭" : "Right-click"),
            "wrapping retains styles and localized secondary hints");
        require(text.toString().contains("identifier_".repeat(35)), "long detail text is not discarded");
    }

    private static final class TooltipScreen extends ScaledCodonScreen {
        int mode;
        private DebuggerButton button;
        private DebuggerEditBox field;
        TooltipScreen(DebuggerPreferences preferences) { super(Component.empty(), preferences); }
        @Override protected void init() {
            button = new DebuggerButton();
            button.configure(width - 45, height - 25, 38, 18, Component.literal("x"), true, true, false, false, () -> { });
            button.withSingleLineTooltip(Component.translatable("codon.breakpoint.saved_line_definitions", 2));
            button.setFocused(true);
            field = new DebuggerEditBox(font, width - 80, height - 25, 73, 18, Component.literal("value"));
            field.setTooltip(Tooltip.create(Component.translatable("codon.breakpoint.saved_line_definitions", 2)));
            field.setTooltipDelay(java.time.Duration.ZERO);
        }
        @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
            capture = null;
            graphics.fill(0, 0, width, height, 0xFF202020);
            if (mode == 0) {
                graphics.setTooltipForNextFrame(font,
                    Component.translatable("codon.breakpoint.saved_line_definitions", 2), width - 8, height - 8);
                graphics.setTooltipForNextFrame(font, Component.literal("ignored"), width - 8, height - 8);
            }
            else if (mode == 1) graphics.setComponentTooltipForNextFrame(font,
                List.of(Component.literal("namespace:" + "long_identifier_".repeat(8)), Component.literal("preview")), width - 8, height - 8);
            else if (mode == 6) field.extractRenderState(graphics, x, y, delta);
            else {
                button.active = mode != 3;
                if (mode == 5) graphics.enableScissor(0, 0, 20, 20);
                button.extractRenderState(graphics, mode == 4 ? -1 : x, mode == 4 ? -1 : y, delta);
                if (mode == 5) graphics.disableScissor();
            }
        }
    }

    private static void assertCapture(String name) {
        var result = capture;
        require(result != null, "native deferred tooltip observed: " + name);
        require(result.width() <= 240 && result.x() >= 4 && result.y() >= 4
            && result.x() + result.width() <= result.viewportWidth() - 4
            && result.y() + result.height() <= result.viewportHeight() - 4, "native tooltip bounds: " + name + " " + result);
        System.out.println("Tooltip PASS " + name + " " + result);
    }
    private static int method(Object target, String name) {
        try { var method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); return (int) method.invoke(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void move(ClientGameTestContext context, ScaledCodonScreen screen, int x, int y) {
        double[] point = context.computeOnClient(client -> new double[]{
            screen.uiScale().toGame(x) * client.getWindow().getScreenWidth() / client.getWindow().getGuiScaledWidth(),
            screen.uiScale().toGame(y) * client.getWindow().getScreenHeight() / client.getWindow().getGuiScaledHeight()});
        context.getInput().setCursorPos(point[0], point[1]);
    }
    private static void language(ClientGameTestContext context, String language) {
        if (context.computeOnClient(client -> client.getLanguageManager().getSelected().equals(language))) return;
        var reload = context.computeOnClient(client -> { client.getLanguageManager().setSelected(language); return client.reloadResourcePacks(); });
        context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 200);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
