package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientWatchEditorState;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.client.ui.ScaledCodonScreen;
import works.nuty.codon.client.ui.WatchPickerScreen;
import works.nuty.codon.client.ui.WatchScreen;
import works.nuty.codon.core.model.WatchEditorPage;
import works.nuty.codon.core.model.WatchEditorQuery;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Opens the actual Browse/Choose routes; deterministic pages isolate dialog layout and native input. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerWatchPickerLayoutGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        int oldScale = context.computeOnClient(client -> client.options.guiScale().get());
        String oldLanguage = context.computeOnClient(client -> client.getLanguageManager().getSelected());
        try (TestSingleplayerContext ignored = context.worldBuilder().create()) {
            // One case per Browse/Choose route; vary language and scale without a Cartesian product.
            for (var scenario : List.of(
                new Scenario("en_us", 1280, 800, 3, 9, WatchSpec.Kind.SCORE, 1), // Custom-scale holder selection
                new Scenario("en_us", 1280, 800, 2, 0, WatchSpec.Kind.STORAGE_NBT, 0), // Storage paging and Retry
                new Scenario("en_us", 1280, 800, 3, 9, WatchSpec.Kind.STORAGE_NBT, 1), // Custom-scale NBT expand/Up
                new Scenario("ko_kr", 320, 240, 1, 0, WatchSpec.Kind.SCORE, 0), // Short Objectives, keyboard/gap hits, scroll
                new Scenario("ko_kr", 1280, 800, 2, 0, WatchSpec.Kind.ENTITY_NBT, 0), // Localized NBT expand/Up
                new Scenario("ko_kr", 1280, 800, 2, 0, WatchSpec.Kind.ENTITY_NBT, 1) // Entity selection
            )) {
                language(context, scenario.language());
                context.getInput().resizeWindow(scenario.width(), scenario.height());
                context.runOnClient(client -> { client.options.guiScale().set(scenario.gameScale()); client.resizeGui(); });
                checkPicker(context, scenario.language(), scenario.gameScale(), scenario.customScale(), scenario.kind(), scenario.row());
            }
        } finally {
            context.runOnClient(client -> { client.setScreenAndShow(null); client.options.guiScale().set(oldScale); client.resizeGui(); });
            language(context, oldLanguage);
        }
    }

    private record Scenario(String language, int width, int height, int gameScale, int customScale, WatchSpec.Kind kind, int row) {}

    private static void checkPicker(ClientGameTestContext context, String language, int gameScale, int custom, WatchSpec.Kind kind, int row) {
        ClientDebuggerState state = new ClientDebuggerState();
        var form = context.computeOnClient(client -> {
            state.applyPause(DebuggerPresentationGameTest.fixture(client));
            if (custom > 0) { state.preferences().selectCustomUiScale(client.getWindow().getGuiScale()); state.preferences().setCustomUiScale(custom); }
            var input = DebuggerPresentationGameTest.input(client, state);
            var screen = new WatchScreen(input, state, new DebuggerOverlay(state));
            client.setScreenAndShow(screen);
            return screen;
        });
        context.waitTicks(3);
        click(context, "kind." + kind.name().toLowerCase(Locale.ROOT));
        String first = kind == WatchSpec.Kind.SCORE ? "aligned_points" : kind == WatchSpec.Kind.ENTITY_NBT ? "Health" : "demo:aligned";
        String second = kind == WatchSpec.Kind.SCORE ? "#aligned" : kind == WatchSpec.Kind.ENTITY_NBT ? new UUID(0, 1).toString() : "counter";
        context.runOnClient(client -> { fields(form).get(0).setValue(first); fields(form).get(1).setValue(second); });
        clickWidget(context, context.computeOnClient(client -> buttons(form).stream().filter(button -> button.getY() == fields(form).get(row).getY()).findFirst().orElseThrow()));
        context.runOnClient(client -> require(screen(client) instanceof WatchPickerScreen, "Both Browse/Choose entry points open the actual picker"));
        context.runOnClient(client -> search(client).setValue(""));
        String name = "codon-watch-picker-" + language + "-game-" + gameScale + (custom > 0 ? "-custom-2_25" : "") + "-" + kind.name().toLowerCase(Locale.ROOT) + "-row-" + row;
        if (kind == WatchSpec.Kind.SCORE && row == 0) {
            var shortPage = new WatchEditorPage(WatchResult.Status.VALUE, List.of(
                new WatchEditorPage.Option("test", "test", "", false),
                new WatchEditorPage.Option("a", "a", "", false)), 0, false, null);
            accept(context, state, shortPage);
            checkGeometry(context);
            context.runOnClient(client -> require(search(client).getValue().isEmpty(), "Objectives reproduces the user's blank search"));
            int footerY = context.computeOnClient(client -> buttons(screen(client)).stream().filter(button -> button.getMessage().getString().equals(Component.translatable("codon.watch.picker.previous").getString())).findFirst().orElseThrow().getY());
            require(footerY - panelTop(context) == 130, "Two objective rows leave eight pixels before the footer, not an empty full-height panel");
            capture(context, name + "-two-objectives");
            point(context, panelLeft(context) + 20, panelTop(context) + 94);
            context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
            context.waitTicks(3);
            context.runOnClient(client -> require(screen(client) instanceof WatchPickerScreen, "The visible gap between rows is not a selection hitbox"));
            clickWidget(context, context.computeOnClient(DebuggerWatchPickerLayoutGameTest::search));
            context.getInput().pressKey(InputConstants.KEY_TAB);
            context.runOnClient(client -> require(screen(client).getFocused() instanceof DebuggerButton button
                && button.getMessage().getString().equals(Component.translatable("codon.watch.close").getString()), "Tab reaches Close when paging and Retry are inactive"));
            context.getInput().pressKey(InputConstants.KEY_TAB);
            context.getInput().pressKey(InputConstants.KEY_DOWN);
            context.getInput().pressKey(InputConstants.KEY_RETURN);
            context.waitTicks(3);
            context.runOnClient(client -> require(screen(client) == form && fields(form).getFirst().getValue().equals("a"), "Native keyboard selects the second short objective and preserves the originating form"));
            clickWidget(context, context.computeOnClient(client -> buttons(form).stream().filter(button -> button.getY() == fields(form).getFirst().getY()).findFirst().orElseThrow()));
            context.runOnClient(client -> search(client).setValue(""));
        }
        clickWidget(context, context.computeOnClient(DebuggerWatchPickerLayoutGameTest::search));
        context.getInput().typeChars("counter");
        context.waitTicks(3);
        ClientWatchEditorState.Query query = context.computeOnClient(client -> {
            var queries = state.watchEditor().drainQueries();
            require(!queries.isEmpty(), "Picker issues a bounded read-only query");
            return queries.getFirst();
        });
        var mode = query.query().mode();
        var page = page(mode, kind, 0, false);
        context.runOnClient(client -> state.watchEditor().accept(query.pauseId(), query.requestId(), page));
        context.waitTicks(3);
        checkGeometry(context);
        capture(context, name);
        if (mode == WatchEditorQuery.Mode.NBT) {
            rowClick(context, 0, true);
            var expanded = accept(context, state, page(mode, kind, 0, true));
            require(expanded.query().path().equals("nested"), "Native arrow expands the intended compound");
            capture(context, name + "-expanded");
            click(context, "picker.up");
            var root = accept(context, state, page);
            require(root.query().path().isEmpty(), "Native Up returns to the root path");
        } else {
            click(context, "picker.next");
            var next = accept(context, state, page(mode, kind, WatchEditorPage.PAGE_SIZE, false));
            require(next.query().offset() == WatchEditorPage.PAGE_SIZE, "Native Next advances the server page");
            click(context, "picker.previous");
            require(accept(context, state, page).query().offset() == 0, "Native Prev returns to the first page");
        }
        if (language.equals("en_us") && gameScale == 2 && custom == 0 && kind == WatchSpec.Kind.STORAGE_NBT && row == 0) {
            context.runOnClient(client -> state.watchEditor().retry());
            var failed = accept(context, state, WatchEditorPage.absent(WatchResult.Status.ERROR));
            capture(context, name + "-error");
            click(context, "retry");
            var retry = accept(context, state, page);
            require(retry.requestId() > failed.requestId() && retry.query().equals(failed.query()), "Native Retry retains query meaning with a fresh identity");
        }
        int selected = mode == WatchEditorQuery.Mode.NBT ? 1 : 0;
        if (language.equals("ko_kr") && gameScale == 1 && row == 0) {
            point(context, panelLeft(context) + 20, panelTop(context) + 83);
            context.getInput().scroll(-1);
            context.waitTicks(3);
            capture(context, name + "-scroll");
            selected = 3;
            rowClick(context, 0, false);
        } else rowClick(context, selected, false);
        String chosen = page.options().get(selected).value();
        context.runOnClient(client -> {
            require(screen(client) == form, "Native option selection returns to the originating Watch form");
            require(fields(form).get(row).getValue().equals(chosen), "The selected value updates its Browse field");
            require(fields(form).get(1 - row).getValue().equals(row == 0 ? second : first), "The other draft field is preserved");
        });
    }

    private static WatchEditorPage page(WatchEditorQuery.Mode mode, WatchSpec.Kind kind, int offset, boolean child) {
        List<WatchEditorPage.Option> options = new ArrayList<>();
        if (mode == WatchEditorQuery.Mode.NBT && !child) options.add(new WatchEditorPage.Option("nested", "nested counter compound", "{ " + "counter:42, ".repeat(20) + "}", true));
        for (int index = options.size(); index < WatchEditorPage.PAGE_SIZE; index++) {
            String value = switch (mode) {
                case STORAGES -> "demo:counter_" + (offset + index);
                case OBJECTIVES -> "counter_points_" + (offset + index);
                case ENTITIES -> kind == WatchSpec.Kind.SCORE ? "\"#counter_" + (offset + index) + "\"" : new UUID(0, offset + index + 2).toString();
                case NBT -> (child ? "nested." : "") + "counter_" + (offset + index);
                default -> throw new AssertionError("Browse mode");
            };
            options.add(new WatchEditorPage.Option(value, "counter option " + (offset + index), "A scalar value and detail for dialog alignment", false));
        }
        return new WatchEditorPage(WatchResult.Status.VALUE, options, offset, offset == 0 && !child, null);
    }

    private static ClientWatchEditorState.Query accept(ClientGameTestContext context, ClientDebuggerState state, WatchEditorPage page) {
        context.waitTicks(3);
        var result = context.computeOnClient(client -> {
            var pending = state.watchEditor().drainQueries();
            require(!pending.isEmpty(), "Native picker action issues its read-only request");
            var query = pending.getFirst(); state.watchEditor().accept(query.pauseId(), query.requestId(), page); return query;
        });
        context.waitTicks(3);
        return result;
    }
    private static void checkGeometry(ClientGameTestContext context) {
        context.runOnClient(client -> {
            EditBox search = search(client);
            DebuggerButton close = buttons(screen(client)).stream().filter(button -> button.getMessage().getString().equals(Component.translatable("codon.watch.close").getString())).findFirst().orElseThrow();
            require(search.getRight() == close.getRight(), "Search and Close reach the same content right edge");
            for (DebuggerButton button : buttons(screen(client)).stream().filter(button -> button.getY() > search.getY()).toList()) {
                require(button.getHeight() == 20 && button.getWidth() == 54, "Footer actions share height and width in both languages");
                if (button.getMessage().getString().equals(Component.translatable("codon.watch.retry").getString()))
                    require(button.getRight() == search.getRight(), "Retry shares the search and row right edge");
            }
            for (AbstractWidget widget : screen(client).children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).filter(widget -> widget.visible).toList())
                require(widget.getX() >= 0 && widget.getY() >= 0 && widget.getRight() <= screen(client).width && widget.getBottom() <= screen(client).height, "Picker controls fit the viewport");
        });
    }
    private static int panelLeft(ClientGameTestContext context) { return context.computeOnClient(client -> search(client).getX() - 8); }
    private static int panelTop(ClientGameTestContext context) { return context.computeOnClient(client -> search(client).getY() - 29); }
    private static void rowClick(ClientGameTestContext context, int row, boolean expand) {
        int x = expand ? context.computeOnClient(client -> {
            int width = Math.min(560, screen(client).width - 16);
            return (screen(client).width - width) / 2 + width - 23;
        }) : panelLeft(context) + 20;
        point(context, x, panelTop(context) + 70 + row * 26 + 11);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT); context.waitTicks(3);
    }
    private static void point(ClientGameTestContext context, double x, double y) {
        double[] position = context.computeOnClient(client -> { var window = client.getWindow(); var scale = ((ScaledCodonScreen) screen(client)).uiScale();
            return new double[]{scale.toGame(x) * window.getScreenWidth() / window.getGuiScaledWidth(), scale.toGame(y) * window.getScreenHeight() / window.getGuiScaledHeight()}; });
        context.getInput().setCursorPos(position[0], position[1]);
    }
    private static void clickWidget(ClientGameTestContext context, AbstractWidget widget) { point(context, widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0); context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT); context.waitTicks(3); }
    private static void click(ClientGameTestContext context, String key) { clickWidget(context, context.computeOnClient(client -> buttons(screen(client)).stream().filter(button -> button.getMessage().getString().equals(Component.translatable("codon.watch." + key).getString())).findFirst().orElseThrow())); }
    private static Screen screen(Minecraft client) { if (client.gui.screen() == null) throw new AssertionError("Picker UI is open"); return client.gui.screen(); }
    private static List<DebuggerButton> buttons(Screen screen) { return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast).filter(button -> button.visible).toList(); }
    private static List<EditBox> fields(Screen screen) { return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).toList(); }
    private static EditBox search(Minecraft client) { return fields(screen(client)).getFirst(); }
    private static void capture(ClientGameTestContext context, String name) { context.getInput().setCursorPos(3, 3); context.waitTicks(3); context.takeScreenshot(name); }
    private static void language(ClientGameTestContext context, String language) { if (context.computeOnClient(client -> client.getLanguageManager().getSelected().equals(language))) return;
        var reload = context.computeOnClient(client -> { client.getLanguageManager().setSelected(language); return client.reloadResourcePacks(); }); context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 200); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
