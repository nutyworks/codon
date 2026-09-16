package works.nuty.bastion.adapter;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import works.nuty.bastion.mixin.ServerCommonPacketListenerAccessor;
import works.nuty.bastion.core.port.ExecutionController;

import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Minecraft adapter for {@link ExecutionController}. Suspends the server thread with
 * a debugger-only mailbox. It deliberately avoids {@code MinecraftServer.managedBlock}, which
 * can run unrelated server work while a command is paused in the middle of a tick.
 */
public final class McExecutionController implements ExecutionController {
    private static final long PARK_NANOS = 5_000_000L;
    private static final long KEEP_ALIVE_INTERVAL_NANOS = 1_000_000_000L;
    private static volatile boolean parked;
    private final Supplier<MinecraftServer> server;

    public static boolean isParked() {
        return parked;
    }

    public McExecutionController(Supplier<MinecraftServer> server) {
        this.server = server;
    }

    @Override
    public ParkResult parkUntil(BooleanSupplier resumed) {
        MinecraftServer s = server.get();
        if (s == null) {
            return ParkResult.CANCELLED;
        }
        parked = true;
        try {
            long nextKeepAliveNanos = 0L;
            while (s.isRunning() && !resumed.getAsBoolean()) {
                DebuggerTaskQueue.drain(s);
                if (!s.isRunning() || resumed.getAsBoolean()) {
                    break;
                }
                long now = System.nanoTime();
                if (now >= nextKeepAliveNanos) {
                    keepConnectionsAlive(s);
                    nextKeepAliveNanos = now + KEEP_ALIVE_INTERVAL_NANOS;
                }
                LockSupport.parkNanos(PARK_NANOS);
            }

            return s.isRunning() ? ParkResult.RESUMED : ParkResult.CANCELLED;
        } finally {
            // Keep the watchdog exemption through the schedule reset, even though resume clears
            // the core paused flag before this method returns.
            if (s.isRunning() && s instanceof ServerTickScheduleController schedule) {
                schedule.bastion$resetTickSchedule();
            }
            parked = false;
        }
    }

    private static void keepConnectionsAlive(MinecraftServer server) {
        for (ServerPlayer player : java.util.List.copyOf(server.getPlayerList().getPlayers())) {
            if (player.connection instanceof ServerCommonPacketListenerAccessor listener) {
                if (!listener.bastion$connection().isConnected()) {
                    listener.bastion$connection().handleDisconnection();
                    continue;
                }
                listener.bastion$keepConnectionAlive();
                listener.bastion$connection().flushChannel();
            }
        }
    }
}
