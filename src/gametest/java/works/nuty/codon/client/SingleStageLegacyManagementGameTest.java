package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
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

/** Real loaded function/block stops and acknowledged exact legacy toggle/delete through Source/Flow. */
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
            client.setScreenAndShow(new BreakpointListScreen(parent, state, List.of(legacy.target())));
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            var row = client.gui.screen().children().stream().filter(DebuggerButton.class::isInstance)
                .map(DebuggerButton.class::cast).findFirst().orElseThrow();
            check(row.active, "saved single-stage row has a real destination");
            client.gui.screen().setFocused(row);
            client.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        });
        context.waitTicks(3);
        openMarker(context, whole);
        context.takeScreenshot(position == null ? "codon-legacy-function-options" : "codon-legacy-block-options");
        choose(context, 1);
        context.waitFor(client -> !CodonClientMod.state().breakpoints().pending(legacy.target())
            && !CodonClientMod.state().breakpoints().get(legacy.target()).enabled(), 200);
        context.runOnClient(client -> check(!CodonClientMod.state().breakpoints().get(whole).enabled()
            && unrelated.equals(CodonClientMod.state().breakpoints().get(unrelated.target())), "toggle changes only the saved stage"));
        if (position == null) {
            context.runOnClient(client -> client.player.connection.sendCommand("codon resume"));
            context.waitFor(client -> completed.get() && !CodonClientMod.state().isPaused(), 200);
            fire(server, null, completed);
            context.waitFor(client -> completed.get() && !CodonClientMod.state().isPaused(), 200);
        }
        openMarker(context, whole);
        choose(context, 2);
        context.runOnClient(client -> {
            var editor = (BreakpointConditionScreen) ScreenLayers.get(client.gui.screen());
            check(((BreakpointDefinition) FunctionLineBreakpointGameTest.field(editor, "original")).target().equals(legacy.target()),
                "saved options keep the exact legacy target");
            var delete = (AbstractWidget) FunctionLineBreakpointGameTest.field(editor, "deleteButton");
            editor.setFocused(delete);
            editor.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        });
        context.waitFor(client -> !CodonClientMod.state().breakpoints().pending(legacy.target())
            && CodonClientMod.state().breakpoints().get(legacy.target()) == null && ScreenLayers.get(client.gui.screen()) == null, 200);
        context.takeScreenshot(position == null ? "codon-legacy-function-removed" : "codon-legacy-block-removed");
        context.runOnClient(client -> check(!CodonClientMod.state().breakpoints().get(whole).enabled()
            && unrelated.equals(CodonClientMod.state().breakpoints().get(unrelated.target())), "delete preserves the line and unrelated breakpoint"));
        if (position != null) {
            context.runOnClient(client -> client.player.connection.sendCommand("codon resume"));
            context.waitFor(client -> completed.get() && !CodonClientMod.state().isPaused(), 200);
        }
        fire(server, position, completed);
        context.waitFor(client -> completed.get() && !CodonClientMod.state().isPaused(), 200);
    }

    private static void openMarker(ClientGameTestContext context, BreakpointTarget whole) {
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            if (screen instanceof FunctionSourceScreen) {
                int line = ((SourceLocation.Function) whole.location()).location().line();
                int x = FunctionLineBreakpointGameTest.value(screen, "lineMarkerX");
                int y = FunctionLineBreakpointGameTest.value(screen, "sourceLineTop")
                    + (line - (int) FunctionLineBreakpointGameTest.field(screen, "lineOffset") - 1) * 18 + 9;
                var event = new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0));
                screen.mouseClicked(event, false); screen.mouseReleased(event);
            } else {
                var overlay = FunctionLineBreakpointGameTest.field(screen, "overlay");
                var panel = FunctionLineBreakpointGameTest.field(overlay, "commandPanel");
                var cache = (Map<String, DebuggerButton>) FunctionLineBreakpointGameTest.field(panel, "cache");
                var marker = cache.get("flow-breakpoint-" + CodonClientMod.state().selectedExecutionFlow().invocationId() + "-" + whole);
                check(marker != null, "Flow exposes its sole whole-command marker");
                var event = new MouseButtonEvent(marker.getX() + 3, marker.getY() + 3,
                    new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0));
                screen.mouseClicked(event, false); screen.mouseReleased(event);
            }
            check(ScreenLayers.get(screen).getClass().getSimpleName().equals("DebuggerContextMenu"), "marker exposes saved-stage options");
        });
        context.waitTicks(2);
    }

    private static void choose(ClientGameTestContext context, int index) {
        context.runOnClient(client -> {
            var menu = ScreenLayers.get(client.gui.screen());
            for (int i = 0; i < index; i++) menu.keyPressed(new KeyEvent(InputConstants.KEY_DOWN, 0, 0));
            menu.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        });
        context.waitTicks(2);
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
