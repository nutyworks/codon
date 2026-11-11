package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.client.testmixin.CommandBlockInvoker;
import works.nuty.codon.client.ui.*;
import works.nuty.codon.core.model.*;

/** Real loaded function/block stops; the Breakpoints row edit action removes the exact saved stage in Source/Flow. */
@SuppressWarnings({"UnstableApiUsage", "unchecked"})
public final class SingleStageLegacyManagementGameTest implements FabricClientGameTest {
    private static final FunctionId FUNCTION = new FunctionId("codon_test", "condition_visibility");
    private static final String FUNCTION_COMMAND = "say legacy_condition", BLOCK_COMMAND = "say legacy_block";

    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            var server = world.getServer().computeOnServer(value -> {
                var player = value.getPlayerList().getPlayers().getFirst();
                value.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
                CodonMod.engine().clearBreakpoints();
                return value;
            });
            context.getInput().resizeWindow(1280, 800);
            var unrelated = BreakpointDefinition.plain(BreakpointTarget.whole(new SourceLocation.Block(
                new BlockLocation(10000, 80, 10000, "minecraft:overworld"))));
            world.getServer().runOnServer(value -> CodonMod.engine().saveBreakpoint(unrelated));
            AtomicBoolean completed = new AtomicBoolean(true);
            try {
                var function = new SourceLocation.Function(new FunctionLocation(FUNCTION, 7));
                context.runOnClient(client -> CodonClientMod.sources().selectAt(function.location()));
                context.waitFor(client -> CodonClientMod.sources().document() != null
                    && CodonClientMod.sources().document().id().equals(FUNCTION), 200);
                verify(context, server, function, FUNCTION_COMMAND, null, unrelated, completed);
                BlockPos position = world.getServer().computeOnServer(value -> {
                    var player = value.getPlayerList().getPlayers().getFirst();
                    var placed = player.blockPosition().offset(6, 0, 6);
                    var level = player.level();
                    level.setBlockAndUpdate(placed, Blocks.COMMAND_BLOCK.defaultBlockState());
                    ((CommandBlockEntity) level.getBlockEntity(placed)).getCommandBlock().setCommand(BLOCK_COMMAND);
                    level.sendBlockUpdated(placed, level.getBlockState(placed), level.getBlockState(placed), 3);
                    return placed;
                });
                var block = new SourceLocation.Block(new BlockLocation(position.getX(), position.getY(), position.getZ(), "minecraft:overworld"));
                verify(context, server, block, BLOCK_COMMAND, position, unrelated, completed);
            } finally {
                AtomicBoolean cleaned = new AtomicBoolean();
                DebuggerTaskQueue.execute(server, () -> {
                    CodonMod.engine().clearBreakpoints();
                    CodonMod.engine().resetSession();
                    cleaned.set(true);
                });
                context.waitFor(client -> cleaned.get() && completed.get(), 200);
                context.runOnClient(client -> client.setScreenAndShow(null));
            }
        }
    }

    private static boolean stageRowShown(ClientGameTestContext context, String stage) {
        return context.computeOnClient(client -> BreakpointPanelGameTestUi.rows(client.gui.screen()).stream()
            .anyMatch(row -> row.getMessage().getString().contains(stage)));
    }

    private static void verify(ClientGameTestContext context, MinecraftServer server, SourceLocation location, String command,
                               BlockPos position, BreakpointDefinition unrelated, AtomicBoolean completed) {
        var whole = BreakpointTarget.whole(location);
        var legacy = BreakpointDefinition.plain(BreakpointTarget.stage(location, 0, command));
        DebuggerTaskQueue.execute(server, () -> {
            CodonMod.engine().saveBreakpoint(legacy);
            CodonMod.engine().saveBreakpoint(BreakpointDefinition.plain(whole).withEnabled(false));
        });
        context.waitFor(client -> CodonClientMod.state().breakpoints().get(legacy.target()) != null
            && CodonClientMod.state().breakpoints().get(whole) != null, 200);
        fire(server, position, completed);
        context.waitFor(client -> CodonClientMod.state().isPaused()
            && CodonClientMod.state().snapshot().location().equals(location), 200);
        context.runOnClient(client -> {
            var state = CodonClientMod.state();
            var flow = state.selectedExecutionFlow();
            check(flow != null && flow.stages().size() == 1 && flow.stages().getFirst().terminal(), "native command has one terminal stage");
            var parent = new CodonScreen(DebuggerPresentationGameTest.input(client, state), new DebuggerOverlay(state));
            // Watches share the side column; hide them so all three rows fit the panel.
            state.preferences().setWatchesVisible(false);
            client.setScreenAndShow(parent);
        });
        context.waitTicks(2);
        BreakpointPanelGameTestUi.open(context);
        // The side column's height follows the Flow panel, so scroll until the stage row is visible.
        String stage = Component.translatable("codon.breakpoint.stage_target", 1).getString();
        for (int attempt = 0; attempt < 4 && !stageRowShown(context, stage); attempt++) {
            context.runOnClient(client -> {
                var first = BreakpointPanelGameTestUi.rows(client.gui.screen()).getFirst();
                client.gui.screen().mouseScrolled(first.getX() + 2, first.getY() + 2, 0, -1);
            });
            context.waitTicks(3);
        }
        context.runOnClient(client -> {
            var row = BreakpointPanelGameTestUi.rows(client.gui.screen()).stream()
                .filter(candidate -> candidate.getMessage().getString().contains(stage)).findFirst()
                .orElseThrow(() -> new AssertionError("no stage row among " + BreakpointPanelGameTestUi.rows(client.gui.screen()).stream().map(r -> r.getMessage().getString()).toList()));
            check(row.active, "saved single-stage row has a real destination");
            // The row's edit action navigates like the row and opens the strip for this exact definition.
            var edit = BreakpointPanelGameTestUi.edit(client.gui.screen(), row);
            check(edit.active, "saved single-stage row offers its edit action");
            client.gui.screen().setFocused(edit);
            client.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        });
        context.waitFor(client -> ConditionStripGameTestUi.open(client.gui.screen())
            && ConditionStripGameTestUi.strip(client.gui.screen()).bounds().width() > 0, 200);
        context.runOnClient(client -> {
            var strip = ConditionStripGameTestUi.strip(client.gui.screen());
            check(strip.target().equals(legacy.target()), "the edit action keeps the exact saved stage target");
            check(strip.editsMarker(whole, command), "the strip opens under the line marker that shows the saved stage");
            check(client.gui.screen() instanceof FunctionSourceScreen == (position == null),
                "a function opens in Source and a command block in Flow");
        });
        context.takeScreenshot(position == null ? "codon-legacy-function-strip" : "codon-legacy-block-strip");
        context.runOnClient(client -> ConditionStripGameTestUi.click(client.gui.screen(),
            ConditionStripGameTestUi.text("codon.breakpoint.strip.remove")));
        context.waitFor(client -> !CodonClientMod.state().breakpoints().pending(legacy.target())
            && CodonClientMod.state().breakpoints().get(legacy.target()) == null && !ConditionStripGameTestUi.open(client.gui.screen()), 200);
        context.takeScreenshot(position == null ? "codon-legacy-function-removed" : "codon-legacy-block-removed");
        context.runOnClient(client -> check(!CodonClientMod.state().breakpoints().get(whole).enabled()
            && unrelated.equals(CodonClientMod.state().breakpoints().get(unrelated.target())), "Remove preserves the line and unrelated breakpoint"));
        context.runOnClient(client -> client.player.connection.sendCommand("codon resume"));
        context.waitFor(client -> completed.get() && !CodonClientMod.state().isPaused(), 200);
        fire(server, position, completed);
        context.waitFor(client -> completed.get() && !CodonClientMod.state().isPaused(), 200);
    }

    private static void fire(MinecraftServer server, BlockPos position, AtomicBoolean completed) {
        completed.set(false);
        server.execute(() -> {
            try {
                if (position == null) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "function " + FUNCTION);
                else {
                    var level = server.getPlayerList().getPlayers().getFirst().level();
                    var entity = (CommandBlockEntity) level.getBlockEntity(position);
                    ((CommandBlockInvoker) (Object) Blocks.COMMAND_BLOCK).codon$execute(level.getBlockState(position), level,
                        position, entity.getCommandBlock(), true);
                }
            } finally { completed.set(true); }
        });
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
