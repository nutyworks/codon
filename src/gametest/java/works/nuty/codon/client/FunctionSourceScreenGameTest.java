package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.InputType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.PauseReason;
import works.nuty.codon.core.model.CommandSnippet;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.state.ClientFunctionSourceState;
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.client.ui.FunctionSourceScreen;
import works.nuty.codon.core.model.FunctionId;
import works.nuty.codon.core.model.FunctionLocation;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.BreakpointCondition;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;

/** Renders the smallest supported source browser with a wrapped stage command and captures it. */
@SuppressWarnings("UnstableApiUsage")
public final class FunctionSourceScreenGameTest implements FabricClientGameTest {
    private static final FunctionId FUNCTION = new FunctionId("codon_test", "long_stage");
    private static final String COMMAND =
        "execute as @e[type=minecraft:armor_stand,tag=codon_source_preview] at @s positioned ~ ~1 ~ run say a_very_long_stage_preview_value";

    @Override public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            // Minecraft keeps a minimum GUI height of 240, so 320x240 is the
            // smallest native client viewport for this visual check.
            context.getInput().resizeWindow(960, 720);
            context.getInput().setCursorPos(0, 0);
            context.runOnClient(client -> {
                client.options.guiScale().set(3);
                client.resizeGui();
            });
            world.getConnection().waitForChunksRender();
            FunctionSourceScreen screen = context.computeOnClient(client -> {
                ClientFunctionSourceState sources = loadedSource();
                var debugger = require(CodonClientMod.state(), "client debugger state is initialized");
                var location = new SourceLocation.Function(new FunctionLocation(FUNCTION, 1));
                // Synthetic source/definitions exercise visibility, not native function execution.
                debugger.breakpoints().acceptPage(Long.MAX_VALUE, 0, true, List.of(
                    BreakpointDefinition.plain(BreakpointTarget.whole(location)).withEnabled(false),
                    BreakpointDefinition.plain(BreakpointTarget.stage(location, 0, COMMAND))
                        .withCondition(BreakpointCondition.event(BreakpointCondition.Kind.CREATED)).withEnabled(false),
                    BreakpointDefinition.plain(BreakpointTarget.stage(location, 1, COMMAND))));
                FunctionSourceScreen result = new FunctionSourceScreen(new Screen(Component.empty()) { }, sources);
                client.setScreenAndShow(result);
                return result;
            });
            context.waitTicks(2);
            context.takeScreenshot("codon-function-source-320x240-before-selection");
            context.runOnClient(client -> {
                require(screen.width == 320 && screen.height == 240,
                    "test uses 320x240 GUI coordinates, actual " + screen.width + "x" + screen.height);
                client.setLastInputType(InputType.MOUSE);
                MouseButtonEvent click = new MouseButtonEvent(100, 130,
                    new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
                require(screen.mouseClicked(click, false), "selects the long function source line");
                screen.mouseReleased(click);
            });
            context.waitTicks(2);
            // The fixture function is not installed in the server's datapacks. Let its
            // NOT_FOUND reply settle, then provide the recorded parse spans for this UI test.
            context.runOnClient(client -> {
                var debugger = require(CodonClientMod.state(), "client debugger state is initialized");
                SourceLocation.Function location = new SourceLocation.Function(new FunctionLocation(FUNCTION, 1));
                long request = debugger.stagePreviews().begin(location);
                require(debugger.stagePreviews().accept(request, location, ClientStagePreviewState.Status.READY, COMMAND,
                    List.of(new ClientStagePreviewState.StageSpan(0, 0, 65, false),
                        new ClientStagePreviewState.StageSpan(1, 66, COMMAND.length(), true))),
                    "stage preview is accepted");
            });
            context.waitTicks(1);
            context.takeScreenshot("codon-function-source-320x240-stage-first");
            context.getInput().setCursorPos(330, 444);
            context.waitTicks(2);
            context.takeScreenshot("codon-function-source-disabled-hover");
            context.getInput().setCursorPos(0, 0);
            context.runOnClient(client -> screen.mouseScrolled(100, 160, 0, -1));
            context.waitTicks(2);
            context.takeScreenshot("codon-function-source-320x240-stage-scrolled");
            context.runOnClient(client -> {
                MouseButtonEvent click = new MouseButtonEvent(90, 149,
                    new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
                require(screen.mouseClicked(click, false), "selects scrolled stage text without toggling its marker");
                screen.mouseReleased(click);
            });
            context.waitTicks(1);
            context.runOnClient(client -> {
                require(screen.children().stream().filter(AbstractButton.class::isInstance).map(AbstractButton.class::cast)
                    .anyMatch(button -> button.visible && button.active && button.getMessage().getString().equals("Stage condition…")),
                    "selected server-provided stage retains its condition control");
            });
            context.waitTicks(1);
            context.takeScreenshot("codon-function-source-stage-selected");
            context.getInput().resizeWindow(960, 540);
            context.waitTicks(2);
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
                require(screen.width == 480 && screen.height == 270,
                    "test uses 480x270 GUI coordinates after resize, actual "
                        + screen.width + "x" + screen.height);
            });
            context.takeScreenshot("codon-function-source-480x270-stage");
            context.getInput().resizeWindow(1280, 720);
            ClientFunctionSourceState sourceState = context.computeOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
                var debugger = require(CodonClientMod.state(), "client debugger state is initialized");
                InputManager input = new InputManager(debugger, ignored -> { });
                KeyMapping.Category category = new KeyMapping.Category(InputManager.CATEGORY_ID);
                input.menuKey = key("open_menu", InputConstants.KEY_V, category);
                input.keepFreecamKey = key("keep_freecam", InputConstants.KEY_G, category);
                input.hideUiKey = key("hide_ui", InputConstants.KEY_H, category);
                input.breakpointKey = key("breakpoint", InputConstants.KEY_F10, category);
                input.resumeKey = key("resume", InputConstants.KEY_F7, category);
                input.stepOverKey = key("step_over", InputConstants.KEY_F8, category);
                input.stepIntoKey = key("step_into", InputConstants.KEY_F9, category);
                CodonScreen parent = new CodonScreen(input, new DebuggerOverlay(debugger));
                client.setScreenAndShow(parent);
                ClientFunctionSourceState wideSources = loadedSource();
                wideSources.rememberBrowseView(0, 0, 1, 0, 0);
                client.setScreenAndShow(new FunctionSourceScreen(parent, wideSources));
                return wideSources;
            });
            context.waitTicks(2);
            context.takeScreenshot("codon-function-source-640x360-docked");
            context.runOnClient(client -> searchBox(client.gui.screen()).setValue("long_stage"));
            context.getInput().resizeWindow(1600, 1000);
            context.waitTicks(2);
            context.runOnClient(client -> require(searchBox(client.gui.screen()).getValue().equals("long_stage"),
                "resizing retains the function search query"));
            context.takeScreenshot("codon-function-source-search-after-resize");
            context.runOnClient(client -> verifyFilteredSelection(client.gui.screen(), sourceState));
            context.getInput().resizeWindow(960, 540);
            context.waitTicks(2);
            context.runOnClient(client -> {
                Screen current = client.gui.screen();
                AbstractButton functions = current.children().stream().filter(AbstractButton.class::isInstance)
                    .map(AbstractButton.class::cast).filter(button -> button.visible
                        && button.getMessage().getString().equals("Functions")).findFirst().orElseThrow();
                MouseButtonEvent click = new MouseButtonEvent(functions.getX() + 2, functions.getY() + 2,
                    new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
                current.mouseClicked(click, false);
                current.mouseReleased(click);
                require(searchBox(current).getValue().equals("long_stage") && searchBox(current).visible,
                    "opening the compact function drawer retains the same query");
            });
            context.takeScreenshot("codon-function-source-filtered-drawer");
            context.runOnClient(client -> verifyFilteredSelection(client.gui.screen(), sourceState));
            verifySearchScrollReset(context);
            verifyCodeReader(context);
            context.runOnClient(client -> client.setScreenAndShow(null));
        }
    }

    private static void verifyFilteredSelection(Screen screen, ClientFunctionSourceState sources) {
        require(!clickTreeRow(screen, 2), "filtered-out other_function row is not selectable");
        require(clickTreeRow(screen, 1) && FUNCTION.equals(sources.selected()),
            "the remaining long_stage row is selectable after rebuilding");
    }

    private static boolean clickTreeRow(Screen screen, int row) {
        EditBox search = searchBox(screen);
        // Namespace row 0 starts five pixels below the search box; rows are 18 pixels high.
        MouseButtonEvent click = new MouseButtonEvent(search.getX() + 16, search.getBottom() + 9 + row * 18,
            new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
        boolean handled = screen.mouseClicked(click, false);
        screen.mouseReleased(click);
        return handled;
    }

    private static void verifySearchScrollReset(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280, 720);
        ClientFunctionSourceState sources = context.computeOnClient(client -> {
            var state = new ClientFunctionSourceState();
            state.open();
            long request = state.drainRequests().getFirst().requestId();
            List<FunctionId> functions = java.util.stream.IntStream.range(0, 32)
                .mapToObj(index -> new FunctionId("codon_test", "match_%02d".formatted(index))).toList();
            state.accept(new ClientFunctionSourceState.ListPage(request, ClientFunctionSourceState.Status.READY,
                0, true, functions));
            var screen = new FunctionSourceScreen(new Screen(Component.empty()) { }, state);
            client.setScreenAndShow(screen);
            EditBox search = searchBox(screen);
            screen.mouseScrolled(search.getX() + 16, search.getBottom() + 10, 0, -1);
            require(state.browseView().treeOffset() > 0, "fixture begins with a saved nonzero tree scroll");
            search.setValue("match");
            return state;
        });
        context.getInput().resizeWindow(1600, 1000);
        context.waitTicks(2);
        context.runOnClient(client -> require(searchBox(client.gui.screen()).getValue().equals("match"),
            "scroll regression retains the new query through resize"));
        context.takeScreenshot("codon-function-source-query-scroll-after-resize");
        context.runOnClient(client -> require(clickTreeRow(client.gui.screen(), 1)
            && new FunctionId("codon_test", "match_00").equals(sources.selected()),
            "query change stays at the first filtered result after resizing; selected=" + sources.selected()));
    }

    private static void verifyCodeReader(ClientGameTestContext context) {
        context.getInput().resizeWindow(1600, 1000);
        ClientFunctionSourceState sources = context.computeOnClient(client -> {
            client.options.guiScale().set(2);
            client.resizeGui();
            var state = loadedSource();
            state.rememberBrowseView(0, 0, 3, -1, 0);
            var debugger = require(CodonClientMod.state(), "debugger state exists");
            debugger.applyPause(new PauseSnapshot(new SourceLocation.Function(new FunctionLocation(FUNCTION, 4)),
                CommandSnippet.plain("say source_reader"), 0, List.of(), List.of(), PauseReason.BREAKPOINT));
            Screen screen = new FunctionSourceScreen(new Screen(Component.empty()) { }, state);
            client.setScreenAndShow(screen);
            Style code = Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("codon", "code")));
            int advance = client.font.width(Component.literal("W").setStyle(code));
            require(advance == 5, "code resource font has a five-pixel ASCII advance, actual " + advance);
            for (char ch = 32; ch < 127; ch++) require(client.font.width(Component.literal(String.valueOf(ch)).setStyle(code)) == advance,
                "ASCII glyph has fixed advance: " + ch);
            require(client.font.width(Component.literal("한글😀").setStyle(code)) > 0, "Unicode text retains glyph geometry");
            return state;
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-function-source-code-stop-and-selection");
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            screen.setFocused(null);
            screen.keyPressed(new KeyEvent(InputConstants.KEY_END, 0, 0));
            screen.keyPressed(new KeyEvent(InputConstants.KEY_RIGHT, 0, 0));
        });
        context.waitTicks(1);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            EditBox find = sourceSearchBox(screen);
            MouseButtonEvent click = new MouseButtonEvent(find.getX() + 48 - 5 + 45 - 30 + 5,
                find.getBottom() + 5 + 4 * 18 + 7, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            require(screen.mouseClicked(click, false), "follows the visible underlined function reference");
            screen.mouseReleased(click);
            require(new FunctionId("codon_test", "other_function").equals(sources.selected()), "reference selects the loaded target");
        });
        context.waitTicks(1);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            AbstractButton back = screen.children().stream().filter(AbstractButton.class::isInstance).map(AbstractButton.class::cast)
                .filter(button -> button.visible && button.getMessage().getString().equals("Back")).findFirst().orElseThrow();
            MouseButtonEvent click = new MouseButtonEvent(back.getX() + 2, back.getY() + 2,
                new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            screen.mouseClicked(click, false);
            screen.mouseReleased(click);
            require(FUNCTION.equals(sources.selected()) && sources.browseView().selectedLine() == 5
                && sources.browseView().horizontalOffset() == 30, "Back restores caller line and horizontal viewport");
            long request = sources.drainRequests().stream().filter(ClientFunctionSourceState.Request.ReadFunction.class::isInstance)
                .map(ClientFunctionSourceState.Request.ReadFunction.class::cast).filter(read -> read.function().equals(FUNCTION))
                .reduce((first, last) -> last).orElseThrow().requestId();
            sources.accept(new ClientFunctionSourceState.SourcePage(request, ClientFunctionSourceState.Status.READY, FUNCTION,
                "gametest", "source-preview", false, 0, true, List.of(COMMAND, "", "# Source comment · 한글",
                "say source_reader", "function codon_test:other_function")));
            screen.keyPressed(new KeyEvent(InputConstants.KEY_HOME, 0, 0));
            screen.keyPressed(new KeyEvent(InputConstants.KEY_DOWN, 0, 0));
            screen.keyPressed(new KeyEvent(InputConstants.KEY_DOWN, 0, 0));
        });
        context.waitTicks(1);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            screen.setFocused(null);
            require(screen.keyPressed(new KeyEvent(InputConstants.KEY_DOWN, 0, 0)), "arrow key navigates source lines");
            require(sources.browseView().selectedLine() == 4, "keyboard selection uses original file line numbers");
            screen.keyPressed(new KeyEvent(InputConstants.KEY_HOME, 0, 0));
            screen.mouseScrolled(screen.width - 120, 180, -10, 0);
            require(sources.browseView().horizontalOffset() > 0, "horizontal wheel exposes the long source tail");
            require(sources.browseView().selectedLine() == 1, "horizontal movement preserves the selected original line");
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-function-source-code-horizontal");
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            EditBox find = sourceSearchBox(screen);
            screen.setFocused(find);
            var debugger = require(CodonClientMod.state(), "debugger state exists");
            var location = new SourceLocation.Function(new FunctionLocation(FUNCTION, 1));
            long request = debugger.stagePreviews().begin(location);
            debugger.stagePreviews().accept(request, location, ClientStagePreviewState.Status.READY, COMMAND,
                List.of(new ClientStagePreviewState.StageSpan(0, 0, COMMAND.length(), true)));
            var acknowledged = debugger.stagePreviews().get(location);
            find.setValue("preview_value");
            require(debugger.stagePreviews().get(location) == acknowledged,
                "finding another match on the same line retains its acknowledged preview instead of reparsing");
            require(sources.browseView().selectedLine() == 1 && sources.browseView().horizontalOffset() > 0,
                "find moves to a match beyond the initial horizontal viewport");
            require(sources.document().lines().getFirst().equals(COMMAND), "navigation leaves source bytes unchanged");
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-function-source-code-find-tail");
        context.getInput().resizeWindow(960, 720);
        context.waitTicks(2);
        context.runOnClient(client -> {
            client.options.guiScale().set(3);
            client.resizeGui();
            Screen screen = client.gui.screen();
            require(sourceSearchBox(screen).getValue().equals("preview_value"), "source find survives a compact rebuild");
            require(sources.browseView().horizontalOffset() > 0, "horizontal viewport survives resize");
            sourceSearchBox(screen).setValue("source");
            screen.setFocused(sourceSearchBox(screen));
            screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
            require(sources.browseView().selectedLine() == 3, "next result includes comments at the original line");
            screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 1));
            require(sources.browseView().selectedLine() == 1, "Shift+Enter returns to the previous literal result");
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-function-source-code-find-compact");
        context.runOnClient(client -> {
            var debugger = require(CodonClientMod.state(), "debugger state exists");
            debugger.applyResume();
            sourceSearchBox(client.gui.screen()).setValue("not_present");
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-function-source-code-find-empty-resumed");
    }

    private static EditBox sourceSearchBox(Screen screen) {
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).skip(1).findFirst().orElseThrow();
    }

    private static KeyMapping key(String name, int code, KeyMapping.Category category) {
        return new KeyMapping("key.codon." + name, InputConstants.Type.KEYBOARD, code, category);
    }

    private static ClientFunctionSourceState loadedSource() {
        ClientFunctionSourceState sources = new ClientFunctionSourceState();
        sources.select(FUNCTION);
        long request = ((ClientFunctionSourceState.Request.ReadFunction) sources.drainRequests().getFirst()).requestId();
        sources.accept(new ClientFunctionSourceState.SourcePage(request, ClientFunctionSourceState.Status.READY, FUNCTION,
            "gametest", "source-preview", false, 0, true, List.of(COMMAND, "", "# Source comment · 한글", "say source_reader", "function codon_test:other_function")));
        sources.open();
        long listRequest = ((ClientFunctionSourceState.Request.ListFunctions) sources.drainRequests().getFirst()).requestId();
        sources.accept(new ClientFunctionSourceState.ListPage(listRequest, ClientFunctionSourceState.Status.READY,
            0, true, List.of(FUNCTION, new FunctionId("codon_test", "other_function"))));
        return sources;
    }

    private static EditBox searchBox(Screen screen) {
        return screen.children().stream().filter(EditBox.class::isInstance)
            .map(EditBox.class::cast).findFirst().orElseThrow();
    }

    private static <T> T require(T value, String message) {
        if (value == null) throw new AssertionError(message);
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
