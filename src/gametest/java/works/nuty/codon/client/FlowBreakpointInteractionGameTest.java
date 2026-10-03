package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.InputType;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.state.ClientBreakpointState;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientFunctionSourceState;
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.client.ui.*;
import works.nuty.codon.core.model.*;

/** Actual screen events over deterministic client observations; no claim about server execution. */
@SuppressWarnings("UnstableApiUsage")
public final class FlowBreakpointInteractionGameTest implements FabricClientGameTest {
    private static final SourceLocation LOCATION = new SourceLocation.Block(new BlockLocation(1, 64, 1, "minecraft:overworld"));
    private static final String COMMAND = "execute as @s at @s run function test:leaf";
    private static final List<ClientStagePreviewState.StageSpan> SPANS = List.of(
        new ClientStagePreviewState.StageSpan(0, 8, 13, false),
        new ClientStagePreviewState.StageSpan(1, 14, 19, false),
        new ClientStagePreviewState.StageSpan(2, 20, 23, false),
        new ClientStagePreviewState.StageSpan(3, 24, COMMAND.length(), true));

    @Override public void runTest(ClientGameTestContext context) {
        int oldScale = context.computeOnClient(client -> client.options.guiScale().get());
        try (var world = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1280, 720);
            context.getInput().setCursorPos(0, 0);
            var state = new ClientDebuggerState();
            CodonScreen screen = context.computeOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
                state.applyPause(snapshot(COMMAND, SPANS, 2));
                state.breakpoints().acceptPage(1, 0, true, List.of());
                preview(state, COMMAND, SPANS);
                var result = new CodonScreen(DebuggerPresentationGameTest.input(client, state), new DebuggerOverlay(state));
                client.setScreenAndShow(result);
                return result;
            });
            context.waitTicks(3);
            checkOrderAndAffordances(context, screen, state);
            checkExactEditors(context, screen, state);
            checkKeyboardToggle(context, screen, state);
            checkNavigationList(context, screen, state);
            checkWrappedTraversal(context, screen, state);
            checkSingleStage(context, screen, state);
            checkCrossFlowNavigation(context, screen, state);
            checkSourceStageEditors(context, screen);
        } finally {
            context.runOnClient(client -> {
                ScreenLayers.close(ScreenLayers.get(client.gui.screen()));
                client.setScreenAndShow(null);
                client.options.guiScale().set(oldScale);
                client.resizeGui();
            });
        }
    }

    private static void checkOrderAndAffordances(ClientGameTestContext context, CodonScreen screen, ClientDebuggerState state) {
        context.runOnClient(client -> {
            var markers = markers(screen);
            require(markers.size() == 5, "Root plus as/at/run/function each has one persistent BP affordance");
            require(state.breakpoints().definitions().isEmpty(), "Rendering unset affordances never creates definitions");
            for (var marker : markers) {
                require(marker.icon() == DebuggerIcon.BREAKPOINT_EMPTY && marker.active, "Unset marker is hollow and available");
                require(!(boolean) FunctionLineBreakpointGameTest.field(marker, "revealOnHover"), "Marker remains visible without hover");
            }
            var expected = new ArrayList<DebuggerButton>();
            expected.add(markers.getFirst());
            for (int index = 0; index < SPANS.size(); index++) {
                expected.add(markers.get(index + 1));
                expected.add(clause(screen, COMMAND.substring(SPANS.get(index).start(), SPANS.get(index).end())));
            }
            screen.setFocused(expected.getFirst());
            for (int index = 1; index < expected.size(); index++) {
                press(screen, InputConstants.KEY_TAB, false);
                require(screen.getFocused() == expected.get(index), "Tab interleaves each BP with its stage: " + index);
            }
            for (int index = expected.size() - 2; index >= 0; index--) {
                press(screen, InputConstants.KEY_TAB, true);
                require(screen.getFocused() == expected.get(index), "Shift+Tab reverses the same order: " + index);
            }
        });
    }

    private static void checkExactEditors(ClientGameTestContext context, CodonScreen screen, ClientDebuggerState state) {
        var targets = new ArrayList<BreakpointTarget>();
        targets.add(BreakpointTarget.whole(LOCATION));
        for (var span : SPANS) targets.add(BreakpointTarget.stage(LOCATION, span.index(), COMMAND));
        for (int index = 0; index < targets.size(); index++) {
            int markerIndex = index;
            var target = targets.get(index);
            for (boolean keyboard : new boolean[]{false, true}) {
                context.runOnClient(client -> {
                    var before = state.breakpoints().definitions();
                    var marker = markers(screen).get(markerIndex);
                    screen.setFocused(marker);
                    int selected = state.selectedFlowStageIndex();
                    if (keyboard) press(screen, InputConstants.KEY_F10, true);
                    else click(screen, marker, InputConstants.MOUSE_BUTTON_RIGHT);
                    require(ScreenLayers.get(screen) instanceof BreakpointConditionScreen, "BP opens the editor directly without a dropdown");
                    var editor = (BreakpointConditionScreen) ScreenLayers.get(screen);
                    require(editor.editsMarker(target, COMMAND), "Dialog owns the exact line/stage/fingerprint target: " + target);
                    require(((BreakpointDefinition) FunctionLineBreakpointGameTest.field(editor, "original")).target().equals(target),
                        "Editor cannot redirect the marker to another saved target");
                    require(state.selectedFlowStageIndex() == selected, "Right-click does not change inspected execution");
                    require(state.breakpoints().definitions().equals(before) && !state.breakpoints().pending(target),
                        "Opening an unset BP does not submit an edit");
                    // The line editor may request a parse. This fixture supplies the matching read-only reply.
                    preview(state, COMMAND, SPANS);
                });
                context.waitTicks(2);
                context.runOnClient(client -> ScreenLayers.get(screen).onClose());
                context.waitTicks(2);
                context.runOnClient(client -> {
                    require(screen.getFocused() == markers(screen).get(markerIndex), "Cancel restores the same logical BP");
                    require(state.breakpoints().definitions().isEmpty() && !state.breakpoints().pending(target),
                        "Cancel never creates or toggles a breakpoint");
                });
            }
        }
    }

    private static void checkKeyboardToggle(ClientGameTestContext context, CodonScreen screen, ClientDebuggerState state) {
        var target = BreakpointTarget.stage(LOCATION, 0, COMMAND);
        // Use this fixture's client state; acknowledgements are applied explicitly, independently of the live server state.
        for (int key : new int[]{InputConstants.KEY_RETURN, InputConstants.KEY_SPACE}) {
            context.runOnClient(client -> {
                var marker = markers(screen).get(1);
                screen.setFocused(marker);
                press(screen, key, false);
                require(state.breakpoints().pending(target), "Keyboard activation sends an edit for the focused exact BP");
            });
            context.waitTicks(2);
            context.runOnClient(client -> {
                var marker = markers(screen).get(1);
                require(!marker.active && screen.getFocused() == marker, "Pending toggle retains focus rather than advancing to the stage");
                press(screen, key, false);
                require(screen.getFocused() == marker, "Repeat activation while pending cannot move focus");
                // The first request created the definition; the second disables it.
                state.breakpoints().acceptPage(key == InputConstants.KEY_RETURN ? 2 : 3, 0, true,
                    List.of(BreakpointDefinition.plain(target).withEnabled(key == InputConstants.KEY_RETURN)));
                state.breakpoints().finish(key == InputConstants.KEY_RETURN ? 1 : 2, ClientBreakpointState.Result.APPLIED);
            });
            context.waitTicks(2);
            context.runOnClient(client -> require(screen.getFocused() == markers(screen).get(1), "Acknowledgement retains exact marker focus"));
        }
        context.runOnClient(client -> {
            require(markers(screen).get(1).icon() == DebuggerIcon.BREAKPOINT_EMPTY, "Disabled saved marker remains hollow and visible");
            press(screen, InputConstants.KEY_RETURN, false);
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            press(screen, InputConstants.KEY_TAB, false);
            require(screen.getFocused() == clause(screen, "as @s"), "Explicit Tab moves to the paired stage while BP is pending");
            state.breakpoints().finish(3, ClientBreakpointState.Result.NO_PERMISSION);
        });
        context.waitTicks(2);
        context.runOnClient(client -> require(screen.getFocused() == clause(screen, "as @s"), "Later rejection must not steal explicit focus"));
    }

    private static void checkNavigationList(ClientGameTestContext context, CodonScreen screen, ClientDebuggerState state) {
        var target = BreakpointTarget.stage(LOCATION, 0, COMMAND);
        var unavailable = BreakpointTarget.whole(new SourceLocation.Block(new BlockLocation(99, 64, 99, "minecraft:overworld")));
        var definitions = List.of(BreakpointDefinition.plain(target).withEnabled(false), BreakpointDefinition.plain(unavailable));
        context.runOnClient(client -> {
            state.breakpoints().acceptPage(4, 0, true, definitions);
            client.setScreenAndShow(new BreakpointListScreen(screen, state));
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            var list = client.gui.screen();
            var rows = buttons(list);
            require(rows.size() == 3, "List contains only its two destinations and Close, without row edit/delete/overflow controls");
            var unavailableRow = rows.stream().filter(button -> button.getMessage().getString().contains(BreakpointUi.target(unavailable))).findFirst().orElseThrow();
            require(!unavailableRow.active, "Unavailable destination stays listed but cannot mutate or retarget");
            var row = rows.stream().filter(button -> button.getMessage().getString().contains(BreakpointUi.target(target))).findFirst().orElseThrow();
            click(list, row, InputConstants.MOUSE_BUTTON_LEFT);
            require(client.gui.screen() == screen && state.selectedFlowStageIndex() == 0, "Saved row navigates to its exact Flow stage");
            require(state.breakpoints().definitions().containsAll(definitions) && state.breakpoints().definitions().size() == definitions.size()
                && !state.breakpoints().pending(target) && !state.breakpoints().pending(unavailable), "Navigation never enables, edits or deletes definitions");
        });
        context.waitTicks(2);
        context.runOnClient(client -> require(screen.getFocused() == markers(screen).get(1), "Navigation reveals the exact BP keyboard target"));
    }

    private static void checkWrappedTraversal(ClientGameTestContext context, CodonScreen screen, ClientDebuggerState state) {
        String command = "say " + "wrapped_value_".repeat(100);
        var spans = List.of(new ClientStagePreviewState.StageSpan(0, 0, command.length(), true));
        context.runOnClient(client -> { state.applyPause(snapshot(command, spans, 1)); preview(state, command, spans); });
        context.waitTicks(2);
        context.runOnClient(client -> screen.setFocused(markers(screen).getFirst()));
        var fragments = new ArrayList<String>();
        // Continue until Tab leaves the command fragments for the persistent detail/action region.
        for (int step = 0; step < 80; step++) {
            context.runOnClient(client -> press(screen, InputConstants.KEY_TAB, false));
            context.waitTicks(1);
            String fragment = context.computeOnClient(client -> screen.getFocused() instanceof DebuggerButton button
                && button.getHeight() == 16 && button.icon() == null ? button.getMessage().getString() : null);
            if (fragment == null) break;
            fragments.add(fragment);
        }
        require(fragments.size() > 2 && String.join("", fragments).equals(command), "Wrapped/off-screen traversal visits all text exactly once");
        for (int index = fragments.size() - 1; index >= 0; index--) {
            int expected = index;
            context.runOnClient(client -> press(screen, InputConstants.KEY_TAB, true));
            context.waitTicks(1);
            context.runOnClient(client -> require(((DebuggerButton) screen.getFocused()).getMessage().getString().equals(fragments.get(expected)),
                "Shift+Tab reverses wrapped fragments and reveals hidden rows"));
        }
    }

    private static void checkCrossFlowNavigation(ClientGameTestContext context, CodonScreen screen, ClientDebuggerState state) {
        var destination = new SourceLocation.Block(new BlockLocation(2, 64, 2, "minecraft:overworld"));
        String command = "execute as @e[tag=" + "wrapped_value_".repeat(80) + "] at @s run function test:leaf";
        int at = command.indexOf("at @s"), run = command.indexOf("run function"), terminal = command.indexOf("function test:leaf");
        var spans = List.of(new ClientStagePreviewState.StageSpan(0, 8, at - 1, false),
            new ClientStagePreviewState.StageSpan(1, at, run - 1, false),
            new ClientStagePreviewState.StageSpan(2, run, terminal - 1, false),
            new ClientStagePreviewState.StageSpan(3, terminal, command.length(), true));
        var oldFlow = snapshot(COMMAND, SPANS, SPANS.size()).executionFlows().getFirst();
        var stages = snapshot(command, spans, spans.size()).executionFlows().getFirst().stages();
        var newFlow = new ExecutionFlowTrace(88, destination, stages, false);
        var pause = new PauseSnapshot(LOCATION, oldFlow.stages().getFirst().command(), 0, List.of(), List.of(),
            List.of(oldFlow, newFlow), PauseReason.BREAKPOINT);
        var target = BreakpointTarget.stage(destination, 3, command);
        for (boolean hidden : new boolean[]{false, true}) {
            context.runOnClient(client -> {
                state.applyPause(pause);
                state.selectExecutionFlow(0);
                state.selectExecutionFlowStage(0);
                state.preferences().setCommandVisible(!hidden);
                state.breakpoints().acceptPage(hidden ? 6 : 5, 0, true, List.of(BreakpointDefinition.plain(target).withEnabled(false)));
                long request = state.stagePreviews().begin(destination);
                state.stagePreviews().accept(request, destination, ClientStagePreviewState.Status.READY, command, spans);
            });
            context.waitTicks(2);
            context.runOnClient(client -> client.setScreenAndShow(new BreakpointListScreen(screen, state)));
            context.waitTicks(2);
            context.runOnClient(client -> {
                var list = client.gui.screen();
                var row = buttons(list).stream().filter(button -> button.getMessage().getString().contains(BreakpointUi.target(target)))
                    .findFirst().orElseThrow();
                client.setLastInputType(InputType.MOUSE);
                click(list, row, InputConstants.MOUSE_BUTTON_LEFT);
                require(client.gui.screen() == screen && state.selectedExecutionFlow() == newFlow
                    && state.selectedFlowStageIndex() == 3, "List selects the exact destination flow and stage before rendering");
            });
            context.waitTicks(1);
            context.runOnClient(client -> {
                var marker = exactFlowMarker(screen, "flow-breakpoint-88-" + target);
                require(marker != null && screen.children().contains(marker) && screen.getFocused() == marker,
                    "First destination render reveals and focuses the off-screen exact marker; initially hidden=" + hidden);
                require(state.preferences().commandVisible() && !state.breakpoints().get(target).enabled()
                    && !state.breakpoints().pending(target), "Navigation reveals Flow without changing the disabled definition");
                press(screen, InputConstants.KEY_F10, true);
                require(ScreenLayers.get(screen) instanceof BreakpointConditionScreen editor && editor.editsMarker(target, command),
                    "The destination's keyboard focus opens its exact condition editor");
                ScreenLayers.get(screen).onClose();
            });
            context.waitTicks(2);
            context.takeScreenshot("codon-flow-cross-navigation-" + (hidden ? "hidden" : "visible"));
        }
        // A changed selection before the first destination render invalidates the queued request.
        context.runOnClient(client -> {
            state.selectExecutionFlow(0);
            state.selectExecutionFlowStage(0);
        });
        context.waitTicks(2);
        context.runOnClient(client -> client.setScreenAndShow(new BreakpointListScreen(screen, state)));
        context.waitTicks(2);
        context.runOnClient(client -> {
            var list = client.gui.screen();
            click(list, buttons(list).stream().filter(button -> button.getMessage().getString().contains(BreakpointUi.target(target)))
                .findFirst().orElseThrow(), InputConstants.MOUSE_BUTTON_LEFT);
            state.selectExecutionFlow(0);
            state.selectExecutionFlowStage(0);
            screen.setFocused(null);
            client.setLastInputType(InputType.MOUSE);
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(screen.getFocused() == null, "Changed context discards unresolved destination focus");
            state.selectExecutionFlow(1);
            state.selectExecutionFlowStage(3);
        });
        context.waitTicks(2);
        context.runOnClient(client -> require(screen.getFocused() == null,
            "Returning to a former destination cannot revive a cancelled focus request"));
    }

    private static DebuggerButton exactFlowMarker(CodonScreen screen, String id) {
        var overlay = (DebuggerOverlay) FunctionLineBreakpointGameTest.field(screen, "overlay");
        return (DebuggerButton) ((java.util.Map<?, ?>) FunctionLineBreakpointGameTest.field(overlay.navigation(), "visible")).get(id);
    }

    private static void checkSourceStageEditors(ClientGameTestContext context, CodonScreen parent) {
        var function = new FunctionId("test", "ui_source");
        var location = new SourceLocation.Function(new FunctionLocation(function, 1));
        var source = context.computeOnClient(client -> {
            var sources = new ClientFunctionSourceState();
            sources.select(function);
            long request = sources.drainRequests().getFirst().requestId();
            sources.accept(new ClientFunctionSourceState.SourcePage(request, ClientFunctionSourceState.Status.READY,
                function, "gametest", "source-stages", false, 0, true, List.of(COMMAND)));
            sources.rememberBrowseView(0, 0, 1, -1, 0);
            var state = CodonClientMod.state();
            state.breakpoints().reset();
            state.breakpoints().acceptPage(Long.MAX_VALUE, 0, true, List.of());
            long parse = state.stagePreviews().begin(location);
            state.stagePreviews().accept(parse, location, ClientStagePreviewState.Status.READY, COMMAND, SPANS);
            var screen = new FunctionSourceScreen(parent, sources);
            client.setScreenAndShow(screen);
            return screen;
        });
        context.waitTicks(2);
        for (var span : SPANS) {
            var target = BreakpointTarget.stage(location, span.index(), COMMAND);
            double[] pointer = context.computeOnClient(client -> {
                var hit = sourceHit(source, target, false);
                int x = (int) FunctionLineBreakpointGameTest.field(hit, "x") + 2;
                int y = (int) FunctionLineBreakpointGameTest.field(hit, "y") + 8;
                return new double[]{source.uiScale().toGame(x) * client.getWindow().getScreenWidth() / client.getWindow().getGuiScaledWidth(),
                    source.uiScale().toGame(y) * client.getWindow().getScreenHeight() / client.getWindow().getGuiScaledHeight()};
            });
            context.getInput().setCursorPos(pointer[0], pointer[1]);
            context.waitTicks(2);
            context.runOnClient(client -> {
                var marker = sourceHit(source, target, true);
                int x = (int) FunctionLineBreakpointGameTest.field(marker, "x") + 2;
                int y = (int) FunctionLineBreakpointGameTest.field(marker, "y") + 8;
                var event = new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0));
                require(source.mouseClicked(event, false), "Source stage marker accepts right-click");
                source.mouseReleased(event);
                require(ScreenLayers.get(source) instanceof BreakpointConditionScreen, "Source marker bypasses the body context menu");
                var editor = (BreakpointConditionScreen) ScreenLayers.get(source);
                require(editor.editsMarker(target, COMMAND) && !editor.editsMarker(BreakpointTarget.whole(location), COMMAND),
                    "Source as/at/run/function marker keeps its exact stage target");
                editor.onClose();
                press(source, InputConstants.KEY_F10, true);
                require(ScreenLayers.get(source) instanceof BreakpointConditionScreen
                    && ((BreakpointConditionScreen) ScreenLayers.get(source)).editsMarker(target, COMMAND),
                    "Source Shift+F10 retains the marker target after cancel");
                ScreenLayers.get(source).onClose();
                require(CodonClientMod.state().breakpoints().definitions().isEmpty()
                    && !CodonClientMod.state().breakpoints().pending(target), "Source open/cancel never creates a BP");
            });
            // Changing retained focus adds/removes an inline slot. Read the next stage's
            // coordinates only after the actual destination layout has been rendered.
            context.getInput().setCursorPos(0, 0);
            context.waitTicks(2);
            context.runOnClient(client -> {
                var controls = ((List<?>) FunctionLineBreakpointGameTest.field(source, "stageHits")).stream()
                    .filter(hit -> (boolean) FunctionLineBreakpointGameTest.field(hit, "control")).toList();
                require(controls.size() == 1 && target.equals(FunctionLineBreakpointGameTest.field(controls.getFirst(), "target")),
                    "After Cancel without hover, only the exact focused inline marker retains its hit slot");
            });
        }
    }

    private static Object sourceHit(FunctionSourceScreen screen, BreakpointTarget target, boolean control) {
        var hits = (List<?>) FunctionLineBreakpointGameTest.field(screen, "stageHits");
        return hits.stream().filter(hit -> target.equals(FunctionLineBreakpointGameTest.field(hit, "target"))
            && (boolean) FunctionLineBreakpointGameTest.field(hit, "control") == control).findFirst()
            .orElseThrow(() -> new AssertionError("Visible Source hit target missing: " + target + " marker=" + control));
    }

    private static void checkSingleStage(ClientGameTestContext context, CodonScreen screen, ClientDebuggerState state) {
        for (String command : List.of("say single", "function test:leaf")) {
            var spans = List.of(new ClientStagePreviewState.StageSpan(0, 0, command.length(), true));
            context.runOnClient(client -> { state.applyPause(snapshot(command, spans, 1)); preview(state, command, spans); });
            context.waitTicks(2);
            context.runOnClient(client -> {
                require(markers(screen).size() == 1, "Single-stage command has only one whole-line marker");
                click(screen, markers(screen).getFirst(), InputConstants.MOUSE_BUTTON_RIGHT);
                var editor = (BreakpointConditionScreen) ScreenLayers.get(screen);
                require(editor.editsMarker(BreakpointTarget.whole(LOCATION), command), "Single-stage marker always owns the line");
                require(!editor.editsMarker(BreakpointTarget.stage(LOCATION, 0, command), command), "Single-stage marker cannot silently become stage zero");
                editor.onClose();
            });
        }
    }

    private static PauseSnapshot snapshot(String command, List<ClientStagePreviewState.StageSpan> spans, int recorded) {
        var stages = new ArrayList<ExecutionFlowStage>();
        for (var span : spans.subList(0, recorded)) stages.add(new ExecutionFlowStage(span.index(),
            new CommandSnippet(command, span.start(), span.end()), List.of(), List.of(), List.of(), List.of(),
            1, 1, 0, span.terminal(), 0, 0, true, true, false));
        var flow = new ExecutionFlowTrace(77, LOCATION, stages, false);
        return new PauseSnapshot(LOCATION, stages.getLast().command(), 0, List.of(), List.of(), List.of(flow), PauseReason.BREAKPOINT);
    }

    private static void preview(ClientDebuggerState state, String command, List<ClientStagePreviewState.StageSpan> spans) {
        long request = state.stagePreviews().begin(LOCATION);
        require(state.stagePreviews().accept(request, LOCATION, ClientStagePreviewState.Status.READY, command, spans), "Fixture parse accepted");
    }

    private static List<DebuggerButton> buttons(Screen screen) {
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast).filter(button -> button.visible).toList();
    }

    private static List<DebuggerButton> markers(Screen screen) {
        return buttons(screen).stream().filter(button -> button.getWidth() == 14 && button.getHeight() == 16)
            .filter(button -> button.icon() == DebuggerIcon.BREAKPOINT || button.icon() == DebuggerIcon.BREAKPOINT_EMPTY)
            .sorted(Comparator.comparingInt(DebuggerButton::getY).thenComparingInt(DebuggerButton::getX)).toList();
    }

    private static DebuggerButton clause(Screen screen, String text) {
        return buttons(screen).stream().filter(button -> button.getMessage().getString().equals(text)).findFirst().orElseThrow();
    }

    private static void click(Screen screen, DebuggerButton button, int mouseButton) {
        var event = new MouseButtonEvent(button.getX() + 3, button.getY() + 3, new MouseButtonInfo(mouseButton, 0));
        require(screen.mouseClicked(event, false), "Screen accepts the requested click");
        screen.mouseReleased(event);
    }

    private static void press(Screen screen, int key, boolean shift) {
        net.minecraft.client.Minecraft.getInstance().setLastInputType(InputType.KEYBOARD_TAB);
        screen.keyPressed(new KeyEvent(key, 0, shift ? InputConstants.MOD_SHIFT : 0));
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
