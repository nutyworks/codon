package works.nuty.codon.client;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import works.nuty.codon.client.ui.DebuggerFeedbackToast;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.*;
import works.nuty.codon.core.model.*;
import works.nuty.codon.network.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Real server step and ordered packets; delayed delivery simulates a stale client view. */
@SuppressWarnings({"UnstableApiUsage","unchecked"})
public final class DebuggerControlOrderingGameTest implements FabricClientGameTest {
    private static void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private static volatile ClientRequestFeedback.Notice expectedNotice;
    private static volatile boolean expectedToastVisible;

    public static void observeToastDraw(SystemToast toast, GuiGraphicsExtractor graphics) {
        if (!(toast instanceof DebuggerFeedbackToast feedback) || feedback.notice() != expectedNotice
            || feedback.getWantedVisibility() != SystemToast.Visibility.SHOW) return;
        float x = graphics.pose().m20(), y = graphics.pose().m21();
        if (x >= 0 && y >= 0 && x + toast.width() <= graphics.guiWidth()
            && y + toast.height() <= graphics.guiHeight()
            && graphics.containsPointInScissor((int) x, (int) y)
            && graphics.containsPointInScissor((int) (x + toast.width() - 1), (int) (y + toast.height() - 1))) {
            expectedToastVisible = true;
        }
    }

    private static ClientDebuggerState state() { return CodonClientMod.state(); }
    @Override public void runTest(ClientGameTestContext context) {
        try(TestSingleplayerContext world=context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            var server=world.getServer().computeOnServer(s->s);
            world.getServer().runOnServer(s->{
                var player=s.getPlayerList().getPlayers().getFirst();
                s.getPlayerList().op(player.nameAndId(),Optional.of(LevelBasedPermissionSet.OWNER),Optional.empty());
                CodonMod.engine().clearBreakpoints();
                CodonMod.engine().toggleBlockBreakpoint(new BlockLocation(4,80,0,"minecraft:overworld"));
                CodonMod.engine().toggleBlockBreakpoint(new BlockLocation(5,80,0,"minecraft:overworld"));
            });
            world.getServer().runCommand("scoreboard objectives add ordering_audit dummy");
            world.getServer().runCommand("gamemode creative @a");
            world.getServer().runCommand("tp @a 5 82 5 180 25");
            world.getServer().runCommand("fill 3 79 -1 6 79 1 minecraft:stone");
            world.getServer().runCommand("setblock 4 80 0 minecraft:command_block[facing=east]{Command:\"execute as @a run scoreboard players add @s ordering_audit 1\",auto:0b}");
            world.getServer().runCommand("setblock 5 80 0 minecraft:chain_command_block[facing=east]{Command:\"execute as @a run scoreboard players add @s ordering_audit 10\",auto:1b}");
            world.getServer().runCommand("setblock 3 80 0 minecraft:redstone_block");
            context.waitFor(c->state().isPaused()&&state().snapshot()!=null,200);
            var first=context.computeOnClient(c->state().snapshot());
            var hold=new AtomicBoolean(true);
            var events=new ArrayList<String>();
            var deferred=new ArrayList<Runnable>();
            var next=new AtomicReference<PauseSnapshot>();
            var reply=new AtomicReference<ControlRejectedPayload>();
            var oldStep=context.computeOnClient(c->(ClientPlayNetworking.PlayPayloadHandler<StepSyncPayload>)ClientPlayNetworking.unregisterReceiver(StepSyncPayload.TYPE.id()));
            var oldPause=context.computeOnClient(c->(ClientPlayNetworking.PlayPayloadHandler<PauseSyncPayload>)ClientPlayNetworking.unregisterReceiver(PauseSyncPayload.TYPE.id()));
            var oldReject=context.computeOnClient(c->(ClientPlayNetworking.PlayPayloadHandler<ControlRejectedPayload>)ClientPlayNetworking.unregisterReceiver(ControlRejectedPayload.TYPE.id()));
            try {
                context.runOnClient(c->{
                    require(oldStep!=null&&oldPause!=null&&oldReject!=null,"production receivers exist");
                    require(ClientPlayNetworking.registerReceiver(StepSyncPayload.TYPE,(p,n)->{
                        events.add("wire StepSync");
                        if(hold.get())deferred.add(()->oldStep.receive(p,n));else oldStep.receive(p,n);
                    }),"observe StepSync");
                    require(ClientPlayNetworking.registerReceiver(PauseSyncPayload.TYPE,(p,n)->{
                        events.add("wire PauseSync("+p.snapshot().pauseId()+")");
                        if(hold.get()){deferred.add(()->oldPause.receive(p,n));next.set(p.snapshot());}else oldPause.receive(p,n);
                    }),"observe PauseSync");
                    require(ClientPlayNetworking.registerReceiver(ControlRejectedPayload.TYPE,(p,n)->{
                        events.add("wire Rejected("+p.pauseId()+","+p.requestId()+","+p.reason()+")");
                        oldReject.receive(p,n);reply.set(p);
                    }),"observe rejection");
                });
                require(DebuggerTaskQueue.execute(server,()->server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack(),"codon stepinto "+first.pauseId())),"admit independent authorized server control");
                context.waitFor(c->next.get()!=null&&next.get().pauseId()!=first.pauseId(),200);
                context.runOnClient(c->{
                    require(state().snapshot()==first,"client still sees unmodified earlier server snapshot while delivery delayed");
                    CodonClientMod.input().control(InputManager.Control.RESUME);
                    require(state().controlPending(),"real UI request is pending");
                    events.add("client send Resume("+first.pauseId()+","+state().controlRequestId()+")");
                    hold.set(false);
                    for(var delivery:List.copyOf(deferred))delivery.run();
                    deferred.clear();
                });
                context.waitFor(c->reply.get()!=null&&state().snapshot()!=null&&state().snapshot().pauseId()==next.get().pauseId(),200);
                context.waitTicks(2);
                context.runOnClient(c->{
                    require(reply.get().reason()==ControlRejectedPayload.Reason.STALE_PAUSE,"real command returns STALE_PAUSE");
                    require(reply.get().pauseId()==first.pauseId()&&reply.get().requestId()==state().controlRequestId(),"reply matches sent request");
                    require(!state().controlPending() && state().feedback().current() != null
                        && state().feedback().current().messageKey().equals("command.codon.error.stale_pause"),
                        "matching rejection is displayed after ordered sync");
                    expectedNotice = state().feedback().current();
                    expectedToastVisible = false;
                    events.add("final snapshot="+state().snapshot().pauseId()+", pending=false, feedback=stale_pause");
                    CodonMod.LOGGER.info("ORDERING REGRESSION PASSED {}",events);
                });
                context.waitTicks(20);
                context.runOnClient(c -> require(expectedToastVisible,
                    "the matching rejection toast must render fully within the viewport and scissor"));
                context.takeScreenshot("codon-ordered-stale-rejection");
            } finally {
                context.runOnClient(c->{
                    expectedNotice = null;
                    expectedToastVisible = false;
                    ClientPlayNetworking.unregisterReceiver(StepSyncPayload.TYPE.id());
                    ClientPlayNetworking.unregisterReceiver(PauseSyncPayload.TYPE.id());
                    ClientPlayNetworking.unregisterReceiver(ControlRejectedPayload.TYPE.id());
                    ClientPlayNetworking.registerReceiver(StepSyncPayload.TYPE,oldStep);
                    ClientPlayNetworking.registerReceiver(PauseSyncPayload.TYPE,oldPause);
                    ClientPlayNetworking.registerReceiver(ControlRejectedPayload.TYPE,oldReject);
                });
                var done=new AtomicBoolean();
                DebuggerTaskQueue.execute(server,()->{CodonMod.engine().clearBreakpoints();CodonMod.engine().resetSession();done.set(true);});
                context.waitFor(c->done.get(),200);
                context.runOnClient(c->{state().reset();CodonClientMod.freecam().synchronize(c);c.setScreenAndShow(null);});
            }
        }
    }
}
