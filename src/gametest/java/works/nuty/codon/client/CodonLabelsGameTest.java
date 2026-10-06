package works.nuty.codon.client;

import java.util.List;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.ui.BreakpointListScreen;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.client.ui.ScaledCodonScreen;
import works.nuty.codon.client.ui.WatchDetailsScreen;
import works.nuty.codon.core.model.*;

/** Label/count and native rendering regression under acknowledged presentation fixtures. */
@SuppressWarnings("UnstableApiUsage")
public final class CodonLabelsGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        int oldScale = context.computeOnClient(client -> client.options.guiScale().get());
        String oldLanguage = context.computeOnClient(client -> client.getLanguageManager().getSelected());
        try (TestSingleplayerContext ignored = context.worldBuilder().create()) {
            for (String language : List.of("en_us", "ko_kr")) {
                language(context, language);
                for (int width : new int[] {1280, 640}) check(context, language, width);
            }
        } finally {
            context.runOnClient(client -> {
                client.setScreenAndShow(null);
                client.options.guiScale().set(oldScale);
                client.resizeGui();
            });
            language(context, oldLanguage);
        }
    }

    private record Fixture(ClientDebuggerState state, InputManager input, DebuggerOverlay overlay, CodonScreen screen) { }

    private static void check(ClientGameTestContext context, String language, int width) {
        context.getInput().resizeWindow(width, width == 640 ? 480 : 800);
        boolean korean = language.equals("ko_kr");
        var fixture = context.computeOnClient(client -> {
            client.options.guiScale().set(2);
            client.resizeGui();
            client.gui.hud.getChat().clearMessages(false);
            var state = new ClientDebuggerState();
            var source = new PauseSource(new Vec3d(1, 64, 2), -10, 45,
                new EntityRef(client.player.getUUID(), "Label fixture"), "minecraft:overworld");
            var location = new SourceLocation.Block(new BlockLocation(1, 64, 1, "minecraft:overworld"));
            state.applyPause(new PauseSnapshot(location, CommandSnippet.plain("say labels"), 0, List.of(),
                List.of(source), List.of(), PauseReason.BREAKPOINT, 1));
            state.selectSource(0);
            state.preferences().setInspectorVisible(true);
            state.preferences().setWatchesVisible(false);
            state.preferences().setCommandVisible(width != 640);
            var input = DebuggerPresentationGameTest.input(client, state);
            var overlay = new DebuggerOverlay(state);
            return new Fixture(state, input, overlay, new CodonScreen(input, overlay));
        });
        var first = BreakpointDefinition.plain(BreakpointTarget.whole(fixture.state().snapshot().location()));
        var second = BreakpointDefinition.plain(BreakpointTarget.whole(new SourceLocation.Block(
            new BlockLocation(2, 64, 1, "minecraft:overworld"))));
        String name = "codon-labels-" + language + "-" + width;
        long transferId = 0;
        for (int enabled : new int[] {2, 0, 1}) {
            long id = ++transferId;
            context.runOnClient(client -> {
                require(fixture.state().breakpoints().acceptPage(id, 0, true,
                    List.of(first.withEnabled(enabled > 0), second.withEnabled(enabled == 2))), "Fixture snapshot is accepted");
                client.setScreenAndShow(fixture.screen());
            });
            context.waitTicks(3);
            var button = context.computeOnClient(client -> fixture.screen().children().stream()
                .filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
                .filter(widget -> widget.getMessage().getString().equals(Component.translatable("codon.breakpoint.short_count", enabled).getString()))
                .findFirst().orElseThrow());
            context.runOnClient(client -> {
                var tooltip = (Tooltip) field(button, "tooltip");
                String actual = String.join(" ", tooltip.toCharSequence(client).stream().map(CodonLabelsGameTest::text).toList());
                String expected = korean ? "중단점 · 활성 " + enabled + "개 / 전체 2개"
                    : "Breakpoints · " + enabled + " enabled / 2 total";
                require(actual.equals(expected), "Toolbar tooltip explicitly counts enabled and total: " + actual);
            });
            hover(context, fixture.screen(), button);
            context.waitTicks(12);
            context.takeScreenshot(name + "-toolbar-" + enabled);
            context.runOnClient(client -> client.setScreenAndShow(new BreakpointListScreen(fixture.screen(), fixture.state())));
            capture(context, name + "-list-" + enabled);
            context.runOnClient(client -> {
                var list = (BreakpointListScreen) client.gui.screen();
                String header = (String) invoke(list, "countHeader", new Class<?>[0]);
                String expected = korean ? "중단점 · 2개 (활성 " + enabled + "개)" : "Breakpoints · 2 (" + enabled + " enabled)";
                require(header.equals(expected), "List header labels total and enabled: " + header);
                int panelWidth = (int) field(list, "panelWidth");
                require(client.font.width(header) <= panelWidth - 16, "Complete count header fits the native panel");
                require(client.font.width(Component.translatable("codon.breakpoint.list_header", 4096, 4096)) <= panelWidth - 16,
                    "Maximum breakpoint counts also fit the count header");
                require(fixture.state().breakpoints().definitions().size() == 2, "Viewing never drops disabled definitions");
            });
        }
        context.runOnClient(client -> client.setScreenAndShow(new BreakpointListScreen(fixture.screen(), fixture.state(), List.of(second.target()))));
        context.waitTicks(3);
        context.runOnClient(client -> require(invoke(client.gui.screen(), "countHeader", new Class<?>[0])
            .equals(Component.translatable("codon.breakpoint.saved_definitions_header", 1).getString()), "Filtered saved list keeps its own total"));
        for (var spec : List.of(new WatchSpec(WatchSpec.Kind.SCORE, "points", ""),
            new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Pos[0]"),
            new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:state", "counter"))) {
            context.runOnClient(client -> {
                fixture.state().watches().add(spec);
                client.setScreenAndShow(new WatchDetailsScreen(fixture.input(), fixture.state(), fixture.overlay(), fixture.state().watches().findId(spec)));
                String expression = ((Component) invoke(client.gui.screen(), "expression", new Class<?>[] {WatchSpec.class}, spec)).getString();
                String kind = switch (spec.kind()) {
                    case SCORE -> korean ? "점수" : "Score";
                    case ENTITY_NBT -> korean ? "엔티티 NBT" : "Entity NBT";
                    case STORAGE_NBT -> korean ? "저장소 NBT" : "Storage NBT";
                };
                String value = switch (spec.kind()) {
                    case SCORE -> "points";
                    case ENTITY_NBT -> "Pos[0]";
                    case STORAGE_NBT -> "demo:state / counter";
                };
                require(expression.equals(kind + ": " + value), "Watch prefix translates while syntax stays literal");
            });
            capture(context, name + "-" + spec.kind().name().toLowerCase(java.util.Locale.ROOT));
        }
        context.runOnClient(client -> client.setScreenAndShow(fixture.screen()));
        context.waitTicks(3);
        if (width == 640) {
            click(context, fixture.screen(), "codon.ui.view");
            click(context, fixture.screen(), "codon.ui.details");
            context.runOnClient(client -> require((boolean) field(fixture.overlay(), "showInspector"),
                "Native View → Details opens the narrow context inspector"));
        }
        capture(context, name + "-rotation");
    }

    private static void click(ClientGameTestContext context, ScaledCodonScreen screen, String key) {
        var button = context.computeOnClient(client -> screen.children().stream().filter(DebuggerButton.class::isInstance)
            .map(DebuggerButton.class::cast).filter(widget -> widget.getMessage().getString().replaceFirst("^● ", "").strip()
                .equals(Component.translatable(key).getString()))
            .findFirst().orElseThrow());
        hover(context, screen, button);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(3);
    }

    private static String text(FormattedCharSequence sequence) {
        var result = new StringBuilder();
        sequence.accept((index, style, codePoint) -> { result.appendCodePoint(codePoint); return true; });
        return result.toString();
    }

    private static Object field(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try {
            var method = target.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }

    private static void hover(ClientGameTestContext context, ScaledCodonScreen screen, DebuggerButton button) {
        double[] position = context.computeOnClient(client -> {
            var window = client.getWindow();
            return new double[] {screen.uiScale().toGame(button.getX() + button.getWidth() / 2.0) * window.getScreenWidth() / window.getGuiScaledWidth(),
                screen.uiScale().toGame(button.getY() + button.getHeight() / 2.0) * window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(position[0], position[1]);
    }

    private static void capture(ClientGameTestContext context, String name) {
        context.getInput().setCursorPos(0, 0);
        context.waitTicks(3);
        context.takeScreenshot(name);
    }

    private static void language(ClientGameTestContext context, String language) {
        if (context.computeOnClient(client -> client.getLanguageManager().getSelected().equals(language))) return;
        var reload = context.computeOnClient(client -> { client.getLanguageManager().setSelected(language); return client.reloadResourcePacks(); });
        context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 200);
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
