package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.client.ui.ScreenLayers;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.state.ClientFunctionSourceState;
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.client.state.DebuggerPreferences;
import works.nuty.codon.client.ui.FunctionSourceScreen;
import works.nuty.codon.client.ui.ScaledCodonScreen;
import works.nuty.codon.core.model.*;

/** Pointer, scroll and splitter regressions using original-source fixtures. */
@SuppressWarnings("UnstableApiUsage")
public final class FunctionSourceInteractionGameTest implements FabricClientGameTest {
    private static final FunctionId FUNCTION = new FunctionId("codon_test", "source_interaction");
    private static final String COMMAND = "execute as @s at @s run say " + "long_source_tail_".repeat(12);

    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1280, 720);
            context.getInput().setCursorPos(0, 0);
            ClientFunctionSourceState sources = context.computeOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
                var state = source();
                var debugger = CodonClientMod.state();
                debugger.applyResume();
                debugger.breakpoints().reset();
                var location = new SourceLocation.Function(new FunctionLocation(FUNCTION, 1));
                debugger.breakpoints().acceptPage(1, 0, true, List.of(
                    BreakpointDefinition.plain(BreakpointTarget.whole(location)),
                    BreakpointDefinition.plain(BreakpointTarget.stage(location, 0, COMMAND)),
                    BreakpointDefinition.plain(BreakpointTarget.stage(location, 1, COMMAND)).withEnabled(false)));
                client.setScreenAndShow(new FunctionSourceScreen(
                    new ScaledCodonScreen(Component.empty(), new DebuggerPreferences()) { }, state));
                return state;
            });
            world.getConnection().waitForChunksRender();
            context.waitTicks(2);
            context.runOnClient(client -> {
                var location = new SourceLocation.Function(new FunctionLocation(FUNCTION, 1));
                var previews = CodonClientMod.state().stagePreviews();
                long request = previews.begin(location);
                require(previews.accept(request, location, ClientStagePreviewState.Status.READY, COMMAND,
                    List.of(new ClientStagePreviewState.StageSpan(0, 0, 13, false),
                        new ClientStagePreviewState.StageSpan(1, 14, 19, false),
                        new ClientStagePreviewState.StageSpan(2, 20, COMMAND.length(), true))), "fixture preview accepted");
            });
            context.waitTicks(2);
            context.takeScreenshot("codon-source-qa-unhovered");
            context.runOnClient(client -> {
                Screen screen = client.gui.screen();
                EditBox find = find(screen);
                screen.mouseScrolled(find.getX() + 60, find.getBottom() + 12, 2, 0);
                require(sources.browseView().horizontalOffset() > 0,
                    "positive native horizontal delta scrolls the source viewport to the right");
            });
            verifyScrollbars(context, sources);
            verifySplitter(context, sources);
            verifyOcclusion(context, sources);
            verifyKorean(context, sources);
            context.runOnClient(client -> {
                ScreenLayers.close(ScreenLayers.get(client.gui.screen()));
                client.setScreenAndShow(null);
            });
        }
    }

    private static void verifyScrollbars(ClientGameTestContext context, ClientFunctionSourceState sources) {
        // Exercise Minecraft's native callback entry, then the separate Shift+vertical route.
        pointer(context, point(context, "sourceLeft", "sourceLineTop", 80, 9));
        context.runOnClient(client -> {
            int before = sources.browseView().horizontalOffset();
            client.mouseHandler.onScroll(client.getWindow().handle(), 1, 0);
            require(sources.browseView().horizontalOffset() > before, "Minecraft native X callback preserves rightward motion");
            client.mouseHandler.onScroll(client.getWindow().handle(), -1, 0);
            require(sources.browseView().horizontalOffset() == before, "native left reverses native right");
            Screen screen = client.gui.screen();
            screen.mouseScrolled(find(screen).getX() + 80, find(screen).getBottom() + 12, .01, 0);
            screen.mouseScrolled(find(screen).getX() + 80, find(screen).getBottom() + 12, .01, 0);
            screen.mouseScrolled(find(screen).getX() + 80, find(screen).getBottom() + 12, .02, 0);
            require(sources.browseView().horizontalOffset() == before + 1, "fractional trackpad deltas accumulate");
        });
        context.getInput().holdKey(InputConstants.KEY_LSHIFT);
        context.runOnClient(client -> {
            int before = sources.browseView().horizontalOffset();
            var screen = client.gui.screen();
            screen.mouseScrolled(find(screen).getX() + 80, find(screen).getBottom() + 12, 0, -1);
            require(sources.browseView().horizontalOffset() == before + 30, "Shift+wheel-down moves right");
            screen.mouseScrolled(find(screen).getX() + 80, find(screen).getBottom() + 12, 1, -1);
            require(sources.browseView().horizontalOffset() == before + 60, "native X takes precedence with Shift held");
        });
        context.getInput().releaseKey(InputConstants.KEY_LSHIFT);
        context.getInput().scroll(-1);
        context.waitTicks(2);
        context.runOnClient(client -> require(sources.browseView().lineOffset() == 3, "native vertical wheel advances three original rows"));
        int horizontal = sources.browseView().horizontalOffset();
        double[] vertical = context.computeOnClient(client -> {
            Screen screen = client.gui.screen();
            return new double[]{integer(screen, "left") + integer(screen, "panelWidth") - 13, invoke(screen, "sourceLineTop") + 12};
        });
        pointer(context, vertical);
        context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.runOnClient(client -> require(sources.browseView().lineOffset() == 3, "grabbing the vertical thumb does not jump"));
        pointer(context, new double[]{vertical[0], vertical[1] + 900});
        context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.runOnClient(client -> {
            int rows = invoke(client.gui.screen(), "sourceRows");
            require(sources.browseView().lineOffset() == 120 - rows, "vertical drag reaches the final original row");
            require(sources.browseView().horizontalOffset() == horizontal, "vertical dragging preserves horizontal offset");
        });
        context.takeScreenshot("codon-source-qa-vertical-eof");
        double[] track = context.computeOnClient(client -> {
            Screen screen = client.gui.screen();
            return new double[]{invoke(screen, "codeRight") - 4, invoke(screen, "horizontalTrackY") + 3};
        });
        int line = sources.browseView().lineOffset();
        pointer(context, track);
        context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
        pointer(context, new double[]{track[0] + 900, track[1]});
        context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.runOnClient(client -> {
            require(sources.browseView().horizontalOffset() == invoke(client.gui.screen(), "maxHorizontalOffset"), "horizontal drag reaches the right edge");
            require(sources.browseView().lineOffset() == line, "horizontal dragging preserves vertical offset");
        });
        context.takeScreenshot("codon-source-qa-both-scrollbars");
    }

    private static void verifySplitter(ClientGameTestContext context, ClientFunctionSourceState sources) {
        double[] splitter = context.computeOnClient(client -> {
            Screen screen = client.gui.screen();
            return new double[]{integer(screen, "left") + integer(screen, "treeWidth"), integer(screen, "top") + 60};
        });
        pointer(context, splitter);
        context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
        pointer(context, new double[]{splitter[0] + 900, splitter[1]});
        context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.runOnClient(client -> require(integer(client.gui.screen(), "treeWidth") == integer(client.gui.screen(), "panelWidth") - 300,
            "sidebar maximum keeps the source controls reachable"));
        context.takeScreenshot("codon-source-qa-sidebar-max");
        int requested = sources.treeWidth();
        context.getInput().resizeWindow(960, 540);
        context.waitTicks(2);
        context.runOnClient(client -> require(sources.treeWidth() == requested, "compact drawer preserves desired sidebar width"));
        // Drawer cannot expose the underlying source hit regions.
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            var functions = screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                .filter(widget -> widget.visible && widget.getMessage().getString().equals("Functions")).findFirst().orElseThrow();
            click(screen, functions.getX() + 2, functions.getY() + 2);
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            require(!find(screen).visible, "drawer covers Find controls");
            require(list(screen, "stageHits").isEmpty() && list(screen, "lineHits").isEmpty(), "drawer clears source hit regions");
        });
        context.takeScreenshot("codon-source-qa-drawer");
        context.getInput().resizeWindow(1600, 1000);
        context.waitTicks(2);
        context.runOnClient(client -> require(integer(client.gui.screen(), "treeWidth") == requested,
            "wider viewport restores the requested sidebar width"));
        // Same state survives close/re-entry; drag remains native under custom scale.
        context.runOnClient(client -> {
            var preferences = ((ScaledCodonScreen) client.gui.screen()).uiPreferences();
            client.setScreenAndShow(new FunctionSourceScreen(new ScaledCodonScreen(Component.empty(), preferences) { }, sources));
            preferences.setCustomUiScale(6);
            preferences.setUiScaleMode(DebuggerPreferences.UiScaleMode.CUSTOM);
        });
        context.waitTicks(2);
        double[] custom = context.computeOnClient(client -> {
            Screen screen = client.gui.screen();
            require(integer(screen, "treeWidth") == requested, "re-entry retains sidebar width");
            return new double[]{integer(screen, "left") + requested, integer(screen, "top") + 60};
        });
        pointer(context, custom);
        context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
        pointer(context, new double[]{-50, custom[1]});
        context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.runOnClient(client -> require(sources.treeWidth() == 150, "custom-scale native splitter drag clamps to minimum"));
        context.takeScreenshot("codon-source-qa-custom-scale-sidebar-min");
        context.runOnClient(client -> ((ScaledCodonScreen) client.gui.screen()).uiPreferences().resetUiScale());
        context.waitTicks(2);
    }

    private static void verifyOcclusion(ClientGameTestContext context, ClientFunctionSourceState sources) {
        context.runOnClient(client -> {
            var state = CodonClientMod.state();
            state.applyPause(DebuggerPresentationGameTest.fixture(client));
            var parent = new CodonScreen(DebuggerPresentationGameTest.input(client, state), new DebuggerOverlay(state));
            client.setScreenAndShow(parent);
        });
        context.waitTicks(2);
        CodonScreen parent = context.computeOnClient(client -> (CodonScreen) client.gui.screen());
        // Choose a real HUD widget that will be covered by the docked Source panel.
        AbstractWidget covered = context.computeOnClient(client -> parent.children().stream()
            .filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
            .filter(widget -> widget.visible && widget.getY() >= 106 && widget.getX() >= 24 && widget.getRight() < 776)
            .findFirst().orElseThrow(() -> new AssertionError("fixture contains a HUD control covered by Source")));
        pointer(context, new double[]{covered.getX() + 2, covered.getY() + 2});
        context.runOnClient(client -> require(covered.isHovered(), "underlying HUD control is hoverable before Source opens"));
        context.takeScreenshot("codon-source-qa-parent-before-cover");
        context.runOnClient(client -> client.setScreenAndShow(new FunctionSourceScreen(parent, sources)));
        context.waitTicks(2);
        context.runOnClient(client -> require(parent.children().stream().filter(AbstractWidget.class::isInstance)
            .map(AbstractWidget.class::cast).noneMatch(AbstractWidget::isHovered), "covered parent controls receive no hover"));
        context.takeScreenshot("codon-source-qa-parent-covered");
        context.runOnClient(client -> {
            Screen owner = client.gui.screen();
            ScreenLayers.open(owner, new Screen(Component.literal("Source input occlusion fixture")) { });
        });
        pointer(context, point(context, "sourceLeft", "sourceLineTop", 80, 9));
        context.runOnClient(client -> {
            var owner = client.gui.screen();
            int offset = sources.browseView().horizontalOffset();
            int selected = sources.browseView().selectedLine();
            owner.mouseScrolled(find(owner).getX() + 80, find(owner).getBottom() + 12, -2, -2);
            owner.keyPressed(new KeyEvent(InputConstants.KEY_HOME, 0, 0));
            click(owner, find(owner).getX() + 80, find(owner).getBottom() + 12);
            require(sources.browseView().horizontalOffset() == offset && sources.browseView().selectedLine() == selected,
                "modal blocks source wheel, key and click hit testing");
            require(integer(owner, "hoveredLine") == -1, "modal hides underlying source hover");
        });
        context.takeScreenshot("codon-source-qa-modal-occlusion");
        context.runOnClient(client -> {
            ScreenLayers.close(ScreenLayers.get(client.gui.screen()));
            CodonClientMod.state().applyResume();
        });
    }

    private static void verifyKorean(ClientGameTestContext context, ClientFunctionSourceState sources) {
        String language = context.computeOnClient(client -> client.getLanguageManager().getSelected());
        var reload = context.computeOnClient(client -> {
            client.getLanguageManager().setSelected("ko_kr");
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 200);
        context.getInput().resizeWindow(960, 720);
        context.runOnClient(client -> {
            client.options.guiScale().set(3); client.resizeGui();
            client.setScreenAndShow(new FunctionSourceScreen(new ScaledCodonScreen(Component.empty(), new DebuggerPreferences()) { }, sources));
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            screen.setFocused(null);
            screen.keyPressed(new KeyEvent(InputConstants.KEY_HOME, 0, 0));
            screen.mouseScrolled(invoke(screen, "sourceLeft") + 80, invoke(screen, "sourceLineTop") + 9, -1000, 0);
        });
        context.getInput().setCursorPos(0, 0);
        context.waitTicks(2);
        context.takeScreenshot("codon-source-qa-korean-minimum");
        var restore = context.computeOnClient(client -> {
            client.getLanguageManager().setSelected(language);
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> restore.isDone() && client.gui.overlay() == null, 200);
    }

    private static void click(Screen screen, double x, double y) {
        var event = new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
        screen.mouseClicked(event, false); screen.mouseReleased(event);
    }

    private static double[] point(ClientGameTestContext context, String xMethod, String yMethod, int dx, int dy) {
        return context.computeOnClient(client -> new double[]{invoke(client.gui.screen(), xMethod) + dx, invoke(client.gui.screen(), yMethod) + dy});
    }

    private static void pointer(ClientGameTestContext context, double[] local) {
        double[] nativePoint = context.computeOnClient(client -> {
            Screen screen = client.gui.screen();
            var scale = screen instanceof ScaledCodonScreen scaled ? scaled.uiScale() : null;
            var window = client.getWindow();
            return new double[]{(scale == null ? local[0] : scale.toGame(local[0])) * window.getScreenWidth() / window.getGuiScaledWidth(),
                (scale == null ? local[1] : scale.toGame(local[1])) * window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(nativePoint[0], nativePoint[1]);
        context.waitTicks(2);
    }

    private static int integer(Object screen, String name) {
        try { var field = screen.getClass().getDeclaredField(name); field.setAccessible(true); return field.getInt(screen); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static List<?> list(Object screen, String name) {
        try { var field = screen.getClass().getDeclaredField(name); field.setAccessible(true); return (List<?>) field.get(screen); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static int invoke(Object screen, String name) {
        try { var method = screen.getClass().getDeclaredMethod(name); method.setAccessible(true); return (int) method.invoke(screen); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static ClientFunctionSourceState source() {
        var state = new ClientFunctionSourceState();
        state.select(FUNCTION);
        long request = state.drainRequests().getFirst().requestId();
        List<String> lines = new ArrayList<>();
        lines.add(COMMAND);
        for (int line = 2; line <= 120; line++) lines.add("# source line " + line + " · 한글 English");
        state.accept(new ClientFunctionSourceState.SourcePage(request, ClientFunctionSourceState.Status.READY,
            FUNCTION, "gametest", "source-interaction", false, 0, true, lines));
        state.open();
        long list = state.drainRequests().getFirst().requestId();
        state.accept(new ClientFunctionSourceState.ListPage(list, ClientFunctionSourceState.Status.READY, 0, true,
            List.of(FUNCTION, new FunctionId("codon_test", "other_function"))));
        state.rememberBrowseView(0, 0, 1, 1, 0);
        return state;
    }

    private static EditBox find(Screen screen) {
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
            .skip(1).findFirst().orElseThrow();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
