package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.state.ClientFunctionSourceState;
import works.nuty.codon.client.state.DebuggerPreferences;
import works.nuty.codon.client.ui.FunctionSourceScreen;
import works.nuty.codon.client.ui.ScaledCodonScreen;
import works.nuty.codon.client.ui.ScreenLayers;
import works.nuty.codon.core.model.FunctionId;

/** Native pointer coverage for the Functions tree, using decoded source/list fixtures. */
@SuppressWarnings("UnstableApiUsage")
public final class FunctionListScrollbarGameTest implements FabricClientGameTest {
    private static final List<FunctionId> FUNCTIONS = functions(64);

    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1280, 720);
            context.getInput().setCursorPos(0, 0);
            ClientFunctionSourceState sources = context.computeOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
                CodonClientMod.state().applyResume();
                var state = new ClientFunctionSourceState();
                state.select(FUNCTIONS.getFirst());
                acceptSource(state);
                state.rememberBrowseView(0, 2, 3, -1, 0, 30);
                client.setScreenAndShow(new FunctionSourceScreen(
                    new ScaledCodonScreen(Component.empty(), new DebuggerPreferences()) { }, state));
                acceptList(state, FUNCTIONS);
                return state;
            });
            world.getConnection().waitForChunksRender();
            context.waitTicks(2);
            verifyPointer(context, sources);
            verifyTransitions(context, sources);
            verifyResizeAndDrawer(context, sources);
            context.runOnClient(client -> client.setScreenAndShow(null));
        }
    }

    private static void verifyPointer(ClientGameTestContext context, ClientFunctionSourceState sources) {
        Track initial = context.computeOnClient(client -> track(client.gui.screen()));
        pointer(context, initial.x() + 1, initial.y() + 9);
        context.getInput().scroll(-1);
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(sources.browseView().treeOffset() == 3, "wheel down advances the function list");
            require(sources.browseView().lineOffset() == 2 && sources.browseView().horizontalOffset() == 30,
                "tree wheel preserves both source offsets");
        });
        context.getInput().scroll(1);
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(sources.browseView().treeOffset() == 0, "wheel up returns to the first tree row");
            Screen screen = client.gui.screen();
            screen.mouseScrolled(invoke(screen, "sourceLeft") + 80, invoke(screen, "sourceLineTop") + 9, 1, -1);
            require(sources.browseView().treeOffset() == 0, "source wheel does not scroll the tree");
            screen.setFocused(search(screen));
        });
        int sourceX = sources.browseView().horizontalOffset();
        int sourceY = sources.browseView().lineOffset();
        pointer(context, initial.x() + 1, initial.y() + initial.length() - 1);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            require(sources.browseView().treeOffset() == initial.maximum(), "track click reaches the last tree row");
            require(screen.getFocused() == null, "scrollbar click clears text focus");
            require(sources.selected().equals(FUNCTIONS.getFirst()), "track clicks do not select a function");
            require(sources.browseView().lineOffset() == sourceY && sources.browseView().horizontalOffset() == sourceX,
                "track clicks isolate source scrolling");
        });
        context.takeScreenshot("codon-functions-scrollbar-track-end");
        Track end = context.computeOnClient(client -> track(client.gui.screen()));
        pointer(context, end.x() + 1, end.thumbTop() + 2);
        context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.runOnClient(client -> require(sources.browseView().treeOffset() == end.maximum(), "thumb grab does not jump"));
        pointer(context, end.x() + 100, end.y() - 50);
        context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(sources.browseView().treeOffset() == 0, "captured thumb clamps above the track, outside its width");
            require(sources.browseView().lineOffset() == sourceY && sources.browseView().horizontalOffset() == sourceX,
                "thumb drag preserves the source viewport");
            Screen screen = client.gui.screen();
            click(screen, end.x() - 1, end.y() + 27);
            require(sources.selected().equals(FUNCTIONS.getFirst()), "scrollbar hit padding never selects the covered row");
            click(screen, integer(screen, "left") + 30, end.y() + 45);
            require(sources.selected().equals(FUNCTIONS.get(1)), "ordinary row selection remains reachable");
            acceptSource(sources);
            screen.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
            require(screen.getFocused() == search(screen), "Tab after track focus clearing reaches Search");
            int selected = sources.browseView().selectedLine();
            screen.keyPressed(new KeyEvent(InputConstants.KEY_DOWN, 0, 0));
            require(sources.browseView().selectedLine() == selected, "Search focus retains source keyboard isolation");
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-functions-scrollbar-drag-start");
        context.runOnClient(client -> ScreenLayers.open(client.gui.screen(), new Screen(Component.empty()) { }));
        pointer(context, end.x() + 1, end.y() + end.length() - 1);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.getInput().scroll(-1);
        context.runOnClient(client -> {
            require(sources.browseView().treeOffset() == 0, "modal blocks tree track and wheel input");
            ScreenLayers.close(ScreenLayers.get(client.gui.screen()));
        });
        context.waitTicks(2);
    }

    private static void verifyTransitions(ClientGameTestContext context, ClientFunctionSourceState sources) {
        Track before = context.computeOnClient(client -> track(client.gui.screen()));
        pointer(context, before.x() + 1, before.y() + 2);
        context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.runOnClient(client -> search(client.gui.screen()).setValue("item_01"));
        context.waitTicks(2);
        context.runOnClient(client -> require(trackOrNull(client.gui.screen()) == null,
            "filter removing overflow removes the function track"));
        pointer(context, before.x() + 1, before.y() + 500);
        context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.runOnClient(client -> {
            require(sources.browseView().treeOffset() == 0, "disappearing track cancels capture");
            search(client.gui.screen()).setValue("no_matching_function");
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(trackOrNull(client.gui.screen()) == null, "empty search has no track");
            search(client.gui.screen()).setValue("");
            int rows = invoke(client.gui.screen(), "visibleRows");
            sources.refreshList();
            acceptList(sources, FUNCTIONS.subList(0, rows - 1));
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(trackOrNull(client.gui.screen()) == null, "exactly fitting rows have no scrollbar");
            sources.refreshList();
            acceptList(sources, FUNCTIONS.subList(0, invoke(client.gui.screen(), "visibleRows")));
        });
        context.waitTicks(2);
        Track one = context.computeOnClient(client -> track(client.gui.screen()));
        require(one.maximum() == 1, "one extra row produces an interactive track");
        pointer(context, one.x() + 1, one.y() + one.length() - 1);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
        context.runOnClient(client -> require(sources.browseView().treeOffset() == 1, "one-row overflow remains usable"));
        context.getInput().resizeWindow(1280, 1000);
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(trackOrNull(client.gui.screen()) == null && sources.browseView().treeOffset() == 0,
                "height growth removes overflow and retains its clamped offset");
            sources.refreshList();
            acceptList(sources, FUNCTIONS);
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            click(screen, integer(screen, "left") + 20, integer(screen, "top") + 63);
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            require(trackOrNull(screen) == null, "collapsing the namespace removes overflow");
            click(screen, integer(screen, "left") + 20, integer(screen, "top") + 63);
        });
        context.waitTicks(2);
        context.runOnClient(client -> require(trackOrNull(client.gui.screen()) != null, "expanding restores the tree track"));
    }

    private static void verifyResizeAndDrawer(ClientGameTestContext context, ClientFunctionSourceState sources) {
        context.runOnClient(client -> {
            var preferences = ((ScaledCodonScreen) client.gui.screen()).uiPreferences();
            preferences.setCustomUiScale(7);
            preferences.setUiScaleMode(DebuggerPreferences.UiScaleMode.CUSTOM);
        });
        context.waitTicks(2);
        Track custom = context.computeOnClient(client -> track(client.gui.screen()));
        pointer(context, custom.x() + 1, custom.y() + custom.length() - 1);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
        context.runOnClient(client -> require(sources.browseView().treeOffset() == custom.maximum(),
            "native track click uses fractional custom-scale coordinates"));
        context.takeScreenshot("codon-functions-scrollbar-custom-scale");
        context.runOnClient(client -> ((ScaledCodonScreen) client.gui.screen()).uiPreferences().resetUiScale());
        context.getInput().resizeWindow(960, 720);
        context.waitTicks(2);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            require(trackOrNull(screen) == null, "closed compact drawer does not expose a stale track");
            AbstractWidget button = screen.children().stream().filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast).filter(widget -> widget.visible
                    && widget.getMessage().getString().equals("Functions")).findFirst().orElseThrow();
            click(screen, button.getX() + 2, button.getY() + 2);
        });
        context.waitTicks(2);
        Track drawer = context.computeOnClient(client -> track(client.gui.screen()));
        pointer(context, drawer.x() + 1, drawer.thumbTop() + 2);
        context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
        pointer(context, drawer.x() + 1, drawer.y() - 20);
        context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(sources.browseView().treeOffset() == 0, "compact drawer thumb drag returns to the first row");
            require(list(client.gui.screen(), "lineHits").isEmpty(), "drawer keeps source pointer hits clipped");
            search(client.gui.screen()).setValue("item_01");
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-functions-scrollbar-drawer-filtered");
        context.getInput().resizeWindow(1600, 1000);
        context.waitTicks(2);
        context.runOnClient(client -> require(search(client.gui.screen()).getValue().equals("item_01")
            && trackOrNull(client.gui.screen()) == null, "resizing retains search and removes its overflow"));
    }

    private static List<FunctionId> functions(int count) {
        List<FunctionId> result = new ArrayList<>();
        for (int index = 0; index < count; index++) result.add(new FunctionId("codon_test",
            "item_%02d_with_a_long_label_that_must_not_cover_the_track".formatted(index)));
        return List.copyOf(result);
    }

    private static void acceptList(ClientFunctionSourceState sources, List<FunctionId> functions) {
        var request = sources.drainRequests().stream().filter(ClientFunctionSourceState.Request.ListFunctions.class::isInstance)
            .reduce((first, second) -> second).orElseThrow();
        sources.accept(new ClientFunctionSourceState.ListPage(request.requestId(), ClientFunctionSourceState.Status.READY,
            0, true, functions));
    }

    private static void acceptSource(ClientFunctionSourceState sources) {
        var request = sources.drainRequests().stream().filter(ClientFunctionSourceState.Request.ReadFunction.class::isInstance)
            .reduce((first, second) -> second).orElseThrow();
        sources.accept(new ClientFunctionSourceState.SourcePage(request.requestId(), ClientFunctionSourceState.Status.READY,
            sources.selected(), "gametest", "functions-scrollbar", false, 0, true,
            java.util.Collections.nCopies(80, "# long source content ".repeat(12))));
    }

    private static void pointer(ClientGameTestContext context, double x, double y) {
        double[] point = context.computeOnClient(client -> {
            var screen = (ScaledCodonScreen) client.gui.screen();
            var window = client.getWindow();
            return new double[]{screen.uiScale().toGame(x) * window.getScreenWidth() / window.getGuiScaledWidth(),
                screen.uiScale().toGame(y) * window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(point[0], point[1]);
        context.waitTicks(2);
    }

    private static void click(Screen screen, double x, double y) {
        var event = new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
        screen.mouseClicked(event, false); screen.mouseReleased(event);
    }

    private record Track(int x, int y, int length, int thumb, int offset, int maximum) {
        int thumbTop() { return y + (length - thumb) * offset / maximum; }
    }

    private static Track track(Screen screen) {
        Track result = trackOrNull(screen);
        require(result != null, "overflowing function tree has a rendered interactive track");
        return result;
    }

    private static Track trackOrNull(Screen screen) {
        Object value = ((Map<?, ?>) field(field(screen, "scrollbars"), "tracks")).get("functions");
        return value == null ? null : new Track(invoke(value, "x"), invoke(value, "y"), invoke(value, "length"),
            invoke(value, "thumb"), invoke(value, "offset"), invoke(value, "maximum"));
    }

    private static EditBox search(Screen screen) {
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
    }
    private static int integer(Object object, String name) { return (int) field(object, name); }
    private static List<?> list(Object object, String name) { return (List<?>) field(object, name); }
    private static Object field(Object object, String name) {
        try { var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static int invoke(Object object, String name) {
        try { var method = object.getClass().getDeclaredMethod(name); method.setAccessible(true); return (int) method.invoke(object); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
