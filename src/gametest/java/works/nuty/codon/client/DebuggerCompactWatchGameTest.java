package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.WatchGrouping;
import works.nuty.codon.client.ui.*;
import works.nuty.codon.client.ui.layout.WatchDetailsLayout;
import works.nuty.codon.client.ui.layout.WatchPanelLayout;
import works.nuty.codon.core.model.*;

/** Rendered layout and native hit targets under synthetic observations; no server stepping claim. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerCompactWatchGameTest implements FabricClientGameTest {
    private static final WatchSpec SCORE = new WatchSpec(WatchSpec.Kind.SCORE, "long_compact_score_objective", "");
    private static final WatchSpec NBT = new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:compact", "very.long.nbt.path.to.inspect");
    private static final String LONG_VALUE = "{description:\"" + "long value 한글 ".repeat(32) + "\"}";

    @Override public void runTest(ClientGameTestContext context) {
        int oldScale = context.computeOnClient(client -> client.options.guiScale().get());
        String oldLanguage = context.computeOnClient(client -> client.getLanguageManager().getSelected());
        String oldClipboard = context.computeOnClient(client -> client.keyboardHandler.getClipboard());
        try (TestSingleplayerContext ignored = context.worldBuilder().create()) {
            for (var scenario : List.of(
                new Scenario("en_us", 854, 480, 2, 0), // Reported 427x240 side panel
                new Scenario("en_us", 640, 480, 2, 0), // Minimum 320x240 Details footer
                new Scenario("ko_kr", 854, 480, 2, 0),
                new Scenario("ko_kr", 1280, 800, 2, 0), // Regular layout
                new Scenario("ko_kr", 854, 480, 1, 8), // Custom 2x over game 1x
                new Scenario("en_us", 1280, 800, 3, 8)  // Regular custom 2x over game 3x
            )) check(context, scenario);
        } finally {
            context.runOnClient(client -> {
                client.setScreenAndShow(null);
                client.keyboardHandler.setClipboard(oldClipboard);
                client.options.guiScale().set(oldScale);
                client.resizeGui();
            });
            language(context, oldLanguage);
        }
    }

    private record Scenario(String language, int width, int height, int gameScale, int customScale) { }
    private record Fixture(ClientDebuggerState state, InputManager input, DebuggerOverlay overlay) { }

    private static void check(ClientGameTestContext context, Scenario scenario) {
        language(context, scenario.language());
        context.getInput().resizeWindow(scenario.width(), scenario.height());
        var fixture = context.computeOnClient(client -> {
            client.options.guiScale().set(scenario.gameScale());
            client.resizeGui();
            var state = new ClientDebuggerState();
            if (scenario.customScale() > 0) {
                state.preferences().selectCustomUiScale(client.getWindow().getGuiScale());
                state.preferences().setCustomUiScale(scenario.customScale());
            }
            var base = DebuggerPresentationGameTest.fixture(client);
            state.applyPause(pause(base, 101, PauseReason.BREAKPOINT));
            state.selectSource(0);
            state.watches().grouping(WatchGrouping.Mode.NONE);
            var definitions = new ArrayList<>(List.of(SCORE, NBT));
            for (int i = 0; i < 10; i++) definitions.add(new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:compact", "row_" + i));
            state.watches().addAll(definitions);
            accept(state, "123456789");
            state.applyPause(pause(base, 102, PauseReason.STEP));
            accept(state, "-2147483648");
            var input = DebuggerPresentationGameTest.input(client, state);
            var overlay = new DebuggerOverlay(state);
            client.setScreenAndShow(new CodonScreen(input, overlay));
            return new Fixture(state, input, overlay);
        });
        context.waitTicks(3);
        if (context.computeOnClient(client -> screen(client).width < 600)) {
            click(context, "codon.ui.view");
            click(context, "codon.watch.title");
        }
        String name = "codon-compact-watch-" + scenario.language() + "-" + scenario.width() + "-game-"
            + scenario.gameScale() + "-custom-" + scenario.customScale();
        context.runOnClient(client -> checkWatchGeometry(client, fixture));
        capture(context, name);
        click(context, "codon.watch.details.copy_value");
        context.runOnClient(client -> require(client.keyboardHandler.getClipboard().equals("-2147483648"), "Copy hitbox reaches the current value"));
        click(context, "codon.watch.pin");
        context.runOnClient(client -> require(fixture.state().watches().definitions().getFirst().isPinned(), "Pin hitbox remains accessible"));
        click(context, "codon.watch.unpin");
        context.runOnClient(client -> require(!fixture.state().watches().definitions().getFirst().isPinned(), "Unpin uses its own target"));

        // Scroll the real panel, then return to its first row without changing definitions.
        context.runOnClient(client -> {
            var bounds = fixture.overlay().watchPanel().scrollBounds();
            for (int i = 0; i < 20; i++) require(screen(client).mouseScrolled(bounds.x() + 5, bounds.y() + 5, 0, -1), "Watch wheel is handled");
        });
        context.waitTicks(3);
        context.runOnClient(client -> {
            require(fixture.overlay().watchPanel().offset() > 0, "Overflow reaches later rows");
            checkWatchGeometry(client, fixture);
        });
        capture(context, name + "-overflow");
        context.runOnClient(client -> fixture.overlay().watchPanel().select(fixture.state().watches().findId(SCORE)));
        context.waitTicks(3);
        click(context, "codon.watch.edit");
        context.runOnClient(client -> require(screen(client) instanceof WatchScreen, "Edit hitbox opens the editor"));
        click(context, "codon.watch.close");
        clickLabel(context, context.computeOnClient(client -> Component.translatable("codon.watch.inspect", WatchFormatting.specification(NBT).getString()).getString()));
        context.runOnClient(DebuggerCompactWatchGameTest::checkDetailsGeometry);
        capture(context, name + "-details");
        click(context, "codon.watch.details.copy_value");
        context.runOnClient(client -> require(client.keyboardHandler.getClipboard().equals(LONG_VALUE), "Full inspection copies the entire shortened value"));
        click(context, "codon.watch.details.copy_path");
        context.runOnClient(client -> require(client.keyboardHandler.getClipboard().equals(NBT.path()), "Copy path retains the full shortened name"));
        click(context, "codon.watch.details.more");
        context.runOnClient(client -> {
            require(screen(client).getFocused() == button(screen(client), text("codon.watch.details.less")), "More retains focus on Less after rebuilding");
            checkDetailsGeometry(client);
        });
        context.getInput().pressKey(InputConstants.KEY_TAB);
        context.runOnClient(client -> require(screen(client).getFocused() == button(screen(client), text("codon.watch.edit")), "Tab after More reaches visible Edit"));
        context.getInput().pressKey(InputConstants.KEY_END);
        capture(context, name + "-details-expanded-end");
        click(context, "codon.watch.details.less");
        context.runOnClient(client -> {
            require(screen(client).getFocused() == button(screen(client), text("codon.watch.details.more")), "Less retains focus on More");
            checkDetailsGeometry(client);
        });
        context.getInput().pressKey(InputConstants.KEY_TAB);
        context.runOnClient(client -> require(screen(client).getFocused() == button(screen(client), text("codon.watch.details.retry")), "Collapsed Tab skips hidden Edit"));
        click(context, "codon.watch.details.retry");
        context.runOnClient(client -> require(fixture.state().watches().drainQueries().stream().anyMatch(query -> query.spec().equals(NBT)), "Retry hitbox starts the same read-only query"));
        click(context, "codon.watch.close");
        click(context, "codon.watch.remove");
        context.runOnClient(client -> require(fixture.state().watches().findId(SCORE) < 0, "Delete hitbox removes its own row"));
        click(context, "codon.watch.undo_deleted");
        context.runOnClient(client -> require(fixture.state().watches().findId(SCORE) >= 0, "Undo restores the removed row"));
        clickLabel(context, "+");
        context.runOnClient(client -> require(screen(client) instanceof WatchScreen, "Add remains accessible above compact rows"));
        click(context, "codon.watch.close");
        context.runOnClient(client -> {
            var base = fixture.state().snapshot();
            fixture.state().applyPause(pause(base, 103, PauseReason.EXECUTION_COMPLETE));
            accept(fixture.state(), "-2147483648");
        });
        context.waitTicks(3);
        capture(context, name + "-finished");
        context.runOnClient(client -> fixture.state().beginControlRequest());
        context.waitTicks(6);
        context.runOnClient(client -> require(!button(screen(client), text("codon.ui.control.resume") + " "
            + fixture.input().keyLabel(InputManager.Control.RESUME).getString()).active, "Pending control still disables execution buttons"));
        capture(context, name + "-waiting");
        context.runOnClient(client -> client.setScreenAndShow(null));
    }

    private static PauseSnapshot pause(PauseSnapshot base, long id, PauseReason reason) {
        return new PauseSnapshot(base.location(), base.command(), base.depth(), base.callStack(), base.pauseSources(), base.executionFlows(), reason, id);
    }

    private static void accept(ClientDebuggerState state, String score) {
        for (var query : state.watches().drainQueries()) {
            WatchResult result = query.spec().equals(SCORE) ? new WatchResult(WatchResult.Status.VALUE, score, "entity:00000000-0000-0000-0000-000000000001")
                : query.spec().equals(NBT) ? new WatchResult(WatchResult.Status.VALUE, LONG_VALUE, "storage:demo:compact")
                : query.spec().path().equals("row_0") ? WatchResult.absent(WatchResult.Status.ERROR, "storage:demo:compact")
                : new WatchResult(WatchResult.Status.VALUE, "0", "storage:demo:compact");
            state.watches().accept(query.pauseId(), query.requestId(), result);
        }
    }

    private static void checkWatchGeometry(Minecraft client, Fixture fixture) {
        Screen screen = screen(client);
        var panel = fixture.overlay().watchPanel().bounds();
        require(panel.width() > 0, "Watches are visible");
        for (var widget : buttons(screen)) {
            require(widget.getX() >= 0 && widget.getY() >= 0 && widget.getRight() <= screen.width && widget.getBottom() <= screen.height, "Native controls remain on screen");
            if (widget.getX() >= panel.x() && widget.getX() < panel.x() + panel.width() && widget.getY() >= panel.y()
                && widget.getY() < panel.y() + panel.height()) {
                require(widget.getRight() <= panel.x() + panel.width() && widget.getBottom() <= panel.y() + panel.height(), "Watch controls remain in their panel");
                for (var other : buttons(screen)) if (widget != other) require(!overlaps(widget, other), "Watch hitboxes do not overlap other controls");
            }
        }
        if (WatchPanelLayout.stackedValues(panel.width())) {
            require(client.font.width("-2147483648") <= WatchPanelLayout.valueWidth(panel.width()), "The entire primary score fits the compact value line");
            require(client.font.width(text("codon.watch.short.error")) <= WatchPanelLayout.valueWidth(panel.width()), "Localized error stays distinguishable from a value");
        }
        require(button(screen, text("codon.ui.control.resume") + " " + fixture.input().keyLabel(InputManager.Control.RESUME).getString()).active,
            "Execution control remains accessible under an acknowledged pause");
    }

    private static void checkDetailsGeometry(Minecraft client) {
        Screen screen = screen(client);
        require(screen instanceof WatchDetailsScreen, "Full inspection is open");
        boolean expanded = buttons(screen).stream().anyMatch(widget -> widget.getMessage().getString().equals(text("codon.watch.details.less")));
        var layout = WatchDetailsLayout.create(screen.width, screen.height, expanded);
        for (var widget : buttons(screen)) {
            require(widget.getX() >= layout.panel().x() + 8 && widget.getRight() <= layout.panel().x() + layout.panel().width() - 8, "Footer stays inside the panel");
            require(widget.getY() >= layout.textBottom() + 6 && widget.getBottom() <= screen.height, "Text and footer have a gap");
            require(client.font.width(widget.getMessage()) <= widget.getWidth() - 10, "Localized footer text fits its native hitbox");
            for (var other : buttons(screen)) if (widget != other) require(!overlaps(widget, other), "Visible Details footer targets are disjoint");
        }
    }

    private static boolean overlaps(AbstractWidget a, AbstractWidget b) {
        return a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom();
    }
    private static List<DebuggerButton> buttons(Screen screen) { return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast).filter(button -> button.visible).toList(); }
    private static String text(String key) { return Component.translatable(key).getString(); }
    private static Screen screen(Minecraft client) { return client.gui.screen(); }
    private static DebuggerButton button(Screen screen, String label) { return buttons(screen).stream().filter(button -> button.getMessage().getString().equals(label)
        || button.getMessage().getString().endsWith(" " + label)).findFirst().orElseThrow(() -> new AssertionError("Visible button: " + label)); }
    private static void click(ClientGameTestContext context, String key) { clickLabel(context, context.computeOnClient(client -> text(key))); }
    private static void clickLabel(ClientGameTestContext context, String label) {
        double[] point = context.computeOnClient(client -> {
            var widget = button(screen(client), label);
            var scale = ((ScaledCodonScreen) screen(client)).uiScale();
            var window = client.getWindow();
            return new double[]{scale.toGame(widget.getX() + widget.getWidth() / 2.0) * window.getScreenWidth() / window.getGuiScaledWidth(),
                scale.toGame(widget.getY() + widget.getHeight() / 2.0) * window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(point[0], point[1]);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(3);
    }
    private static void capture(ClientGameTestContext context, String name) { context.getInput().setCursorPos(3, 3); context.waitTicks(3); context.takeScreenshot(name); }
    private static void language(ClientGameTestContext context, String language) {
        if (context.computeOnClient(client -> client.getLanguageManager().getSelected().equals(language))) return;
        var reload = context.computeOnClient(client -> { client.getLanguageManager().setSelected(language); return client.reloadResourcePacks(); });
        context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 200);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
