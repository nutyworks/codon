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
            context.waitTicks(3);
            context.takeScreenshot("codon-flow-legacy-condition");
            context.runOnClient(client -> {
                var panel = (CommandPanel) FunctionLineBreakpointGameTest.field(overlay, "commandPanel");
                var buttons = (Map<String, DebuggerButton>) FunctionLineBreakpointGameTest.field(panel, "cache");
                if (!buttons.get("selected-condition").getMessage().getString().equals(BreakpointUi.condition(legacy.condition())))
                    failures.add("Flow toolbar hides the saved legacy stage-zero condition");
                try {
                    var method = CommandPanel.class.getDeclaredMethod("conditionSummary", ExecutionFlowStage.class);
                    method.setAccessible(true);
                    if (!((String) method.invoke(panel, state.selectedExecutionFlowStage())).startsWith(BreakpointUi.condition(legacy.condition())))
                        failures.add("Flow condition summary hides the saved legacy stage-zero condition");
                    var summary = CommandPanel.class.getDeclaredMethod("summary");
                    summary.setAccessible(true);
                    if (!((String) summary.invoke(panel)).contains(BreakpointUi.condition(legacy.condition())))
                        failures.add("Flow visible summary hides the saved legacy stage-zero condition");
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            context.runOnClient(client -> {
                state.breakpoints().acceptPage(2, 0, true, List.of(legacy.withEnabled(false)));
                BreakpointUi.openCondition(client.gui.screen(), state, BreakpointTarget.whole(legacy.target().location()),
                    "say legacy_flow", 1, null);
            });
            context.waitTicks(2);
            context.takeScreenshot("codon-flow-legacy-disabled-editing");
            context.runOnClient(client -> {
                var panel = FunctionLineBreakpointGameTest.field(overlay, "commandPanel");
                var buttons = (Map<String, DebuggerButton>) FunctionLineBreakpointGameTest.field(panel, "cache");
                var marker = buttons.get("breakpoint-1-0");
                if (ScreenLayers.get(client.gui.screen()) == null || marker == null
                    || (boolean) FunctionLineBreakpointGameTest.field(marker, "revealOnHover"))
                    failures.add("Flow does not retain the exact disabled legacy line marker while editing");
                if (!buttons.get("selected-condition").getMessage().getString().equals(BreakpointUi.condition(legacy.condition())))
                    failures.add("Flow hides a disabled legacy condition in its toolbar");
                ScreenLayers.get(client.gui.screen()).onClose();
                if (state.breakpoints().get(legacy.target()).enabled()) failures.add("Flow Cancel enables the saved target");
                state.breakpoints().begin(ClientBreakpointState.Action.SAVE, legacy);
            });
            context.waitTicks(2);
            context.takeScreenshot("codon-flow-legacy-condition-pending");
            context.runOnClient(client -> {
                var panel = FunctionLineBreakpointGameTest.field(overlay, "commandPanel");
                var buttons = (Map<String, DebuggerButton>) FunctionLineBreakpointGameTest.field(panel, "cache");
                if (buttons.get("selected-condition").active)
                    failures.add("Flow toolbar remains enabled during the legacy target's pending edit");
                var whole = BreakpointTarget.whole(legacy.target().location());
                BreakpointUi.openCondition(client.gui.screen(), state, whole, "say legacy_flow", 1, null);
                if (ScreenLayers.get(client.gui.screen()) != null)
                    failures.add("Pending legacy edit allows opening a second condition editor");
                // End the presentation-only pending request before simulating rejected acknowledgements.
                state.breakpoints().reset();
                state.breakpoints().acceptPage(3, 0, true, List.of(legacy.withEnabled(false)));
            });
            for (var result : List.of(ClientBreakpointState.Result.NO_PERMISSION, ClientBreakpointState.Result.INVALID_TARGET)) {
                context.runOnClient(client -> {
                    var edit = state.breakpoints().begin(ClientBreakpointState.Action.TOGGLE, legacy);
                    state.breakpoints().finish(edit.requestId(), result);
                });
                double[] pointer = context.computeOnClient(client -> {
                    var panel = FunctionLineBreakpointGameTest.field(overlay, "commandPanel");
                    var buttons = (Map<String, DebuggerButton>) FunctionLineBreakpointGameTest.field(panel, "cache");
                    var marker = buttons.get("breakpoint-1-0");
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
                        FunctionLineBreakpointGameTest.field(buttons.get("breakpoint-1-0"), "tooltip");
                    var text = new StringBuilder();
                    for (var line : tooltip.toCharSequence(client)) {
                        line.accept((index, style, codePoint) -> { text.appendCodePoint(codePoint); return true; });
                        text.append(' ');
                    }
                    String expected = net.minecraft.network.chat.Component.translatable("codon.breakpoint.error."
                        + result.name().toLowerCase(java.util.Locale.ROOT)).getString();
                    if (!text.toString().replaceAll("\\s+", " ").contains(expected.replaceAll("\\s+", " ")))
                        failures.add("Flow legacy marker hides rejected toggle " + result + ": " + text);
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
                state.breakpoints().acceptPage(4, 0, true, List.of(BreakpointDefinition.plain(BreakpointTarget.whole(base.location()))
                    .withCondition(legacy.condition())));
                state.selectExecutionFlowStage(1);
            });
            context.waitTicks(2);
            context.takeScreenshot("codon-flow-terminal-whole-condition");
            context.runOnClient(client -> {
                var panel = FunctionLineBreakpointGameTest.field(overlay, "commandPanel");
                try {
                    var summary = CommandPanel.class.getDeclaredMethod("summary");
                    summary.setAccessible(true);
                    if (((String) summary.invoke(panel)).contains(BreakpointUi.condition(legacy.condition())))
                        failures.add("Flow attributes a whole-line modifier condition to the terminal stage");
                    state.selectExecutionFlowStage(0);
                    if (!((String) summary.invoke(panel)).contains(BreakpointUi.condition(legacy.condition())))
                        failures.add("Flow hides the applicable whole-line condition on its modifier stage");
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                state.reset();
                client.setScreenAndShow(null);
            });
            if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
        }
    }
}
