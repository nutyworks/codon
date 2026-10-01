package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.client.ui.*;
import works.nuty.codon.client.testmixin.CommandBlockInvoker;
import works.nuty.codon.core.model.*;

/** Server-acknowledged single-stage editor/Flow controls and real first-invocation execution. */
@SuppressWarnings("UnstableApiUsage")
public final class SingleStageBreakpointGameTest implements FabricClientGameTest {
    private static final String COMMAND = "scoreboard players add @a codon_single 1";

    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            world.getServer().runCommand("scoreboard objectives add codon_single dummy");
            world.getServer().runCommand("scoreboard players set @a codon_single 0");
            BlockPos position = world.getServer().computeOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                server.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
                CodonMod.engine().clearBreakpoints();
                var placed = player.blockPosition().offset(6, 0, 6);
                var level = player.level();
                level.setBlockAndUpdate(placed, Blocks.COMMAND_BLOCK.defaultBlockState());
                ((CommandBlockEntity) level.getBlockEntity(placed)).getCommandBlock().setCommand(COMMAND);
                level.sendBlockUpdated(placed, level.getBlockState(placed), level.getBlockState(placed), 3);
                return placed;
            });
            var server = world.getServer().computeOnServer(value -> value);
            context.waitFor(client -> client.level.getBlockEntity(position) instanceof CommandBlockEntity, 200);
            var location = context.computeOnClient(client -> new SourceLocation.Block(new BlockLocation(position.getX(), position.getY(),
                position.getZ(), client.level.dimension().identifier().toString())));
            var whole = BreakpointTarget.whole(location);
            var legacy = BreakpointDefinition.plain(BreakpointTarget.stage(location, 0, COMMAND)).withEnabled(false);
            world.getServer().runOnServer(value -> CodonMod.engine().saveBreakpoint(legacy));
            context.runOnClient(client -> {
                var entity = (CommandBlockEntity) client.level.getBlockEntity(position);
                entity.getCommandBlock().setCommand(COMMAND);
                var screen = new CommandBlockEditScreen(entity);
                client.setScreenAndShow(screen);
                screen.updateGui();
            });
            context.waitFor(client -> {
                var state = CodonClientMod.state();
                var preview = state.stagePreviews().get(location);
                return state.breakpoints().ready() && preview != null && preview.status() == ClientStagePreviewState.Status.READY;
            }, 200);
            context.waitTicks(2);
            context.runOnClient(client -> {
                var state = CodonClientMod.state();
                var screen = client.gui.screen();
                var control = screen.children().stream().filter(InlineBreakpointButton.class::isInstance)
                    .map(InlineBreakpointButton.class::cast).findFirst().orElseThrow();
                state.stagePreviews().reset();
                screen.setFocused(control);
                screen.keyPressed(new KeyEvent(InputConstants.KEY_SPACE, 0, 0));
                check(!state.breakpoints().pending(whole) && !state.breakpoints().pending(legacy.target()),
                    "native editor missing preview defers legacy-sensitive toggle");
                check(!control.active, "native editor exposes the unresolved legacy control as disabled");
                screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 1));
                check(ScreenLayers.get(screen) == null, "native editor missing preview defers condition editing");
                state.stagePreviews().begin(location);
                screen.keyPressed(new KeyEvent(InputConstants.KEY_SPACE, 0, 0));
                screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 1));
                check(!state.breakpoints().pending(whole) && !state.breakpoints().pending(legacy.target())
                    && ScreenLayers.get(screen) == null, "native editor loading preview cannot create a second definition");
                long ready = state.stagePreviews().begin(location);
                state.stagePreviews().accept(ready, location, ClientStagePreviewState.Status.READY, COMMAND,
                    List.of(new ClientStagePreviewState.StageSpan(0, 0, COMMAND.length(), true)));
            });
            context.waitTicks(2);
            context.runOnClient(client -> {
                var screen = client.gui.screen();
                var control = screen.children().stream().filter(InlineBreakpointButton.class::isInstance)
                    .map(InlineBreakpointButton.class::cast).findFirst().orElseThrow();
                screen.setFocused(control);
                screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 1));
                var condition = (BreakpointConditionScreen) ScreenLayers.get(screen);
                check(legacy.equals(FunctionLineBreakpointGameTest.field(condition, "original")),
                    "READY native editor condition preserves the exact disabled legacy definition");
                condition.setFocused((net.minecraft.client.gui.components.AbstractWidget)
                    FunctionLineBreakpointGameTest.field(condition, "saveButton"));
                condition.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
                check(CodonClientMod.state().breakpoints().pending(legacy.target())
                    && !CodonClientMod.state().breakpoints().pending(whole), "native condition Save cannot duplicate the legacy target");
                condition.onClose();
            });
            context.waitFor(client -> !CodonClientMod.state().breakpoints().pending(legacy.target())
                && CodonClientMod.state().stagePreviews().get(location).status() == ClientStagePreviewState.Status.READY, 200);
            context.waitTicks(2);
            context.runOnClient(client -> {
                check(CodonClientMod.state().breakpoints().get(legacy.target()).enabled(),
                    "condition Save enables the original disabled legacy definition");
                var screen = client.gui.screen();
                var controls = screen.children().stream().filter(InlineBreakpointButton.class::isInstance)
                    .map(InlineBreakpointButton.class::cast).toList();
                check(controls.size() == 1 && controls.getFirst().target().equals(whole), "native single-stage editor exposes only the whole-command control");
                screen.setFocused(controls.getFirst());
                screen.keyPressed(new KeyEvent(InputConstants.KEY_SPACE, 0, 0));
            });
            context.waitFor(client -> !CodonClientMod.state().breakpoints().pending(legacy.target())
                && !CodonClientMod.state().breakpoints().get(legacy.target()).enabled(), 200);
            context.runOnClient(client -> {
                var screen = client.gui.screen();
                screen.keyPressed(new KeyEvent(InputConstants.KEY_SPACE, 0, 0));
            });
            context.waitFor(client -> {
                var state = CodonClientMod.state().breakpoints();
                return !state.pending(legacy.target()) && state.get(legacy.target()).enabled();
            }, 200);
            // An existing sole-stage target is enabled in place; no new second target is created.
            context.runOnClient(client -> check(CodonClientMod.state().breakpoints().get(whole) == null,
                "line control preserves a sole legacy definition rather than duplicating it"));
            context.takeScreenshot("codon-single-stage-editor-legacy");
            world.getServer().runOnServer(value -> CodonMod.engine().saveBreakpoint(BreakpointDefinition.plain(whole)));
            context.waitFor(client -> CodonClientMod.state().breakpoints().get(whole) != null, 200);
            context.runOnClient(client -> client.setScreenAndShow(null));
            AtomicBoolean completed = new AtomicBoolean();
            server.execute(() -> {
                var level = server.getPlayerList().getPlayers().getFirst().level();
                var entity = (CommandBlockEntity) level.getBlockEntity(position);
                try {
                    ((CommandBlockInvoker) (Object) Blocks.COMMAND_BLOCK).codon$execute(level.getBlockState(position), level,
                        position, entity.getCommandBlock(), true);
                } finally { completed.set(true); }
            });
            try {
                context.waitFor(client -> CodonClientMod.state().isPaused(), 200);
                context.runOnClient(client -> {
                    var state = CodonClientMod.state();
                    var flow = state.selectedExecutionFlow();
                    check(flow != null && flow.stages().size() == 1 && flow.stages().getFirst().terminal(), "real vanilla command records a single terminal stage");
                    var panel = new CommandPanel(state, () -> { });
                    try {
                        var method = CommandPanel.class.getDeclaredMethod("selectedBreakpoint");
                        method.setAccessible(true);
                        check(whole.equals(method.invoke(panel)), "Flow selected condition resolves to the whole line");
                    } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                    client.setScreenAndShow(new CodonScreen(DebuggerPresentationGameTest.input(client, state), new DebuggerOverlay(state)));
                });
                context.waitTicks(2);
                context.takeScreenshot("codon-single-stage-flow-native-pause");
                context.runOnClient(client -> {
                    var state = CodonClientMod.state();
                    BreakpointUi.toggle(state, whole, COMMAND, 1);
                });
                context.waitFor(client -> {
                    var state = CodonClientMod.state().breakpoints();
                    return !state.pending(whole) && !state.pending(legacy.target())
                        && !state.get(whole).enabled() && !state.get(legacy.target()).enabled();
                }, 200);
                context.runOnClient(client -> {
                    var state = CodonClientMod.state();
                    BreakpointUi.openCondition(client.gui.screen(), state, whole, COMMAND, 1, null);
                    check(client.gui.screen() instanceof BreakpointListScreen, "coexisting saved conditions are both accessible after disable");
                    check(state.breakpoints().definitions().size() == 2, "disabling preserves both saved definitions");
                });
                context.waitTicks(2);
                context.takeScreenshot("codon-single-stage-disabled-legacy-conditions");
                context.runOnClient(client -> client.player.connection.sendCommand("codon resume"));
                context.waitFor(client -> completed.get() && !CodonClientMod.state().isPaused(), 200);
                int score = world.getServer().computeOnServer(value -> {
                    var player = value.getPlayerList().getPlayers().getFirst();
                    return value.getScoreboard().getPlayerScoreInfo(player, value.getScoreboard().getObjective("codon_single")).value();
                });
                check(score == 1, "Continue executes the first occurrence exactly once");
                world.getServer().runCommand("codon breakpoint clear");
                context.waitFor(client -> CodonClientMod.state().breakpoints().definitions().isEmpty(), 200);
            } finally {
                AtomicBoolean cleaned = new AtomicBoolean();
                DebuggerTaskQueue.execute(server, () -> {
                    CodonMod.engine().clearBreakpoints();
                    CodonMod.engine().resetSession();
                    cleaned.set(true);
                });
                context.waitFor(client -> completed.get() && cleaned.get(), 200);
                context.runOnClient(client -> client.setScreenAndShow(null));
            }
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
