package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.WatchGrouping;
import works.nuty.codon.client.ui.*;
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
                new Scenario("en_us", 640, 480, 2, 0), // Minimum 320x240 expanded row
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
            client.gui.hud.getChat().clearMessages(false);
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
            // Keep the healthy case fully visible even in the 144-pixel compact pane.
            for (int i = 0; i < 16; i++) definitions.add(new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:compact", i == 1 ? "ok" : "row_" + i));
            state.watches().addAll(definitions);
            accept(state, "123456789");
            state.applyPause(pause(base, 102, PauseReason.STEP));
            accept(state, "-2147483648");
            state.watches().reveal(state.watches().findId(SCORE));
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
        context.runOnClient(client -> checkRowTooltips(client));
        capture(context, name);
        var beforeCopyBounds = context.computeOnClient(client -> fixture.overlay().watchPanel().scrollBounds());
        int beforeCopyOffset = context.computeOnClient(client -> fixture.overlay().watchPanel().offset());
        watchAction(context, SCORE, "codon.watch.details.copy_value");
        context.runOnClient(client -> {
            require(client.keyboardHandler.getClipboard().equals("-2147483648"), "Copy hitbox reaches the current value");
            require(fixture.overlay().watchPanel().scrollBounds().equals(beforeCopyBounds)
                && fixture.overlay().watchPanel().offset() == beforeCopyOffset,
                "Visible copy feedback preserves the Watch viewport and scroll position");
        });
        capture(context, name + "-copied");
        watchAction(context, SCORE, "codon.watch.pin");
        context.runOnClient(client -> require(fixture.state().watches().definitions().getFirst().isPinned(), "Pin hitbox remains accessible"));
        watchAction(context, SCORE, "codon.watch.unpin");
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
        watchAction(context, SCORE, "codon.watch.edit");
        closeInlineRow(context, "Edit hitbox opens the inline editor");
        openDetailsFromValueRightEdge(context, fixture, name);
        capture(context, name + "-details");
        context.runOnClient(client -> checkDetailsGeometry(client, fixture));
        click(context, "codon.watch.details.copy_value");
        context.runOnClient(client -> require(client.keyboardHandler.getClipboard().equals(LONG_VALUE), "Expanded row copies the entire shortened value"));
        click(context, "codon.watch.details.copy_path");
        context.runOnClient(client -> require(client.keyboardHandler.getClipboard().equals(NBT.path()), "Copy path retains the full shortened name"));
        capture(context, name + "-details-copied");
        context.runOnClient(client -> {
            require(buttons(screen(client)).stream().noneMatch(widget -> widget.getMessage().getString().equals(text("codon.watch.details.retry"))),
                "A successful value offers no Retry");
            screen(client).setFocused(rowButtons(client, NBT.path()).getFirst());
        });
        context.getInput().pressKey(InputConstants.KEY_DOWN);
        context.runOnClient(client -> require(screen(client).getFocused() == button(screen(client), text("codon.watch.details.copy_value")),
            "Down from an expanded row reaches its first action"));
        context.getInput().pressKey(InputConstants.KEY_END);
        context.runOnClient(client -> {
            var panel = fixture.overlay().watchPanel();
            require(panel.expansionScroll() == panel.expansionMaxScroll(), "End scrolls the expanded details from the keyboard");
            require(screen(client).getFocused() == button(screen(client), text("codon.watch.details.copy_value")),
                "Scrolling the details keeps keyboard focus on the action");
            System.out.println("[codon-test] " + name + " expansion scroll " + panel.expansionScroll() + "/" + panel.expansionMaxScroll());
        });
        capture(context, name + "-details-end");
        context.getInput().pressKey(InputConstants.KEY_HOME);
        context.runOnClient(client -> require(fixture.overlay().watchPanel().expansionScroll() == 0, "Home returns to the value"));
        click(context, "codon.watch.edit");
        closeInlineRow(context, "Expanded row Edit hitbox opens the same inline editor");
        context.runOnClient(client -> require(fixture.overlay().watchPanel().expandedId() == fixture.state().watches().findId(NBT),
            "Closing the inline editor keeps the row expanded"));
        context.runOnClient(client -> {
            fixture.state().applyPause(pause(fixture.state().snapshot(), 103, PauseReason.STEP));
            accept(fixture.state(), "-2147483648", true);
            require(fixture.state().watches().entries().stream().anyMatch(entry -> entry.spec().equals(NBT)
                && entry.result().status() == WatchResult.Status.ERROR), "Retry is exercised on a failed read");
        });
        context.waitTicks(3);
        context.runOnClient(client -> {
            require(button(screen(client), text("codon.watch.details.retry")).active, "Failed read enables Retry");
            checkDetailsGeometry(client, fixture);
        });
        capture(context, name + "-details-failed");
        click(context, "codon.watch.details.retry");
        context.runOnClient(client -> require(fixture.state().watches().drainQueries().stream().anyMatch(query -> query.spec().equals(NBT)), "Retry hitbox starts the same read-only query"));
        clickLabel(context, context.computeOnClient(client -> Component.translatable("codon.watch.inspect", WatchFormatting.specification(NBT).getString()).getString()));
        context.runOnClient(client -> {
            require(fixture.overlay().watchPanel().expandedId() == 0, "Selecting an expanded row collapses it");
            require(buttons(screen(client)).stream().noneMatch(widget -> widget.getMessage().getString().equals(text("codon.watch.details.copy_path"))),
                "Collapsed rows remove their actions");
        });
        // Expanding scrolled the compact list to the NBT row; reveal the first row again for its menu.
        context.runOnClient(client -> fixture.overlay().watchPanel().select(fixture.state().watches().findId(SCORE)));
        context.waitTicks(3);
        watchAction(context, SCORE, "codon.watch.menu.delete");
        context.runOnClient(client -> require(fixture.state().watches().findId(SCORE) < 0, "Delete hitbox removes its own row"));
        capture(context, name + "-removed");
        click(context, "codon.watch.undo_deleted");
        context.runOnClient(client -> require(fixture.state().watches().findId(SCORE) >= 0, "Undo restores the removed row"));
        clickLabel(context, "+");
        closeInlineRow(context, "Add remains accessible above compact rows");
        context.runOnClient(client -> {
            var base = fixture.state().snapshot();
            fixture.state().applyPause(pause(base, 104, PauseReason.EXECUTION_COMPLETE));
            accept(fixture.state(), "-2147483648");
        });
        context.waitTicks(3);
        capture(context, name + "-finished");
        clickLabel(context, context.computeOnClient(client -> text("codon.ui.control.resume") + " "
            + fixture.input().keyLabel(InputManager.Control.RESUME).getString()));
        context.waitTicks(6);
        context.runOnClient(client -> {
            for (var action : InputManager.Control.values()) require(!button(screen(client), text(action.translationKey()) + " "
                + fixture.input().keyLabel(action).getString()).active, "Pending control still disables every execution button");
            client.gui.hud.getChat().clearMessages(false);
        });
        capture(context, name + "-waiting");
        context.runOnClient(client -> client.setScreenAndShow(null));
    }

    private static PauseSnapshot pause(PauseSnapshot base, long id, PauseReason reason) {
        return new PauseSnapshot(base.location(), base.command(), base.depth(), base.callStack(), base.pauseSources(), base.executionFlows(), reason, id);
    }

    private static void accept(ClientDebuggerState state, String score) {
        accept(state, score, false);
    }

    private static void accept(ClientDebuggerState state, String score, boolean nbtFailure) {
        for (var query : state.watches().drainQueries()) {
            WatchResult result = query.spec().equals(SCORE) ? new WatchResult(WatchResult.Status.VALUE, score, "entity:00000000-0000-0000-0000-000000000001")
                : query.spec().equals(NBT) ? nbtFailure ? WatchResult.absent(WatchResult.Status.ERROR, "storage:demo:compact")
                    : new WatchResult(WatchResult.Status.VALUE, LONG_VALUE, "storage:demo:compact")
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
        require(!WatchPanelLayout.stackedValues(panel.width()), "Compact Watches keep keys left and values right");
        require(fixture.overlay().watchPanel().expandedId() != 0 || buttons(screen).stream().noneMatch(widget -> List.of(text("codon.watch.edit"), text("codon.watch.pin"),
            text("codon.watch.unpin"), text("codon.watch.menu.delete")).contains(widget.getMessage().getString())),
            "Row management does not recreate inline action buttons");
        require(button(screen, text("codon.ui.control.resume") + " " + fixture.input().keyLabel(InputManager.Control.RESUME).getString()).active,
            "Execution control remains accessible under an acknowledged pause");
    }

    /** The expansion stays inside the Watch panel; its localized actions fit and do not overlap. */
    private static void checkDetailsGeometry(Minecraft client, Fixture fixture) {
        require(screen(client) instanceof CodonScreen && fixture.overlay().watchPanel().expandedId() != 0, "Details open in place");
        var panel = fixture.overlay().watchPanel().bounds();
        var actions = buttons(screen(client)).stream().filter(widget -> List.of(text("codon.watch.details.copy_value"),
            text("codon.watch.details.copy_path"), text("codon.watch.details.retry"), text("codon.watch.edit"))
            .contains(widget.getMessage().getString())).toList();
        require(actions.size() >= 2, "Expanded row shows its actions");
        for (var widget : actions) {
            require(widget.getX() >= panel.x() && widget.getRight() <= panel.x() + panel.width()
                && widget.getY() >= panel.y() && widget.getBottom() <= panel.y() + panel.height(), "Actions stay inside the panel");
            require(client.font.width(widget.getMessage()) <= widget.getWidth() - 10, "Localized action text fits its native hitbox: "
                + widget.getMessage().getString());
            for (var other : buttons(screen(client))) if (widget != other) require(!overlaps(widget, other), "Expanded actions are disjoint");
        }
    }

    private static void openDetailsFromValueRightEdge(ClientGameTestContext context, Fixture fixture, String name) {
        var value = context.computeOnClient(client -> {
            String label = Component.translatable("codon.watch.inspect", WatchFormatting.specification(NBT).getString()).getString();
            return buttons(screen(client)).stream().filter(widget -> widget.getMessage().getString().equals(label))
                .max(java.util.Comparator.comparingInt(DebuggerButton::getY)).orElseThrow();
        });
        double[] point = context.computeOnClient(client -> {
            var panel = fixture.overlay().watchPanel().bounds();
            double x = value.getRight() - 1;
            double y = value.getY() + value.getHeight() / 2.0;
            require(value.isMouseOver(x, y), "The last visible value pixel belongs to the inspection surface");
            Tooltip hint = tooltip(value);
            require(hint != null, "A clipped value keeps a tooltip with its full text");
            for (var other : buttons(screen(client))) if (other != value)
                require(!other.isMouseOver(x, y), "Right-edge inspection cannot activate another control");
            var scale = ((ScaledCodonScreen) screen(client)).uiScale();
            var window = client.getWindow();
            return new double[]{scale.toGame(x) * window.getScreenWidth() / window.getGuiScaledWidth(),
                scale.toGame(y) * window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(point[0], point[1]);
        context.waitTicks(12);
        context.runOnClient(client -> require(value.isHovered(), "Native pointer hover reaches the value's right edge"));
        context.takeScreenshot(name + "-value-right-tooltip");
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(3);
        context.runOnClient(client -> require(screen(client) instanceof CodonScreen
            && fixture.overlay().watchPanel().expandedId() == fixture.state().watches().findId(NBT),
            "Native right-edge value click expands the row in place"));
    }

    /** A healthy, fully drawn row is silent; a failed read or a clipped value still explains itself. */
    private static void checkRowTooltips(Minecraft client) {
        for (var widget : rowButtons(client, "row_0"))
            require(tooltipText(widget, client).contains(text("codon.watch.status.error")),
                "A failed read keeps its explanation on hover");
        for (var widget : rowButtons(client, "ok"))
            require(tooltip(widget) == null, "A healthy, unclipped row has no echo tooltip");
        for (var widget : rowButtons(client, NBT.path()))
            require(tooltipText(widget, client).contains(LONG_VALUE.substring(0, 24)),
                "A clipped value keeps its full text on hover");
    }

    private static List<DebuggerButton> rowButtons(Minecraft client, String path) {
        String label = Component.translatable("codon.watch.inspect",
            WatchFormatting.specification(new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:compact", path)).getString()).getString();
        return buttons(screen(client)).stream().filter(widget -> widget.getMessage().getString().equals(label)).toList();
    }

    private static String tooltipText(DebuggerButton button, Minecraft client) {
        var hint = tooltip(button);
        var text = new StringBuilder();
        if (hint != null) for (var line : hint.toCharSequence(client)) {
            line.accept((index, style, codePoint) -> { text.appendCodePoint(codePoint); return true; });
            text.append('\n');
        }
        return text.toString();
    }

    private static boolean overlaps(AbstractWidget a, AbstractWidget b) {
        return a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom();
    }
    private static Tooltip tooltip(DebuggerButton button) {
        try {
            var field = DebuggerButton.class.getDeclaredField("tooltip");
            field.setAccessible(true);
            return (Tooltip) field.get(button);
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
    private static List<DebuggerButton> buttons(Screen screen) { return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast).filter(button -> button.visible).toList(); }
    private static String text(String key) { return Component.translatable(key).getString(); }
    private static Screen screen(Minecraft client) { return client.gui.screen(); }

    /** The inline Watch row is a text box in the cursor screen; Escape closes only that row. */
    private static void closeInlineRow(ClientGameTestContext context, String message) {
        context.waitTicks(3);
        context.runOnClient(client -> {
            require(screen(client).children().stream().anyMatch(child -> child instanceof net.minecraft.client.gui.components.EditBox),
                message);
            screen(client).keyPressed(new net.minecraft.client.input.KeyEvent(
                com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE, 0, 0));
        });
        context.waitTicks(3);
    }
    private static DebuggerButton button(Screen screen, String label) { return buttons(screen).stream().filter(button -> button.getMessage().getString().equals(label)
        || button.getMessage().getString().endsWith(" " + label)).findFirst().orElseThrow(() -> new AssertionError("Visible button: " + label)); }
    private static void click(ClientGameTestContext context, String key) { clickLabel(context, context.computeOnClient(client -> text(key))); }
    private static void watchAction(ClientGameTestContext context, WatchSpec spec, String action) {
        String inspect = context.computeOnClient(client -> Component.translatable("codon.watch.inspect", WatchFormatting.specification(spec).getString()).getString());
        clickLabel(context, inspect, InputConstants.MOUSE_BUTTON_RIGHT);
        context.runOnClient(client -> require(ScreenLayers.get(screen(client)) != null,
            "Row right-click opens its management menu"));
        click(context, action);
    }
    private static void clickLabel(ClientGameTestContext context, String label) {
        clickLabel(context, label, InputConstants.MOUSE_BUTTON_LEFT);
    }
    private static void clickLabel(ClientGameTestContext context, String label, int mouseButton) {
        double[] point = context.computeOnClient(client -> {
            Screen layer = ScreenLayers.get(screen(client));
            Screen host = layer == null ? screen(client) : layer;
            var widget = button(host, label);
            var scale = ((ScaledCodonScreen) host).uiScale();
            var window = client.getWindow();
            return new double[]{scale.toGame(widget.getX() + widget.getWidth() / 2.0) * window.getScreenWidth() / window.getGuiScaledWidth(),
                scale.toGame(widget.getY() + widget.getHeight() / 2.0) * window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(point[0], point[1]);
        context.getInput().pressMouse(mouseButton);
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
