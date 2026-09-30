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
import works.nuty.codon.client.ui.ScaledCodonScreen;
import works.nuty.codon.client.ui.DebuggerTheme;
import works.nuty.codon.client.ui.layout.SourceLineLayout;
import works.nuty.codon.client.state.DebuggerPreferences;
import works.nuty.codon.core.model.FunctionId;
import works.nuty.codon.core.model.FunctionLocation;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.BreakpointCondition;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;

/** Renders original source rows with inline stage markers and the default Minecraft font. */
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
            int firstMarker = context.computeOnClient(client -> 66 + client.font.width(COMMAND.substring(0, 8)) + SourceLineLayout.MARKER_WIDTH / 2);
            context.getInput().setCursorPos(firstMarker * 3, 133 * 3);
            context.waitTicks(2);
            context.takeScreenshot("codon-function-source-disabled-hover");
            context.getInput().setCursorPos(0, 0);
            context.runOnClient(client -> {
                var debugger = require(CodonClientMod.state(), "debugger state exists");
                var location = new SourceLocation.Function(new FunctionLocation(FUNCTION, 1));
                BreakpointTarget target = BreakpointTarget.stage(location, 0, COMMAND);
                MouseButtonEvent marker = new MouseButtonEvent(firstMarker, 133,
                    new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
                screen.mouseClicked(marker, false);
                screen.mouseReleased(marker);
                require(debugger.breakpoints().pending(target), "inline marker uses the authoritative saved stage target");
                require(debugger.breakpoints().get(target).condition().equals(BreakpointCondition.event(BreakpointCondition.Kind.CREATED)),
                    "toggling a disabled inline marker retains its saved condition");
                screen.mouseScrolled(100, 130, -10, 0);
            });
            context.waitTicks(2);
            context.takeScreenshot("codon-function-source-320x240-inline-scrolled");
            context.runOnClient(client -> {
                int secondText = 66 + client.font.width(COMMAND.substring(0, 66)) + 2 * SourceLineLayout.MARKER_WIDTH - 300 + 4;
                MouseButtonEvent click = new MouseButtonEvent(secondText, 133,
                    new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
                require(screen.mouseClicked(click, false), "selects horizontally scrolled inline stage text without toggling its marker");
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
            verifyInlineStagesBetweenSourceRows(context);
            verifyFinalRowAtMinimumHeight(context);
            verifyNestedFunctionLinks(context);
            verifyCodeReader(context);
            verifyHoverOnlyStages(context);
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

    private static void verifyInlineStagesBetweenSourceRows(ClientGameTestContext context) {
        context.getInput().resizeWindow(960, 720);
        String command = "execute as @e[tag=small_preview] run say next_stage_command";
        String original = "  " + command + "  ";
        ClientFunctionSourceState sources = context.computeOnClient(client -> {
            client.options.guiScale().set(3);
            client.resizeGui();
            var state = new ClientFunctionSourceState();
            state.select(FUNCTION);
            long request = state.drainRequests().getFirst().requestId();
            state.accept(new ClientFunctionSourceState.SourcePage(request, ClientFunctionSourceState.Status.READY,
                FUNCTION, "gametest", "inline-stages", false, 0, true,
                List.of("# stages stay inside line 2", original, "say end")));
            state.rememberBrowseView(0, 0, 2, -1, 0);
            client.setScreenAndShow(new FunctionSourceScreen(new ScaledCodonScreen(Component.empty(), new DebuggerPreferences()) { }, state));
            return state;
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            var debugger = require(CodonClientMod.state(), "client debugger state is initialized");
            var location = new SourceLocation.Function(new FunctionLocation(FUNCTION, 2));
            long request = debugger.stagePreviews().begin(location);
            int run = command.indexOf("run");
            debugger.stagePreviews().accept(request, location, ClientStagePreviewState.Status.READY, command,
                List.of(new ClientStagePreviewState.StageSpan(0, 0, run - 1, false),
                    new ClientStagePreviewState.StageSpan(1, run, command.length(), true)));
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            screen.mouseScrolled(100, 148, -5, 0);
        });
        context.waitTicks(1);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            int marker = 66 + client.font.width(original.substring(0, 2 + command.indexOf("run")))
                + SourceLineLayout.MARKER_WIDTH - sources.browseView().horizontalOffset();
            MouseButtonEvent click = new MouseButtonEvent(marker + SourceLineLayout.MARKER_WIDTH + 4, 151,
                new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            screen.mouseClicked(click, false);
            screen.mouseReleased(click);
            require(sources.browseView().selectedLine() == 2 && sources.browseView().selectedStageIndex() == 1,
                "default font and original indentation locate the second inline stage after horizontal scroll");
            require(sources.document().lines().get(1).equals(original), "inline markers preserve original spaces and text");
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-function-source-inline-second-line");
        context.runOnClient(client -> {
            var screen = (ScaledCodonScreen) client.gui.screen();
            screen.uiPreferences().setCustomUiScale(6);
            screen.uiPreferences().setUiScaleMode(DebuggerPreferences.UiScaleMode.CUSTOM);
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            // Rebuilding the screen requests a fresh parse; reply for this synthetic, uninstalled function.
            var debugger = require(CodonClientMod.state(), "debugger state exists");
            var location = new SourceLocation.Function(new FunctionLocation(FUNCTION, 2));
            long request = debugger.stagePreviews().begin(location);
            int run = command.indexOf("run");
            debugger.stagePreviews().accept(request, location, ClientStagePreviewState.Status.READY, command,
                List.of(new ClientStagePreviewState.StageSpan(0, 0, run - 1, false),
                    new ClientStagePreviewState.StageSpan(1, run, command.length(), true)));
        });
        context.waitTicks(1);
        double[] nativePoint = context.computeOnClient(client -> {
            var screen = (ScaledCodonScreen) client.gui.screen();
            require(screen.width == 640 && screen.height == 480, "viewer inherits its parent's independent 1.5x scale");
            screen.mouseScrolled(100, 140, 1000, 0);
            EditBox find = sourceSearchBox(screen);
            double x = find.getX() + 43 + client.font.width(original.substring(0, 10)) + SourceLineLayout.MARKER_WIDTH + 4;
            double y = find.getBottom() + 12 + 18;
            var window = client.getWindow();
            return new double[]{screen.uiScale().toGame(x) * window.getScreenWidth() / window.getGuiScaledWidth(),
                screen.uiScale().toGame(y) * window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(nativePoint[0], nativePoint[1]);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
        context.takeScreenshot("codon-function-source-custom-scale-inline");
        context.runOnClient(client -> require(sources.browseView().selectedLine() == 2
            && sources.browseView().selectedStageIndex() == 0, "native input hits the inline stage in independent Codon scale: " + sources.browseView()));
        context.runOnClient(client -> ((ScaledCodonScreen) client.gui.screen()).uiPreferences().resetUiScale());
        context.getInput().setCursorPos(0, 0);
        context.waitTicks(2);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            MouseButtonEvent click = new MouseButtonEvent(200, 169,
                new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            screen.mouseClicked(click, false);
            screen.mouseReleased(click);
            require(sources.browseView().selectedLine() == 3,
                "the following original source line remains immediately below the inline stage row");
        });
    }

    private static void verifyFinalRowAtMinimumHeight(ClientGameTestContext context) {
        ClientFunctionSourceState sources = context.computeOnClient(client -> {
            var state = new ClientFunctionSourceState();
            state.select(FUNCTION);
            long request = state.drainRequests().getFirst().requestId();
            List<String> lines = new java.util.ArrayList<>(java.util.Collections.nCopies(7, "# previous line"));
            lines.add(COMMAND);
            state.accept(new ClientFunctionSourceState.SourcePage(request, ClientFunctionSourceState.Status.READY,
                FUNCTION, "gametest", "eof-inline", false, 0, true, lines));
            state.rememberBrowseView(0, 7, -1, -1, 0);
            Screen screen = new FunctionSourceScreen(new Screen(Component.empty()) { }, state);
            client.setScreenAndShow(screen);
            return state;
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            MouseButtonEvent click = new MouseButtonEvent(200, 187,
                new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            screen.mouseClicked(click, false);
            screen.mouseReleased(click);
            require(sources.browseView().selectedLine() == 8 && sources.browseView().horizontalOffset() == 0,
                "minimum-height source click selects EOF rather than starting an overlapping scrollbar drag");
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            var debugger = require(CodonClientMod.state(), "debugger state exists");
            var location = new SourceLocation.Function(new FunctionLocation(FUNCTION, 8));
            long request = debugger.stagePreviews().begin(location);
            debugger.stagePreviews().accept(request, location, ClientStagePreviewState.Status.READY, COMMAND,
                List.of(new ClientStagePreviewState.StageSpan(0, 0, 65, false),
                    new ClientStagePreviewState.StageSpan(1, 66, COMMAND.length(), true)));
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            int marker = 66 + client.font.width(COMMAND.substring(0, 8)) + SourceLineLayout.MARKER_WIDTH / 2;
            MouseButtonEvent click = new MouseButtonEvent(marker, 187,
                new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            screen.mouseClicked(click, false);
            screen.mouseReleased(click);
            var target = BreakpointTarget.stage(new SourceLocation.Function(new FunctionLocation(FUNCTION, 8)), 0, COMMAND);
            require(sources.browseView().selectedStageIndex() == 0 && CodonClientMod.state().breakpoints().pending(target),
                "the final visible row at EOF exposes an inline stage breakpoint");
        });
        context.waitTicks(1);
        context.takeScreenshot("codon-function-source-320x240-eof-inline");
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            MouseButtonEvent click = new MouseButtonEvent(250, 223,
                new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            screen.mouseClicked(click, false);
            screen.mouseReleased(click);
            require(sources.browseView().horizontalOffset() > 0 && sources.browseView().selectedLine() == 8,
                "the separate scrollbar remains usable without changing EOF selection");
        });
    }

    private static void verifyNestedFunctionLinks(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280, 720);
        List<String> lines = List.of("return run function codon_test:other_function",
            "schedule function codon_test:other_function 1t", "say function codon_test:other_function",
            "# return run function codon_test:other_function");
        ClientFunctionSourceState sources = context.computeOnClient(client -> {
            client.options.guiScale().set(2);
            client.resizeGui();
            var state = loadedSource();
            state.refreshSource();
            long request = state.drainRequests().getFirst().requestId();
            state.accept(new ClientFunctionSourceState.SourcePage(request, ClientFunctionSourceState.Status.READY,
                FUNCTION, "gametest", "nested-links", false, 0, true, lines));
            state.rememberBrowseView(0, 0, 1, -1, 0);
            client.setScreenAndShow(new FunctionSourceScreen(new Screen(Component.empty()) { }, state));
            return state;
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-function-source-nested-function-links");
        for (int row = 0; row < 2; row++) {
            int line = row;
            context.runOnClient(client -> {
                Screen screen = client.gui.screen();
                EditBox find = sourceSearchBox(screen);
                String prefix = line == 0 ? "return run function " : "schedule function ";
                MouseButtonEvent click = new MouseButtonEvent(find.getX() + 43 + client.font.width(prefix) + 5,
                    find.getBottom() + 12 + line * 18, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
                screen.mouseClicked(click, false);
                screen.mouseReleased(click);
                require(new FunctionId("codon_test", "other_function").equals(sources.selected()),
                    "nested function reference follows the loaded target on row " + (line + 1));
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
                long request = sources.drainRequests().stream().filter(ClientFunctionSourceState.Request.ReadFunction.class::isInstance)
                    .map(ClientFunctionSourceState.Request.ReadFunction.class::cast).filter(read -> read.function().equals(FUNCTION))
                    .reduce((first, last) -> last).orElseThrow().requestId();
                sources.accept(new ClientFunctionSourceState.SourcePage(request, ClientFunctionSourceState.Status.READY,
                    FUNCTION, "gametest", "nested-links", false, 0, true, lines));
            });
            context.waitTicks(1);
        }
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            EditBox find = sourceSearchBox(screen);
            for (int row = 2; row < 4; row++) {
                String prefix = row == 2 ? "say function " : "# return run function ";
                MouseButtonEvent click = new MouseButtonEvent(find.getX() + 43 + client.font.width(prefix) + 5,
                    find.getBottom() + 12 + row * 18, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
                screen.mouseClicked(click, false);
                screen.mouseReleased(click);
                require(FUNCTION.equals(sources.selected()), "function text in say/comment does not become a false link");
            }
        });
    }

    private static void verifyHoverOnlyStages(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280, 720);
        String command = "execute as @s at @s run say hover";
        var location = new SourceLocation.Function(new FunctionLocation(FUNCTION, 1));
        List<BreakpointDefinition> installed = List.of(
            BreakpointDefinition.plain(BreakpointTarget.stage(location, 0, command))
                .withCondition(BreakpointCondition.event(BreakpointCondition.Kind.CREATED)),
            BreakpointDefinition.plain(BreakpointTarget.stage(location, 1, command)).withEnabled(false));
        context.runOnClient(client -> {
            client.options.guiScale().set(2);
            client.resizeGui();
            var debugger = require(CodonClientMod.state(), "debugger exists for hover checks");
            debugger.applyResume();
            debugger.breakpoints().reset();
            debugger.breakpoints().acceptPage(1, 0, true, installed);
            var sources = new ClientFunctionSourceState();
            sources.select(FUNCTION);
            long request = sources.drainRequests().getFirst().requestId();
            sources.accept(new ClientFunctionSourceState.SourcePage(request, ClientFunctionSourceState.Status.READY,
                FUNCTION, "gametest", "hover-only", false, 0, true, List.of(command, "say below")));
            sources.rememberBrowseView(0, 0, 1, 0, 0);
            var preferences = new DebuggerPreferences();
            DebuggerTheme.usePreferences(preferences);
            client.setScreenAndShow(new FunctionSourceScreen(new ScaledCodonScreen(Component.empty(), preferences) { }, sources));
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            var previews = CodonClientMod.state().stagePreviews();
            long request = previews.begin(location);
            require(previews.accept(request, location, ClientStagePreviewState.Status.READY, command,
                List.of(new ClientStagePreviewState.StageSpan(0, 0, 13, false),
                    new ClientStagePreviewState.StageSpan(1, 14, 19, false),
                    new ClientStagePreviewState.StageSpan(2, 20, command.length(), true))), "hover fixture spans accepted");
        });
        context.waitTicks(2);
        int[] geometry = context.computeOnClient(client -> {
            Screen screen = client.gui.screen();
            EditBox find = sourceSearchBox(screen);
            int codeX = find.getX() + 43;
            return new int[]{screen.width, screen.height, codeX + client.font.width(command.substring(0, 8)),
                codeX + client.font.width(command.substring(0, 14)) + SourceLineLayout.MARKER_WIDTH,
                codeX + client.font.width(command.substring(0, 20)) + 2 * SourceLineLayout.MARKER_WIDTH,
                find.getBottom() + 5};
        });
        for (boolean paused : new boolean[]{false, true}) {
            String row = paused ? "paused" : "browsed";
            context.runOnClient(client -> {
                var debugger = CodonClientMod.state();
                if (paused) debugger.applyPause(new PauseSnapshot(location, CommandSnippet.plain(command),
                    0, List.of(), List.of(), PauseReason.BREAKPOINT));
            });
            context.getInput().setCursorPos(0, 0);
            context.waitTicks(2);
            assertStageMarkerPixels(context, geometry, -1, "codon-function-source-hover-" + row + "-hidden");
            for (int stage = 0; stage < 3; stage++) {
                final int targetStage = stage;
                int center = geometry[2 + stage] + SourceLineLayout.MARKER_WIDTH / 2;
                double[] pointer = context.computeOnClient(client -> {
                    var screen = (ScaledCodonScreen) client.gui.screen();
                    var window = client.getWindow();
                    return new double[]{screen.uiScale().toGame(center) * window.getScreenWidth() / window.getGuiScaledWidth(),
                        screen.uiScale().toGame(geometry[5] + 9) * window.getScreenHeight() / window.getGuiScaledHeight()};
                });
                context.getInput().setCursorPos(pointer[0], pointer[1]);
                context.waitTicks(2);
                assertStageMarkerPixels(context, geometry, stage, "codon-function-source-hover-" + row + "-stage-" + stage);
                context.runOnClient(client -> {
                    var breakpoints = CodonClientMod.state().breakpoints();
                    require(java.util.Set.copyOf(breakpoints.definitions()).equals(java.util.Set.copyOf(installed)),
                        "hover preserves enabled state and saved conditions");
                    var target = BreakpointTarget.stage(location, targetStage, command);
                    require(!breakpoints.pending(target), "hover does not send a breakpoint request");
                    if (paused) {
                        Screen screen = client.gui.screen();
                        MouseButtonEvent click = new MouseButtonEvent(center, geometry[5] + 9,
                            new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
                        require(screen.mouseClicked(click, false), "hovered stage control is clickable");
                        screen.mouseReleased(click);
                        require(breakpoints.pending(target), "hovered control targets its original stage, including a missing breakpoint");
                        require(java.util.Set.copyOf(breakpoints.definitions()).equals(java.util.Set.copyOf(installed)),
                            "click retains acknowledged definitions until the server responds");
                    }
                });
                context.getInput().setCursorPos(0, 0);
                context.waitTicks(2);
                assertStageMarkerPixels(context, geometry, -1, "codon-function-source-hover-" + row + "-leave-" + stage);
            }
        }
        context.runOnClient(client -> CodonClientMod.state().applyResume());
    }

    private static void assertStageMarkerPixels(ClientGameTestContext context, int[] geometry, int hovered, String name) {
        try {
            var image = javax.imageio.ImageIO.read(context.takeScreenshot(name).toFile());
            double scaleX = (double) image.getWidth() / geometry[0];
            double scaleY = (double) image.getHeight() / geometry[1];
            for (int stage = 0; stage < 3; stage++) {
                int color = (stage == 0 ? DebuggerTheme.RED : DebuggerTheme.MUTED) & 0xFFFFFF;
                boolean visible = false;
                for (int x = (int) Math.ceil((geometry[2 + stage] + 5) * scaleX);
                     x < (int) Math.floor((geometry[2 + stage] + 14) * scaleX); x++) {
                    for (int y = (int) Math.ceil((geometry[5] + 5) * scaleY);
                         y < (int) Math.floor((geometry[5] + 14) * scaleY); y++)
                        visible |= (image.getRGB(x, y) & 0xFFFFFF) == color;
                }
                require(visible == (stage == hovered), name + ": only the hovered stage icon is rendered, stage=" + stage);
            }
        } catch (java.io.IOException error) {
            throw new AssertionError("Cannot inspect stage marker capture", error);
        }
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
            require(client.font.width("W") > client.font.width("i"), "viewer uses the proportional default Minecraft font");
            require(client.font.width("한글😀") > 0, "Unicode text retains default font glyph geometry");
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
            MouseButtonEvent click = new MouseButtonEvent(find.getX() + 48 - 5 + client.font.width("function ") - 30 + 5,
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
