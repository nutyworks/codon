package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.InputType;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.network.ClientNetworking;
import works.nuty.codon.client.state.ClientBreakpointState;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.client.testmixin.CommandBlockInvoker;
import works.nuty.codon.client.ui.*;
import works.nuty.codon.core.model.*;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real Flow clicks configure never-observed stages, then Continue stops on their first occurrence. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerUnobservedFlowBreakpointGameTest implements FabricClientGameTest {
    private static final FunctionId FUNCTION = new FunctionId("codon_test", "condition_calls");

    @Override public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            Setup setup = world.getServer().computeOnServer(DebuggerUnobservedFlowBreakpointGameTest::setup);
            try {
                awaitPause(context, location(1), -1);
                openFlow(context);
                context.waitTicks(5);
                context.takeScreenshot("codon-unobserved-flow-before-interaction");
                context.runOnClient(client -> {
                    var flow = state().selectedExecutionFlow();
                    require(flow.stages().size() == 1 && flow.stages().getFirst().index() == 0,
                        "only the if-function stage has been observed before its first execution");
                    require(clause(client.gui.screen(), "say truthy returned") != null,
                        "Flow exposes the never-observed terminal as a selectable stage");
                });
                BreakpointTarget terminal = context.computeOnClient(client -> target(location(1), "say truthy returned"));
                configure(context, "say truthy returned", terminal, BreakpointCondition.ALWAYS);
                disableWhole(context, location(1));
                long firstPause = context.computeOnClient(client -> state().snapshot().pauseId());
                resume(context);
                awaitPause(context, location(1), firstPause);
                context.runOnClient(client -> {
                    var pause = state().snapshot();
                    var stage = state().selectedExecutionFlowStage();
                    require(pause.reason() == PauseReason.BREAKPOINT && stage.index() == terminal.stageIndex() && stage.terminal(),
                        "Continue reaches the configured terminal on its first occurrence");
                    require(stage.executionCount() == ExecutionFlowStage.UNMEASURED && stage.successCount() == ExecutionFlowStage.UNMEASURED,
                        "the terminal breakpoint stops before saying truthy returned");
                });
                context.takeScreenshot("codon-unobserved-flow-first-terminal-stop");
                long terminalPause = context.computeOnClient(client -> state().snapshot().pauseId());
                resume(context);
                awaitPause(context, location(2), terminalPause);
                context.runOnClient(client -> {
                    var first = state().snapshot().executionFlows().stream()
                        .filter(flow -> flow.location().equals(location(1))).findFirst().orElseThrow();
                    require(first.executionCount() == 1 && first.successCount() == 1,
                        "the first line executes exactly once after its stage breakpoint");
                });
                BreakpointCondition count = BreakpointCondition.count(BreakpointCondition.Kind.INPUT_COUNT,
                    BreakpointCondition.Comparison.EQ, 1);
                context.waitFor(client -> state().stagePreviews().get(location(2)) != null
                    && state().stagePreviews().get(location(2)).status() == ClientStagePreviewState.Status.READY, 200);
                BreakpointTarget modifier = context.computeOnClient(client -> target(location(2), "as @a"));
                configure(context, "as @a", modifier, count);
                disableWhole(context, location(2));
                long secondPause = context.computeOnClient(client -> state().snapshot().pauseId());
                resume(context);
                awaitPause(context, location(2), secondPause);
                context.runOnClient(client -> {
                    var stage = state().selectedExecutionFlowStage();
                    require(state().snapshot().reason() == PauseReason.BREAKPOINT && stage.index() == modifier.stageIndex()
                            && !stage.terminal() && stage.complete() && stage.inputCount() == 1,
                        "a condition saved on an unobserved modifier stops at its first measured result");
                    require(state().breakpoints().get(modifier).condition().equals(count),
                        "the exact acknowledged target and condition survive Continue");
                    require(state().selectedExecutionFlow().stages().stream().noneMatch(ExecutionFlowStage::terminal),
                        "the conditional stop occurs before the final say command");
                });
                context.takeScreenshot("codon-unobserved-flow-first-conditional-stop");
                resume(context);
                context.waitFor(client -> setup.finished().get() && !state().isPaused(), 200);
            } finally {
                AtomicBoolean cleaned = new AtomicBoolean();
                DebuggerTaskQueue.execute(setup.server(), () -> {
                    CodonMod.engine().clearBreakpoints();
                    CodonMod.engine().resetSession();
                    cleaned.set(true);
                });
                context.waitFor(client -> cleaned.get(), 200);
                context.runOnClient(client -> client.setScreenAndShow(null));
            }
        }
    }

    private static Setup setup(MinecraftServer server) {
        var player = server.getPlayerList().getPlayers().getFirst();
        server.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
        var engine = CodonMod.engine();
        engine.clearBreakpoints();
        for (int line : List.of(1, 2)) engine.saveBreakpoint(BreakpointDefinition.plain(BreakpointTarget.whole(location(line))));
        var level = player.level();
        BlockPos position = player.blockPosition().offset(7, 0, 7);
        level.setBlockAndUpdate(position, Blocks.COMMAND_BLOCK.defaultBlockState());
        var entity = (CommandBlockEntity) level.getBlockEntity(position);
        entity.getCommandBlock().setCommand("function codon_test:condition_calls");
        AtomicBoolean finished = new AtomicBoolean();
        server.schedule(server.wrapRunnable(() -> {
            try {
                ((CommandBlockInvoker) (Object) Blocks.COMMAND_BLOCK).codon$execute(
                    level.getBlockState(position), level, position, entity.getCommandBlock(), true);
            } finally { finished.set(true); }
        }));
        return new Setup(server, finished);
    }

    private static void openFlow(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280, 720);
        context.runOnClient(client -> {
            client.options.guiScale().set(2);
            client.resizeGui();
            var input = new InputManager(state(), ignored -> {});
            for (var key : client.options.keyMappings) switch (key.getName()) {
                case "key.codon.open_menu" -> input.menuKey = key;
                case "key.codon.breakpoint" -> input.breakpointKey = key;
                case "key.codon.resume" -> input.resumeKey = key;
                case "key.codon.step_over" -> input.stepOverKey = key;
                case "key.codon.step_into" -> input.stepIntoKey = key;
                case "key.codon.keep_freecam" -> input.keepFreecamKey = key;
            }
            client.setScreenAndShow(new CodonScreen(input, new DebuggerOverlay(state())));
        });
    }

    private static void configure(ClientGameTestContext context, String fragment, BreakpointTarget target,
                                  BreakpointCondition condition) {
        context.waitFor(client -> clause(client.gui.screen(), fragment) != null, 100);
        context.runOnClient(client -> click(client.gui.screen(), clause(client.gui.screen(), fragment), InputConstants.MOUSE_BUTTON_LEFT));
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(state().selectedExecutionFlowStage() == null && state().displayedSources().isEmpty()
                    && state().selectedPauseSourceIndex() == -1,
                "unobserved selection contains no invented record or borrowed live contexts");
        });
        long before = context.computeOnClient(client -> state().breakpoints().definitions().stream().filter(BreakpointDefinition::enabled).count());
        double[] pointer = context.computeOnClient(client -> {
            var clause = clause(client.gui.screen(), fragment);
            var marker = marker(client.gui.screen(), clause);
            return new double[]{(marker.getX() + 7.0) * client.getWindow().getScreenWidth() / client.gui.screen().width,
                (marker.getY() + 4.0) * client.getWindow().getScreenHeight() / client.gui.screen().height};
        });
        context.getInput().setCursorPos(pointer[0], pointer[1]);
        context.waitTicks(2);
        context.takeScreenshot("codon-unobserved-flow-hover-stage-" + target.stageIndex());
        context.runOnClient(client -> click(client.gui.screen(), marker(client.gui.screen(), clause(client.gui.screen(), fragment)),
            InputConstants.MOUSE_BUTTON_LEFT));
        context.waitFor(client -> state().breakpoints().get(target) != null && !state().breakpoints().pending(target), 200);
        context.runOnClient(client -> {
            require(state().breakpoints().definitions().stream().filter(BreakpointDefinition::enabled).count() == before + 1,
                "left-clicking the never-observed Flow marker adds one acknowledged breakpoint");
            click(client.gui.screen(), clause(client.gui.screen(), fragment), InputConstants.MOUSE_BUTTON_RIGHT);
            require(ScreenLayers.get(client.gui.screen()) instanceof BreakpointConditionScreen,
                "Flow right-click opens breakpoint options for an unobserved stage");
        });
        context.waitTicks(3);
        if (condition.kind() != BreakpointCondition.Kind.ALWAYS) {
            context.runOnClient(client -> {
                Screen layer = ScreenLayers.get(client.gui.screen());
                click(layer, button(layer, "Always ▾"), InputConstants.MOUSE_BUTTON_LEFT);
                click(layer, button(layer, "Input context count"), InputConstants.MOUSE_BUTTON_LEFT);
                click(layer, button(layer, "= ▾"), InputConstants.MOUSE_BUTTON_LEFT);
                click(layer, button(layer, "="), InputConstants.MOUSE_BUTTON_LEFT);
                layer.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                    .filter(value -> value.visible).findFirst().orElseThrow().setValue("1");
            });
        }
        context.takeScreenshot("codon-unobserved-flow-options-stage-" + target.stageIndex());
        context.runOnClient(client -> {
            Screen layer = ScreenLayers.get(client.gui.screen());
            click(layer, button(layer, "Save"), InputConstants.MOUSE_BUTTON_LEFT);
        });
        context.waitFor(client -> state().breakpoints().get(target) != null && !state().breakpoints().pending(target)
            && ScreenLayers.get(client.gui.screen()) == null, 200);
        context.runOnClient(client -> require(state().breakpoints().get(target).enabled()
                && state().breakpoints().get(target).condition().equals(condition),
            "server acknowledges the exact static stage index, fingerprint, and condition"));
    }

    private static void disableWhole(ClientGameTestContext context, SourceLocation location) {
        var target = BreakpointTarget.whole(location);
        context.runOnClient(client -> require(ClientNetworking.sendBreakpointEdit(state(), ClientBreakpointState.Action.TOGGLE,
            state().breakpoints().get(target)), "send whole-line disable through normal C2S transport"));
        context.waitFor(client -> !state().breakpoints().pending(target) && !state().breakpoints().get(target).enabled(), 200);
    }

    private static void awaitPause(ClientGameTestContext context, SourceLocation location, long previous) {
        context.waitFor(client -> state() != null && state().isPaused() && state().snapshot().pauseId() != previous
            && state().snapshot().location().equals(location), 200);
    }

    private static void resume(ClientGameTestContext context) {
        context.runOnClient(client -> client.player.connection.sendCommand("codon resume"));
    }

    private static SourceLocation location(int line) {
        return new SourceLocation.Function(new FunctionLocation(FUNCTION, line));
    }

    private static BreakpointTarget target(SourceLocation location, String fragment) {
        var preview = state().stagePreviews().get(location);
        var span = preview.spans().stream()
            .filter(value -> preview.savedCommand().substring(value.start(), value.end()).contains(fragment))
            .findFirst().orElseThrow();
        return BreakpointTarget.stage(location, span.index(), preview.savedCommand());
    }

    private static DebuggerButton clause(Screen screen, String fragment) {
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> button.getMessage().getString().contains(fragment)).findFirst().orElse(null);
    }

    private static DebuggerButton marker(Screen screen, DebuggerButton clause) {
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> button.getY() == clause.getY() && button.getX() == clause.getX() - 15 && button.getWidth() == 14)
            .findFirst().orElseThrow(() -> new AssertionError("Flow stage marker missing"));
    }

    private static AbstractButton button(Screen screen, String label) {
        return screen.children().stream().filter(AbstractButton.class::isInstance).map(AbstractButton.class::cast)
            .filter(button -> button.visible && button.getMessage().getString().equals(label)).findFirst()
            .orElseThrow(() -> new AssertionError("Missing button: " + label));
    }

    private static void click(Screen screen, AbstractButton button, int mouseButton) {
        require(button != null && button.active, "Flow control is active");
        var event = new MouseButtonEvent(button.getX() + button.getWidth() / 2.0, button.getY() + 3,
            new MouseButtonInfo(mouseButton, 0));
        net.minecraft.client.Minecraft.getInstance().setLastInputType(InputType.MOUSE);
        require(screen.mouseClicked(event, false), "native screen accepts the breakpoint interaction");
        screen.mouseReleased(event);
    }

    private static ClientDebuggerState state() { return CodonClientMod.state(); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private record Setup(MinecraftServer server, AtomicBoolean finished) { }
}
