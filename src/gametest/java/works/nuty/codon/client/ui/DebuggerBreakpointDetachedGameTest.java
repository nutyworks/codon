package works.nuty.codon.client.ui;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.client.CodonClientMod;
import works.nuty.codon.client.state.ClientBreakpointState;
import works.nuty.codon.client.state.ClientRequestFeedback;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;

/** Native server step and guarded modal with a simulated pending SAVE result. */
@SuppressWarnings("UnstableApiUsage")
public class DebuggerBreakpointDetachedGameTest implements FabricClientGameTest {
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            var server = world.getServer().computeOnServer(s -> s);
            world.getServer().runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                s.getPlayerList().op(p.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
                CodonMod.engine().clearBreakpoints();
                CodonMod.engine().toggleBlockBreakpoint(new BlockLocation(4, 80, 0, "minecraft:overworld"));
            });
            world.getServer().runCommand("gamemode creative @a");
            world.getServer().runCommand("tp @a 5 82 5 180 25");
            world.getServer().runCommand("fill 3 79 -1 6 79 1 minecraft:stone");
            world.getServer().runCommand("setblock 4 80 0 minecraft:command_block[facing=east]{Command:\"execute as @a run say audit\",auto:0b}");
            world.getServer().runCommand("setblock 5 80 0 minecraft:chain_command_block[facing=east]{Command:\"say next\",auto:1b}");
            world.getServer().runCommand("setblock 3 80 0 minecraft:redstone_block");
            var state = CodonClientMod.state();
            context.waitFor(c -> state.isPaused() && state.snapshot() != null, 200);
            var expected = context.computeOnClient(c -> state.snapshot());
            var target = BreakpointTarget.whole(expected.location());
            var edit = context.computeOnClient(c -> {
                var parent = new CodonScreen(CodonClientMod.input(), new DebuggerOverlay(state));
                c.setScreenAndShow(parent);
                var flow = state.selectedExecutionFlow();
                var modal = new BreakpointConditionScreen(parent, state, BreakpointDefinition.plain(target))
                    .withContextGuard(() -> state.snapshot() == expected && state.selectedExecutionFlow() == flow);
                ScreenLayers.open(parent, modal);
                var pending = state.breakpoints().begin(ClientBreakpointState.Action.SAVE, BreakpointDefinition.plain(target));
                require(pending != null && ScreenLayers.get(parent) == modal, "SAVE pending and modal open");
                modal.trackPending(pending.requestId());
                return pending;
            });
            require(DebuggerTaskQueue.execute(server, () -> server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(), "codon stepinto " + expected.pauseId())), "console step admitted");
            context.waitFor(c -> state.snapshot() != expected && ScreenLayers.get(c.gui.screen()) == null, 200);
            context.runOnClient(c -> {
                require(state.breakpoints().pending(target), "modal closed while SAVE remains pending");
                state.breakpoints().finish(edit.requestId(), ClientBreakpointState.Result.INVALID_TARGET);
                require(state.breakpoints().error(target) == ClientBreakpointState.Result.INVALID_TARGET, "failure retained on target");
                var notice = state.feedback().current(ClientRequestFeedback.Kind.BREAKPOINT);
                require(notice != null && notice.messageKey().equals("codon.breakpoint.error.invalid_target"),
                    "the failure remains visible after the modal closes");
                CodonMod.LOGGER.info("DETACHED MODAL PASS: native console step closed guarded modal before simulated SAVE rejection; error and toast retained");
            });
            context.waitTicks(20);
            context.takeScreenshot("codon-detached-modal-save-failure");
            context.runOnClient(c -> {
                var live = state.snapshot();
                for (boolean replyFirst : new boolean[]{false, true}) {
                    state.applyPause(live);
                    var parent = c.gui.screen();
                    var flow = state.selectedExecutionFlow();
                    var modal = new BreakpointConditionScreen(parent, state, BreakpointDefinition.plain(target))
                        .withContextGuard(() -> state.snapshot() == live && state.selectedExecutionFlow() == flow);
                    ScreenLayers.open(parent, modal);
                    var pending = state.breakpoints().begin(ClientBreakpointState.Action.SAVE, BreakpointDefinition.plain(target));
                    require(pending != null, "start same-frame SAVE");
                    modal.trackPending(pending.requestId());
                    if (replyFirst) state.breakpoints().finish(pending.requestId(), ClientBreakpointState.Result.INVALID_TARGET);
                    state.applyStep();
                    if (!replyFirst) state.breakpoints().finish(pending.requestId(), ClientBreakpointState.Result.INVALID_TARGET);
                    modal.tick();
                    var notice = state.feedback().current(ClientRequestFeedback.Kind.BREAKPOINT);
                    require(notice != null && notice.messageKey().equals("codon.breakpoint.error.invalid_target"),
                        "same-frame sync and failure must survive either ordering");
                }
                CodonMod.LOGGER.info("MODAL SAME-FRAME PASS: sync-first and reply-first both retain notice");
            });
            context.waitTicks(20);
            context.takeScreenshot("codon-same-frame-modal-failure");
            var done = new AtomicBoolean();
            DebuggerTaskQueue.execute(server, () -> { CodonMod.engine().clearBreakpoints(); CodonMod.engine().resetSession(); done.set(true); });
            context.waitFor(c -> done.get(), 200);
            context.runOnClient(c -> { state.reset(); CodonClientMod.freecam().synchronize(c); c.setScreenAndShow(null); });
        }
    }
}
