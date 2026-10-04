package works.nuty.codon.adapter;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import works.nuty.codon.mixin.ServerCommonPacketListenerAccessor;
import works.nuty.codon.core.port.ExecutionController;

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
    private static final long CHUNK_SYNC_INTERVAL_NANOS = 50_000_000L;
    private static volatile boolean parked;
    private final Supplier<MinecraftServer> server;
    private final Runnable pausedMaintenance;

    public static boolean isParked() {
        return parked;
    }

    public McExecutionController(Supplier<MinecraftServer> server) {
        this(server, () -> { });
    }

    public McExecutionController(Supplier<MinecraftServer> server, Runnable pausedMaintenance) {
        this.server = server;
        this.pausedMaintenance = java.util.Objects.requireNonNull(pausedMaintenance);
    }

    @Override
    public ParkResult parkUntil(BooleanSupplier resumed) {
        MinecraftServer s = server.get();
        if (s == null) {
            return ParkResult.CANCELLED;
        }
        parked = true;
        try {
            PausedWorldState.synchronize(s);
            flushConnections(s);
            long nextKeepAliveNanos = 0L;
            long nextChunkSyncNanos = System.nanoTime() + CHUNK_SYNC_INTERVAL_NANOS;
            while (s.isRunning() && !resumed.getAsBoolean()) {
                if (DebuggerTaskQueue.drain(s) > 0) {
                    // Vanilla defers server-thread sends until the tick ends. This tick is parked:
                    // deliver command acknowledgements and Watch replies before waiting or resuming.
                    flushConnections(s);
                }
                if (!s.isRunning() || resumed.getAsBoolean()) {
                    break;
                }
                long now = System.nanoTime();
                if (now >= nextChunkSyncNanos) {
                    PausedWorldState.flushChunks(s);
                    flushConnections(s);
                    nextChunkSyncNanos = now + CHUNK_SYNC_INTERVAL_NANOS;
                }
                if (now >= nextKeepAliveNanos) {
                    pausedMaintenance.run();
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
                schedule.codon$resetTickSchedule();
            }
            parked = false;
        }
    }

    private static void keepConnectionsAlive(MinecraftServer server) {
        for (ServerPlayer player : java.util.List.copyOf(server.getPlayerList().getPlayers())) {
            if (player.connection instanceof ServerCommonPacketListenerAccessor listener) {
                if (!listener.codon$connection().isConnected()) {
                    listener.codon$connection().handleDisconnection();
                    continue;
                }
                listener.codon$keepConnectionAlive();
                listener.codon$connection().flushChannel();
            }
        }
    }

    /** Flush outgoing data only; do not tick connections or process unrelated inbound packets. */
    private static void flushConnections(MinecraftServer server) {
        for (ServerPlayer player : java.util.List.copyOf(server.getPlayerList().getPlayers())) {
            if (player.connection instanceof ServerCommonPacketListenerAccessor listener
                && listener.codon$connection().isConnected()) {
                listener.codon$connection().flushChannel();
            }
        }
    }
}
