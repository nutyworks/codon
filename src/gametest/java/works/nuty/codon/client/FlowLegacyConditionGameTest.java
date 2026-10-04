package works.nuty.codon.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import works.nuty.codon.client.state.ClientBreakpointState;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.ui.*;
import works.nuty.codon.core.model.*;

/** Explicit Flow presentation fixture: saved legacy conditions and pending server requests. */
@SuppressWarnings({"UnstableApiUsage", "unchecked"})
public final class FlowLegacyConditionGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            context.getInput().resizeWindow(1280, 800);
            var state = new ClientDebuggerState();
            var overlay = context.computeOnClient(client -> new DebuggerOverlay(state));
            var failures = new ArrayList<String>();
            BreakpointDefinition legacy = context.computeOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
                var base = DebuggerPresentationGameTest.fixture(client);
                var command = CommandSnippet.plain("say legacy_flow");
                var frame = new CallFrame(0, base.location(), command, 1, 0);
                var source = new ExecutionFlowContext(1, base.pauseSources().getFirst());
                var stage = new ExecutionFlowStage(0, command, List.of(source), List.of(), List.of(), List.of(),
                    1, 0, 0, true, 1, 1, true, true, false, 1, List.of(frame));
                state.applyPause(new PauseSnapshot(base.location(), command, 0, List.of(frame), base.pauseSources(),
                    List.of(new ExecutionFlowTrace(1, base.location(), List.of(stage), false)), PauseReason.STEP));
                var definition = BreakpointDefinition.plain(BreakpointTarget.stage(base.location(), 0, command.text()))
                    .withCondition(BreakpointCondition.count(BreakpointCondition.Kind.INPUT_COUNT,
                        BreakpointCondition.Comparison.EQ, 1));
                state.breakpoints().acceptPage(1, 0, true, List.of(definition));
                client.setScreenAndShow(new CodonScreen(DebuggerPresentationGameTest.input(client, state), overlay));
                return definition;
            });
            var whole = BreakpointTarget.whole(legacy.target().location());
            var line = BreakpointDefinition.plain(whole).withCondition(legacy.condition());
            context.waitTicks(3);
            context.takeScreenshot("codon-flow-legacy-condition");
            context.runOnClient(client -> {
                var panel = (CommandPanel) FunctionLineBreakpointGameTest.field(overlay, "commandPanel");
                var buttons = (Map<String, DebuggerButton>) FunctionLineBreakpointGameTest.field(panel, "cache");
                if (buttons.containsKey("selected-condition"))
                    failures.add("Flow recreates the removed selected-condition footer control");
                try {
                    var method = CommandPanel.class.getDeclaredMethod("conditionSummary", ExecutionFlowStage.class);
                    method.setAccessible(true);
                    if (!((String) method.invoke(panel, state.selectedExecutionFlowStage())).isEmpty())
                        failures.add("Single-stage line summary borrows the separate legacy stage-zero condition");
                    state.breakpoints().acceptPage(2, 0, true, List.of(legacy, line));
                    if (!((String) method.invoke(panel, state.selectedExecutionFlowStage())).startsWith(BreakpointUi.condition(line.condition())))
                        failures.add("Flow hides the exact whole-line condition on a one-stage command");
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            context.runOnClient(client -> {
                state.breakpoints().acceptPage(3, 0, true, List.of(legacy.withEnabled(false), line.withEnabled(false)));
                openMarker(client.gui.screen(), marker(overlay, whole));
            });
            context.waitTicks(2);
            context.takeScreenshot("codon-flow-legacy-disabled-editing");
            context.runOnClient(client -> {
                var panel = FunctionLineBreakpointGameTest.field(overlay, "commandPanel");
                var buttons = (Map<String, DebuggerButton>) FunctionLineBreakpointGameTest.field(panel, "cache");
                var marker = buttons.get("flow-breakpoint-1-" + whole);
                if (ScreenLayers.get(client.gui.screen()) == null || marker == null
                    || (boolean) FunctionLineBreakpointGameTest.field(marker, "revealOnHover"))
                    failures.add("Flow does not retain the exact disabled whole-line marker while editing");
                if (!(ScreenLayers.get(client.gui.screen()) instanceof BreakpointConditionScreen editor)
                    || !editor.editsMarker(whole, "say legacy_flow") || editor.editsMarker(legacy.target(), "say legacy_flow"))
                    failures.add("Flow marker editor does not retain the exact whole-line target");
                ScreenLayers.get(client.gui.screen()).onClose();
                if (state.breakpoints().get(legacy.target()).enabled() || state.breakpoints().get(whole).enabled())
                    failures.add("Flow Cancel changes a saved definition");
                state.breakpoints().begin(ClientBreakpointState.Action.SAVE, line);
            });
            context.waitTicks(2);
            context.takeScreenshot("codon-flow-legacy-condition-pending");
            context.runOnClient(client -> {
                var panel = FunctionLineBreakpointGameTest.field(overlay, "commandPanel");
                var buttons = (Map<String, DebuggerButton>) FunctionLineBreakpointGameTest.field(panel, "cache");
                if (marker(overlay, whole).active)
                    failures.add("Exact Flow marker remains enabled during its pending edit");
                openMarker(client.gui.screen(), marker(overlay, whole));
                if (ScreenLayers.get(client.gui.screen()) != null)
                    failures.add("Pending whole-line edit allows opening a second condition editor");
                // End the presentation-only pending request before simulating rejected acknowledgements.
                state.breakpoints().reset();
                state.breakpoints().acceptPage(4, 0, true, List.of(legacy.withEnabled(false), line.withEnabled(false)));
            });
            for (var result : List.of(ClientBreakpointState.Result.NO_PERMISSION, ClientBreakpointState.Result.INVALID_TARGET)) {
                context.runOnClient(client -> {
                    var edit = state.breakpoints().begin(ClientBreakpointState.Action.TOGGLE, line);
                    state.breakpoints().finish(edit.requestId(), result);
                });
                double[] pointer = context.computeOnClient(client -> {
                    var panel = FunctionLineBreakpointGameTest.field(overlay, "commandPanel");
                    var buttons = (Map<String, DebuggerButton>) FunctionLineBreakpointGameTest.field(panel, "cache");
                    var marker = buttons.get("flow-breakpoint-1-" + whole);
                    return new double[]{(marker.getX() + 7.0) * client.getWindow().getScreenWidth() / client.gui.screen().width,
                        (marker.getY() + 4.0) * client.getWindow().getScreenHeight() / client.gui.screen().height};
                });
                context.getInput().setCursorPos(pointer[0], pointer[1]);
                context.waitTicks(12);
                context.takeScreenshot("codon-flow-legacy-rejected-" + result.name().toLowerCase(java.util.Locale.ROOT));
                context.runOnClient(client -> {
                    var panel = FunctionLineBreakpointGameTest.field(overlay, "commandPanel");
                    var buttons = (Map<String, DebuggerButton>) FunctionLineBreakpointGameTest.field(panel, "cache");
                    var tooltip = (net.minecraft.client.gui.components.Tooltip)
                        FunctionLineBreakpointGameTest.field(buttons.get("flow-breakpoint-1-" + whole), "tooltip");
                    var text = new StringBuilder();
                    for (var textLine : tooltip.toCharSequence(client)) {
                        textLine.accept((index, style, codePoint) -> { text.appendCodePoint(codePoint); return true; });
                        text.append(' ');
                    }
                    String expected = net.minecraft.network.chat.Component.translatable("codon.breakpoint.error."
                        + result.name().toLowerCase(java.util.Locale.ROOT)).getString();
                    if (!text.toString().replaceAll("\\s+", " ").contains(expected.replaceAll("\\s+", " ")))
                        failures.add("Flow exact line marker hides rejected toggle " + result + ": " + text);
                });
            }
            context.runOnClient(client -> {
                var base = DebuggerPresentationGameTest.fixture(client);
                var command = CommandSnippet.plain("execute as @s run say terminal_condition");
                var frame = new CallFrame(0, base.location(), command, 2, 0);
                var source = new ExecutionFlowContext(1, base.pauseSources().getFirst());
                var modifier = new ExecutionFlowStage(0, command, List.of(source), List.of(source),
                    List.of(new ExecutionFlowEdge(1, 1)), List.of(), 1, 1, 0, false, 0, 0, true, true, false, 1, List.of(frame));
                var terminal = new ExecutionFlowStage(1, command, List.of(source), List.of(), List.of(), List.of(),
                    1, 0, 0, true, 1, 1, true, true, false, 2, List.of(frame));
                state.applyPause(new PauseSnapshot(base.location(), command, 0, List.of(frame), base.pauseSources(),
                    List.of(new ExecutionFlowTrace(2, base.location(), List.of(modifier, terminal), false)), PauseReason.STEP));
                state.breakpoints().acceptPage(5, 0, true, List.of(BreakpointDefinition.plain(BreakpointTarget.whole(base.location()))
                    .withCondition(legacy.condition())));
                state.selectExecutionFlowStage(1);
            });
            context.waitTicks(2);
            context.takeScreenshot("codon-flow-terminal-whole-condition");
            context.runOnClient(client -> {
                var panel = FunctionLineBreakpointGameTest.field(overlay, "commandPanel");
                try {
                    var summary = CommandPanel.class.getDeclaredMethod("conditionSummary", ExecutionFlowStage.class);
                    summary.setAccessible(true);
                    if (((String) summary.invoke(panel, state.selectedExecutionFlowStage())).contains(BreakpointUi.condition(legacy.condition())))
                        failures.add("Flow attributes a whole-line modifier condition to the terminal stage");
                    state.selectExecutionFlowStage(0);
                    if (((String) summary.invoke(panel, state.selectedExecutionFlowStage())).contains(BreakpointUi.condition(legacy.condition())))
                        failures.add("Flow attributes the whole-line condition to an exact modifier stage");
                    var flow = state.selectedExecutionFlow();
                    var stage = state.selectedExecutionFlowStage();
                    var exact = BreakpointDefinition.plain(BreakpointTarget.stage(flow.location(), stage.index(), stage.command().text()))
                        .withCondition(legacy.condition());
                    state.breakpoints().acceptPage(6, 0, true, List.of(line, exact));
                    if (!((String) summary.invoke(panel, state.selectedExecutionFlowStage())).contains(BreakpointUi.condition(exact.condition())))
                        failures.add("Flow hides the modifier's own exact condition");
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                state.reset();
                client.setScreenAndShow(null);
            });
            if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
        }
    }

    private static DebuggerButton marker(DebuggerOverlay overlay, BreakpointTarget target) {
        var panel = FunctionLineBreakpointGameTest.field(overlay, "commandPanel");
        var buttons = (Map<String, DebuggerButton>) FunctionLineBreakpointGameTest.field(panel, "cache");
        var marker = buttons.get("flow-breakpoint-1-" + target);
        if (marker == null) throw new AssertionError("Exact single-stage whole-line marker is present");
        return marker;
    }

    private static void openMarker(net.minecraft.client.gui.screens.Screen screen, DebuggerButton marker) {
        var event = new net.minecraft.client.input.MouseButtonEvent(marker.getX() + 3, marker.getY() + 3,
            new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT, 0));
        screen.mouseClicked(event, false);
        screen.mouseReleased(event);
    }
}
