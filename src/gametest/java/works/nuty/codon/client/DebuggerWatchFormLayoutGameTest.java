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
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.client.ui.ScaledCodonScreen;
import works.nuty.codon.client.ui.WatchPickerScreen;
import works.nuty.codon.client.ui.WatchScreen;
import works.nuty.codon.client.ui.layout.WatchFormLayout;
import works.nuty.codon.core.model.WatchEditorPage;
import works.nuty.codon.core.model.WatchEditorQuery;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

import java.util.List;
import java.util.Locale;

/** Native input and rendered form geometry; replies use a deterministic UI fixture. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerWatchFormLayoutGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        int oldScale = context.computeOnClient(client -> client.options.guiScale().get());
        String oldLanguage = context.computeOnClient(client -> client.getLanguageManager().getSelected());
        try (TestSingleplayerContext ignored = context.worldBuilder().create()) {
            for (String language : new String[]{"en_us", "ko_kr"}) {
                language(context, language);
                for (int[] viewport : new int[][]{{1280, 800, 2, 0}, {1280, 800, 3, 0}, {320, 240, 1, 0},
                    {1280, 800, 2, 9}, {320, 240, 1, 9}}) {
                    context.getInput().resizeWindow(viewport[0], viewport[1]);
                    context.runOnClient(client -> { client.options.guiScale().set(viewport[2]); client.resizeGui(); });
                    for (WatchSpec.Kind kind : WatchSpec.Kind.values()) checkForm(context, language, viewport[2], viewport[3], kind);
                }
            }
        } finally {
            context.runOnClient(client -> { client.setScreenAndShow(null); client.options.guiScale().set(oldScale); client.resizeGui(); });
            language(context, oldLanguage);
        }
    }

    private static void checkForm(ClientGameTestContext context, String language, int scale, int custom, WatchSpec.Kind kind) {
        context.runOnClient(client -> { client.options.guiScale().set(scale); client.resizeGui(); });
        ClientDebuggerState state = new ClientDebuggerState();
        var input = context.computeOnClient(client -> {
            state.applyPause(DebuggerPresentationGameTest.fixture(client));
            if (custom > 0) { state.preferences().selectCustomUiScale(client.getWindow().getGuiScale()); state.preferences().setCustomUiScale(custom); }
            return DebuggerPresentationGameTest.input(client, state);
        });
        var overlay = context.computeOnClient(client -> new DebuggerOverlay(state));
        context.runOnClient(client -> client.setScreenAndShow(new WatchScreen(input, state, overlay)));
        context.waitTicks(3);
        click(context, "kind." + kind.name().toLowerCase(Locale.ROOT));
        context.runOnClient(DebuggerWatchFormLayoutGameTest::checkGeometry);
        clickField(context, 0);
        String primary = kind == WatchSpec.Kind.SCORE ? "aligned_points" : kind == WatchSpec.Kind.ENTITY_NBT ? "Health" : "demo:aligned";
        context.getInput().typeChars(primary);
        context.getInput().pressKey(InputConstants.KEY_TAB);
        context.runOnClient(client -> require(screen(client).getFocused() == fields(screen(client)).get(1), "Native Tab reaches the second field"));
        String secondary = kind == WatchSpec.Kind.SCORE ? "#aligned" : kind == WatchSpec.Kind.STORAGE_NBT ? "counter" : "";
        if (!secondary.isEmpty()) context.getInput().typeChars(secondary);
        clickField(context, 0);
        context.runOnClient(client -> require(fields(screen(client)).getFirst().getValue().equals(primary), "Native typing preserves the expression"));
        if (custom > 0) {
            if (scale == 2) {
                int oldWidth = context.computeOnClient(client -> screen(client).width);
                context.runOnClient(client -> { client.options.guiScale().set(3); client.resizeGui(); });
                context.waitTicks(3);
                context.runOnClient(client -> {
                    require(screen(client).width == oldWidth && fields(screen(client)).getFirst().getValue().equals(primary), "Game GUI scale changes retain custom layout and typed draft");
                    checkGeometry(client);
                });
            }
            context.runOnClient(client -> {
                require(state.preferences().customUiScale() == custom, "Small-window clamp retains the saved request");
                require(((ScaledCodonScreen) screen(client)).uiScale().effective() == (scale == 1 ? 1 : 2.25), "Custom scale applies or clamps to the supported viewport");
            });
        }
        boolean inline = context.computeOnClient(client -> WatchFormLayout.create(screen(client).width, screen(client).height).inlineSuggestions()
            && kind != WatchSpec.Kind.ENTITY_NBT);
        if (inline && language.equals("en_us") && scale == 2 && custom == 0 && kind == WatchSpec.Kind.SCORE) {
            click(context, "kind.score");
            accept(context, state, WatchEditorPage.absent(WatchResult.Status.ERROR));
            context.waitTicks(3);
            context.runOnClient(client -> require(button(screen(client), text("retry")).active, "Failed preview exposes Retry"));
            clickField(context, 0);
        }
        ClientWatchEditorState.Query query = accept(context, state, inline
            ? choices(primary) : WatchEditorPage.absent(WatchResult.Status.ERROR));
        context.waitTicks(3);
        String name = "codon-watch-form-" + language + "-game-" + scale + (custom > 0 ? "-custom-2_25" : "") + "-" + kind.name().toLowerCase(Locale.ROOT);
        capture(context, name);
        if (inline) {
            context.runOnClient(client -> require(buttons(screen(client)).stream().noneMatch(button -> button.getMessage().getString().equals(text("retry"))),
                "Inline choices hide Retry from the preceding preview"));
            clickLabel(context, "First suggestion");
            context.runOnClient(client -> require(fields(screen(client)).getFirst().getValue().equals(primary), "Native suggestion click targets its field"));
        } else {
            context.runOnClient(client -> {
                DebuggerButton retry = button(screen(client), text("retry"));
                var layout = WatchFormLayout.create(screen(client).width, screen(client).height);
                require(retry.getY() + (retry.getHeight() - client.font.lineHeight) / 2 + 1 == layout.previewValueY(), "Retry text aligns with the preview value");
                require(retry.getRight() == layout.contentRight(), "Retry shares the right action column");
            });
            click(context, "retry");
            context.runOnClient(client -> {
                var retry = state.watchEditor().drainQueries().getFirst();
                require(retry.requestId() > query.requestId() && retry.query().mode() == WatchEditorQuery.Mode.PREVIEW && retry.query().equals(query.query()),
                    "Native Retry requests the same preview with a fresh identity");
            });
        }
        if (inline && kind == WatchSpec.Kind.STORAGE_NBT) {
            context.runOnClient(client -> fields(screen(client)).getFirst().setValue("INVALID:storage"));
            clickField(context, 0);
            context.waitTicks(3);
            accept(context, state, choices(primary));
            context.waitTicks(3);
            context.runOnClient(client -> require(buttons(screen(client)).stream().noneMatch(button -> button.getMessage().getString().contains("suggestion")),
                "Visible validation takes priority over overlapping suggestions"));
            context.getInput().pressKey(InputConstants.KEY_RETURN);
            context.runOnClient(client -> require(client.gui.screen() instanceof WatchScreen && state.watches().definitions().isEmpty(), "Invalid Enter does not add a Watch"));
            capture(context, name + "-validation");
            context.runOnClient(client -> fields(screen(client)).getFirst().setValue(primary));
            context.waitTicks(3);
        }
        for (int index = 0; index < 2; index++) {
            int row = index;
            clickWidget(context, context.computeOnClient(client -> buttons(screen(client)).stream()
                .filter(button -> button.getY() == fields(screen(client)).get(row).getY()).findFirst().orElseThrow()));
            context.runOnClient(client -> require(client.gui.screen() instanceof WatchPickerScreen, "Native Browse/Choose opens the picker"));
            click(context, "close");
            context.runOnClient(client -> require(client.gui.screen() instanceof WatchScreen
                && fields(screen(client)).getFirst().getValue().equals(primary) && fields(screen(client)).get(1).getValue().equals(secondary),
                "Picker return retains both draft fields"));
        }
        click(context, "add");
        WatchSpec expected = kind == WatchSpec.Kind.SCORE ? WatchSpec.scoreHolder(primary, secondary)
            : new WatchSpec(kind, kind == WatchSpec.Kind.STORAGE_NBT ? primary : "", kind == WatchSpec.Kind.STORAGE_NBT ? secondary : primary);
        context.runOnClient(client -> require(client.gui.screen() instanceof CodonScreen && state.watches().definitions().contains(expected), "Native Add saves the same specification"));
        if (language.equals("en_us") && scale == 2 && custom == 0 && kind == WatchSpec.Kind.SCORE) {
            long id = state.watches().findId(expected);
            context.runOnClient(client -> client.setScreenAndShow(WatchScreen.edit(input, state, overlay, id)));
            context.waitTicks(3);
            context.runOnClient(DebuggerWatchFormLayoutGameTest::checkGeometry);
            capture(context, name + "-edit");
            click(context, "editor.save");
            context.runOnClient(client -> require(state.watches().findId(expected) == id, "Native Save preserves the Watch identity"));
        }
    }

    private static WatchEditorPage choices(String value) {
        return new WatchEditorPage(WatchResult.Status.VALUE, List.of(
            new WatchEditorPage.Option(value, "First suggestion", "Fixture choice", false),
            new WatchEditorPage.Option(value, "Second suggestion", "Fixture choice", false)), 0, false, null);
    }

    private static ClientWatchEditorState.Query accept(ClientGameTestContext context, ClientDebuggerState state, WatchEditorPage page) {
        context.waitTicks(3);
        return context.computeOnClient(client -> {
            var pending = state.watchEditor().drainQueries();
            require(!pending.isEmpty(), "The rendered form issues its read-only query");
            var query = pending.getFirst();
            state.watchEditor().accept(query.pauseId(), query.requestId(), page);
            return query;
        });
    }

    private static void checkGeometry(Minecraft client) {
        Screen screen = screen(client);
        var layout = WatchFormLayout.create(screen.width, screen.height);
        List<EditBox> fields = fields(screen);
        require(fields.size() == 2, "Each Watch kind presents two form rows");
        for (int index = 0; index < 2; index++) {
            EditBox field = fields.get(index);
            int row = index;
            DebuggerButton browse = buttons(screen).stream().filter(button -> button.getY() == field.getY()).findFirst().orElseThrow();
            require(field.getX() == layout.contentX() && field.getRight() + 6 == browse.getX() && browse.getRight() == layout.contentRight(), "Field and action columns align");
            require(field.getHeight() == browse.getHeight(), "Field and action rows share height");
            require(layout.labelY(row) + client.font.lineHeight + 2 <= field.getY(), "Labels have a clear gap above the field");
            require(client.font.width(field.getMessage()) <= layout.contentWidth(), "Localized field labels fit the form width");
        }
        for (AbstractWidget widget : screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).filter(widget -> widget.visible).toList())
            require(widget.getX() >= 0 && widget.getY() >= 0 && widget.getRight() <= screen.width && widget.getBottom() <= screen.height, "Visible controls stay inside the viewport");
        require(button(screen, text("kind.storage_nbt")).getRight() == layout.contentRight(), "The last type tab shares the form right edge");
    }

    private static void language(ClientGameTestContext context, String language) {
        if (context.computeOnClient(client -> client.getLanguageManager().getSelected().equals(language))) return;
        var reload = context.computeOnClient(client -> { client.getLanguageManager().setSelected(language); return client.reloadResourcePacks(); });
        context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 200);
    }
    private static void capture(ClientGameTestContext context, String name) {
        context.getInput().setCursorPos(3, 3);
        context.waitTicks(3);
        context.takeScreenshot(name);
    }
    private static Screen screen(Minecraft client) { if (client.gui.screen() == null) throw new AssertionError("Watch UI is open"); return client.gui.screen(); }
    private static List<EditBox> fields(Screen screen) { return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).filter(EditBox::isVisible).toList(); }
    private static List<DebuggerButton> buttons(Screen screen) { return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast).filter(button -> button.visible).toList(); }
    private static String text(String key) { return Component.translatable("codon.watch." + key).getString(); }
    private static DebuggerButton button(Screen screen, String label) { return buttons(screen).stream().filter(button -> button.getMessage().getString().equals(label)).findFirst().orElseThrow(() -> new AssertionError("Visible button: " + label)); }
    private static void click(ClientGameTestContext context, String key) { clickLabel(context, context.computeOnClient(client -> text(key))); }
    private static void clickLabel(ClientGameTestContext context, String label) { clickWidget(context, context.computeOnClient(client -> button(screen(client), label))); }
    private static void clickField(ClientGameTestContext context, int index) { clickWidget(context, context.computeOnClient(client -> fields(screen(client)).get(index))); }
    private static void clickWidget(ClientGameTestContext context, AbstractWidget widget) {
        double[] position = context.computeOnClient(client -> {
            var window = client.getWindow();
            var scale = ((ScaledCodonScreen) screen(client)).uiScale();
            return new double[]{scale.toGame(widget.getX() + widget.getWidth() / 2.0) * window.getScreenWidth() / window.getGuiScaledWidth(),
                scale.toGame(widget.getY() + widget.getHeight() / 2.0) * window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(position[0], position[1]);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(3);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
