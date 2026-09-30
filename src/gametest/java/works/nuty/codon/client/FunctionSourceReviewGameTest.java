package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.state.ClientFunctionSourceState;
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.client.state.DebuggerPreferences;
import works.nuty.codon.client.ui.DebuggerTheme;
import works.nuty.codon.client.ui.FunctionSourceScreen;
import works.nuty.codon.client.ui.ScaledCodonScreen;
import works.nuty.codon.core.model.*;

/** Exact row-boundary, identifier-default and unselected reload regressions. */
@SuppressWarnings("UnstableApiUsage")
public final class FunctionSourceReviewGameTest implements FabricClientGameTest {
    private static final FunctionId MAIN = new FunctionId("pack", "main");
    private static final FunctionId PACK_HELPER = new FunctionId("pack", "helper");
    private static final FunctionId DEFAULT_HELPER = new FunctionId("minecraft", "helper");
    private static final FunctionId MATCHES = new FunctionId("minecraft", "matches");
    private final List<String> failures = new ArrayList<>();

    @Override public void runTest(ClientGameTestContext context) {
        failures.clear();
        try (var world = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1280, 720);
            context.getInput().setCursorPos(0, 0);
            context.runOnClient(client -> {
                client.options.guiScale().set(2); client.resizeGui();
                CodonClientMod.state().applyResume();
                CodonClientMod.state().breakpoints().reset();
            });
            world.getConnection().waitForChunksRender();
            var itemConditions = itemConditions();
            world.getServer().runOnServer(server -> {
                for (String command : itemConditions) {
                    var parsed = server.getCommands().getDispatcher().parse(command, server.createCommandSourceStack());
                    require(!parsed.getReader().canRead() && parsed.getExceptions().isEmpty(),
                        "actual 26.3 grammar accepts: " + command + "; remaining=" + parsed.getReader().getRemaining()
                            + "; errors=" + parsed.getExceptions());
                    var nodes = parsed.getContext().getLastChild().getNodes();
                    var target = nodes.getLast().getRange();
                    require(command.substring(target.getStart(), target.getEnd()).equals("pack:helper"),
                        "actual parser reaches the nested function target");
                    System.out.println("26.3 items/slots grammar PASS: " + command);
                }
            });
            verifyItemConditionLinks(context, itemConditions);
            verifyLinkBounds(context);
            verifyDefaultNamespace(context);
            verifyUnselectedReload(context);
            verifyExecuteArgumentLinks(context);
            verifyFindRebuild(context);
            verifyButtonFocus(context);
            context.runOnClient(client -> client.setScreenAndShow(null));
        }
        if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
    }

    private static List<String> itemConditions() {
        List<String> commands = new ArrayList<>();
        for (String condition : List.of("if", "unless"))
            for (String target : List.of("entity run", "block ~ ~1 ~-2"))
                for (String kind : List.of("items", "slots"))
                    commands.add("execute " + condition + " " + kind + " " + target + " container.*"
                        + (kind.equals("items") ? " minecraft:stick" : "") + " run function pack:helper");
        commands.add("execute if items entity @s weapon.mainhand minecraft:stick run function pack:helper");
        commands.add("execute unless items entity @s weapon.* * run function pack:helper");
        commands.add("execute unless items block 0 64 -2 container.0 minecraft:stick run function pack:helper");
        commands.add("execute if slots block 0 64 -2 container.0 run function pack:helper");
        commands.add("execute if items entity run weapon.mainhand minecraft:stick unless slots block ~ ~ ~ container.* run function pack:helper");
        commands.add("execute if items entity @s weapon.mainhand minecraft:stick[minecraft:custom_data~{label:'run function pack:other'}] run function pack:helper");
        return commands;
    }

    private void verifyItemConditionLinks(ClientGameTestContext context, List<String> commands) {
        int index = 0;
        for (String command : commands) {
            var sources = install(context, List.of(command));
            context.waitTicks(2);
            context.runOnClient(client -> set(client.gui.screen(), "horizontalOffset", invoke(client.gui.screen(), "maxHorizontalOffset")));
            context.waitTicks(2);
            if (index == 0 || index == 3 || index == commands.size() - 1)
                context.takeScreenshot("codon-source-items-slots-" + index);
            context.runOnClient(client -> {
                Screen screen = client.gui.screen();
                List<?> hits = list(screen, "functionHits");
                check(hits.size() == 1 && PACK_HELPER.equals(call(hits.getFirst(), "function", new Class<?>[0])),
                    "standard condition has exactly its real function link: " + command);
                if (!hits.isEmpty()) {
                    Object bounds = call(hits.getFirst(), "bounds", new Class<?>[0]);
                    click(screen, invoke(bounds, "x") + 1, invoke(bounds, "y") + 1);
                    check(PACK_HELPER.equals(sources.selected()), "standard condition click opens pack:helper: " + command);
                }
            });
            index++;
        }
    }

    private void verifyExecuteArgumentLinks(ClientGameTestContext context) {
        var sources = install(context, List.of("execute if score run objective matches 1 run function pack:helper"));
        context.waitTicks(2);
        context.runOnClient(client -> set(client.gui.screen(), "horizontalOffset", invoke(client.gui.screen(), "maxHorizontalOffset")));
        context.waitTicks(2);
        context.takeScreenshot("codon-source-followup-execute-run-argument");
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            List<?> hits = list(screen, "functionHits");
            check(hits.size() == 1, "a score holder named run retains the actual nested function link");
            if (!hits.isEmpty()) {
                Object bounds = call(hits.getFirst(), "bounds", new Class<?>[0]);
                click(screen, invoke(bounds, "x") + 1, invoke(bounds, "y") + 1);
                check(PACK_HELPER.equals(sources.selected()), "the actual nested link opens pack:helper");
            }
        });
        install(context, List.of("execute if score run function matches 1 run say ignored"));
        context.waitTicks(2);
        context.runOnClient(client -> check(list(client.gui.screen(), "functionHits").isEmpty(),
            "score objective named function must not link to the loaded minecraft:matches"));
    }

    private void verifyFindRebuild(ClientGameTestContext context) {
        var sources = install(context, List.of("say start", "say needle", "say needle"));
        context.waitTicks(2);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            ((EditBox) field(screen, "sourceSearch")).setValue("needle");
            check(invokeField(screen, "matchIndex") == 0 && sources.browseView().selectedLine() == 2,
                "typing selects the first result exactly once");
            call(screen, "nextMatch", new Class<?>[]{int.class}, 1);
            call(screen, "rebuildMatches", new Class<?>[0]);
            check(invokeField(screen, "matchIndex") == 1 && sources.browseView().selectedLine() == 3,
                "an unchanged rebuild retains the current result and selection");
            sources.select(PACK_HELPER);
            call(screen, "restoreBrowseView", new Class<?>[0]);
            call(screen, "updateCodeCache", new Class<?>[0]);
            check(invokeField(screen, "matchIndex") == -1 && list(screen, "matches").isEmpty(), "loading clears old matches");
            acceptSource(sources, PACK_HELPER, List.of("# " + "x".repeat(150) + " needle", "say needle"));
            call(screen, "updateCodeCache", new Class<?>[0]);
            check(invokeField(screen, "matchIndex") == 0 && sources.browseView().selectedLine() == 1
                && sources.browseView().horizontalOffset() > 0,
                "function switch with retained Find selects and reveals the first new result without Next");
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-source-followup-find-switch");
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            sources.refreshSource();
            call(screen, "updateCodeCache", new Class<?>[0]);
            acceptSource(sources, PACK_HELPER, List.of("# changed", "say needle", "say needle"));
            call(screen, "updateCodeCache", new Class<?>[0]);
            check(invokeField(screen, "matchIndex") == 0 && sources.browseView().selectedLine() == 2
                && sources.browseView().horizontalOffset() == 0,
                "Reload with retained Find selects and reveals the first replacement result without Next");
            check(((EditBox) field(screen, "sourceSearch")).getValue().equals("needle"), "Find query survives source changes");
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-source-followup-find-reload");
    }

    private void verifyButtonFocus(ClientGameTestContext context) {
        var sources = install(context, java.util.stream.IntStream.range(0, 30)
            .mapToObj(i -> "say row_" + i + " " + "x".repeat(150)).toList());
        context.waitTicks(2);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            for (int key : new int[]{InputConstants.KEY_UP, InputConstants.KEY_DOWN, InputConstants.KEY_PAGEUP,
                InputConstants.KEY_PAGEDOWN, InputConstants.KEY_HOME, InputConstants.KEY_END,
                InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT}) {
                call(screen, "selectLine", new Class<?>[]{int.class}, 2);
                set(screen, "horizontalOffset", 60);
                call(screen, "rememberView", new Class<?>[0]);
                screen.setFocused((net.minecraft.client.gui.components.events.GuiEventListener) field(screen, "refresh"));
                var before = sources.browseView();
                screen.keyPressed(new KeyEvent(key, 0, 0));
                check(sources.browseView().equals(before), "toolbar focus blocks Source navigation key " + key);
            }
            screen.setFocused(null);
            screen.keyPressed(new KeyEvent(InputConstants.KEY_DOWN, 0, 0));
            screen.keyPressed(new KeyEvent(InputConstants.KEY_RIGHT, 0, 0));
            check(sources.browseView().selectedLine() == 3 && sources.browseView().horizontalOffset() == 90,
                "code focus preserves line and horizontal keyboard navigation");
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-source-followup-code-focus");
    }

    private static void acceptSource(ClientFunctionSourceState sources, FunctionId function, List<String> lines) {
        long request = sources.drainRequests().stream().filter(ClientFunctionSourceState.Request.ReadFunction.class::isInstance)
            .map(ClientFunctionSourceState.Request.ReadFunction.class::cast).reduce((a, b) -> b).orElseThrow().requestId();
        sources.accept(new ClientFunctionSourceState.SourcePage(request, ClientFunctionSourceState.Status.READY,
            function, "gametest", "followup", false, 0, true, lines));
        require(sources.document() != null && sources.document().lines().equals(lines), "followup source accepted");
    }

    private static Object field(Object target, String name) {
        try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static int invokeField(Object target, String name) { return (int) field(target, name); }
    private static void set(Object target, String name, Object value) {
        try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void verifyLinkBounds(ClientGameTestContext context) {
        var sources = install(context, List.of("function pack:helper", "say next row", "say final"));
        context.waitTicks(2);
        context.takeScreenshot("codon-source-review-link-boundary-before");
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            int x = codeX(screen) + client.font.width("function ") + 5;
            int y = invoke(screen, "sourceLineTop");
            click(screen, x, y + 18);
            check(MAIN.equals(sources.selected()) && sources.browseView().selectedLine() == 2,
                "first pixel of row 2 must select row 2 rather than following row 1's function link; actual=" + sources.selected());
        });
        var ownRow = install(context, List.of("function pack:helper", "say next row", "say final"));
        context.waitTicks(2);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            click(screen, codeX(screen) + client.font.width("function ") + 5, invoke(screen, "sourceLineTop"));
            check(PACK_HELPER.equals(ownRow.selected()), "first pixel of the link's own row remains clickable");
        });
    }

    private void verifyDefaultNamespace(ClientGameTestContext context) {
        var sources = install(context, List.of("function helper", "say next row", "function pack:helper"));
        context.waitTicks(2);
        context.takeScreenshot("codon-source-review-namespace-collision");
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            click(screen, codeX(screen) + client.font.width("function ") + 5, invoke(screen, "sourceLineTop") + 8);
            check(DEFAULT_HELPER.equals(sources.selected()),
                "unqualified helper must follow Minecraft 26.3's minecraft:helper despite pack:helper also being loaded; actual=" + sources.selected());
        });
        context.waitTicks(1);
        context.takeScreenshot("codon-source-review-namespace-resolved");
    }

    private void verifyUnselectedReload(ClientGameTestContext context) {
        String oldCommand = "say old", replacement = "say replacement", unchanged = "say second";
        var sources = install(context, List.of(oldCommand, unchanged));
        var first = new SourceLocation.Function(new FunctionLocation(MAIN, 1));
        var second = new SourceLocation.Function(new FunctionLocation(MAIN, 2));
        var obsolete = BreakpointDefinition.plain(BreakpointTarget.stage(first, 0, oldCommand));
        context.waitTicks(2);
        context.runOnClient(client -> {
            var state = CodonClientMod.state();
            state.breakpoints().reset();
            state.breakpoints().acceptPage(1, 0, true, List.of(obsolete));
            preview(first, oldCommand); preview(second, unchanged);
            call(client.gui.screen(), "stagesForLine", new Class<?>[]{int.class, boolean.class}, 1, false);
            call(client.gui.screen(), "stagesForLine", new Class<?>[]{int.class, boolean.class}, 2, false);
        });
        pointer(context, context.computeOnClient(client -> new double[]{codeX(client.gui.screen()) + 8,
            invoke(client.gui.screen(), "sourceLineTop") + 9}));
        context.takeScreenshot("codon-source-review-reload-old-preview");
        var before = context.computeOnClient(client -> new ClientStagePreviewState.Preview[]{
            CodonClientMod.state().stagePreviews().get(first), CodonClientMod.state().stagePreviews().get(second)});
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            sources.refreshSource();
            long request = sources.drainRequests().stream().filter(ClientFunctionSourceState.Request.ReadFunction.class::isInstance)
                .map(ClientFunctionSourceState.Request.ReadFunction.class::cast).reduce((a, b) -> b).orElseThrow().requestId();
            sources.accept(new ClientFunctionSourceState.SourcePage(request, ClientFunctionSourceState.Status.READY,
                MAIN, "gametest", "review-reloaded", false, 0, true, List.of(replacement, unchanged)));
            require(sources.document() != null && sources.document().lines().getFirst().equals(replacement), "reloaded source accepted");
            call(screen, "updateCodeCache", new Class<?>[0]);
        });
        // Keep the actual native pointer over the changed, unselected row. Rendering must
        // request the preview, even if the synthetic function's NOT_FOUND reply arrives.
        context.waitTicks(2);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            var previews = CodonClientMod.state().stagePreviews();
            check(previews.get(first) != before[0],
                "native hover on the unselected reloaded row refreshes its mismatched READY preview");
            check(previews.get(second) == before[1] && sources.browseView().selectedLine() == 2,
                "hover refresh retains the second row's preview and original selection");
            // Isolate a pending request from the fixture server's reply, then exercise the
            // production hover path repeatedly to prove it does not replace LOADING.
            previews.begin(first);
            var loading = previews.get(first);
            for (int frame = 0; frame < 4; frame++)
                call(screen, "stagesForLine", new Class<?>[]{int.class, boolean.class}, 1, true);
            check(previews.get(first) == loading, "LOADING hover retains its in-flight request");
        });
        context.runOnClient(client -> preview(first, replacement));
        pointer(context, context.computeOnClient(client -> new double[]{codeX(client.gui.screen()) + 8,
            invoke(client.gui.screen(), "sourceLineTop") + 9}));
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            List<?> hits = list(screen, "stageHits");
            var current = BreakpointTarget.stage(first, 0, replacement);
            check(hits.stream().anyMatch(hit -> current.equals(call(hit, "target", new Class<?>[0]))),
                "fresh nonempty preview exposes the current command's stage candidate without selecting its row");
            check(hits.stream().noneMatch(hit -> obsolete.target().equals(call(hit, "target", new Class<?>[0]))),
                "obsolete fingerprint must never become a current stage click target");
        });
        assertReviewWarning(context, "codon-source-review-reload-current-hover");
        context.getInput().setCursorPos(0, 0);
        context.waitTicks(2);
        assertReviewWarning(context, "codon-source-review-reload-leave");
        context.runOnClient(client -> {
            var definitions = CodonClientMod.state().breakpoints();
            check(definitions.get(obsolete.target()).equals(obsolete), "reload does not mutate acknowledged breakpoint identity or enabled state");
            definitions.acceptPage(2, 0, true, List.of(obsolete.withStaleSource(true)));
        });
        context.waitTicks(2);
        assertReviewWarning(context, "codon-source-review-server-stale-disabled");
    }

    private void assertReviewWarning(ClientGameTestContext context, String name) {
        try {
            int[] geometry = context.computeOnClient(client -> {
                Screen screen = client.gui.screen();
                return new int[]{screen.width, screen.height, invoke(screen, "sourceLeft"), invoke(screen, "sourceLineTop"), invoke(screen, "codeRight")};
            });
            var image = javax.imageio.ImageIO.read(context.takeScreenshot(name).toFile());
            double sx = (double) image.getWidth() / geometry[0], sy = (double) image.getHeight() / geometry[1];
            boolean warning = false, active = false;
            for (int x = (int) Math.ceil((geometry[2] + 10) * sx); x < (int) Math.floor((geometry[2] + 14) * sx); x++)
                for (int y = (int) Math.ceil((geometry[3] + 5) * sy); y < (int) Math.floor((geometry[3] + 14) * sy); y++)
                    warning |= (image.getRGB(x, y) & 0xFFFFFF) == (DebuggerTheme.AMBER & 0xFFFFFF);
            for (int x = (int) Math.ceil((geometry[2] + 16) * sx); x < (int) Math.floor(geometry[4] * sx); x++)
                for (int y = (int) Math.ceil((geometry[3] + 5) * sy); y < (int) Math.floor((geometry[3] + 14) * sy); y++)
                    active |= (image.getRGB(x, y) & 0xFFFFFF) == (DebuggerTheme.RED & 0xFFFFFF);
            check(warning, name + ": obsolete breakpoint review warning remains visible even with a nonempty new preview");
            check(!active, name + ": obsolete breakpoint must not masquerade as a current enabled marker");
        } catch (java.io.IOException error) { throw new AssertionError(error); }
    }

    private static ClientFunctionSourceState install(ClientGameTestContext context, List<String> lines) {
        return context.computeOnClient(client -> {
            var sources = new ClientFunctionSourceState();
            sources.select(MAIN);
            long read = sources.drainRequests().getFirst().requestId();
            sources.accept(new ClientFunctionSourceState.SourcePage(read, ClientFunctionSourceState.Status.READY,
                MAIN, "gametest", "review-source", false, 0, true, lines));
            require(sources.document() != null && sources.document().lines().equals(lines), "source fixture accepted");
            sources.open();
            long list = sources.drainRequests().getFirst().requestId();
            sources.accept(new ClientFunctionSourceState.ListPage(list, ClientFunctionSourceState.Status.READY, 0, true,
                List.of(MAIN, PACK_HELPER, DEFAULT_HELPER, MATCHES)));
            sources.rememberBrowseView(0, 0, 2, -1, 0);
            var preferences = new DebuggerPreferences();
            DebuggerTheme.usePreferences(preferences);
            client.setScreenAndShow(new FunctionSourceScreen(new ScaledCodonScreen(Component.empty(), preferences) { }, sources));
            return sources;
        });
    }

    private static void preview(SourceLocation.Function location, String command) {
        var previews = CodonClientMod.state().stagePreviews();
        long request = previews.begin(location);
        require(previews.accept(request, location, ClientStagePreviewState.Status.READY, command,
            List.of(new ClientStagePreviewState.StageSpan(0, 0, command.length(), true))), "authoritative fixture preview accepted");
    }

    private static void click(Screen screen, double x, double y) {
        var event = new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
        screen.mouseClicked(event, false); screen.mouseReleased(event);
    }

    private static int codeX(Screen screen) { return invoke(screen, "sourceLeft") + invoke(screen, "gutterWidth"); }
    private static int invoke(Object target, String name) { return (int) call(target, name, new Class<?>[0]); }
    private static Object call(Object target, String name, Class<?>[] types, Object... arguments) {
        try { var method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, arguments); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static List<?> list(Object target, String name) {
        try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return (List<?>) field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void pointer(ClientGameTestContext context, double[] local) {
        double[] nativePoint = context.computeOnClient(client -> {
            var screen = (ScaledCodonScreen) client.gui.screen();
            var window = client.getWindow();
            return new double[]{screen.uiScale().toGame(local[0]) * window.getScreenWidth() / window.getGuiScaledWidth(),
                screen.uiScale().toGame(local[1]) * window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(nativePoint[0], nativePoint[1]);
        context.waitTicks(2);
    }
    private void check(boolean condition, String message) { if (!condition) failures.add(message); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
