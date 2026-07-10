package works.nuty.bastion.adapter;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import works.nuty.bastion.core.port.ExecutionController;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Minecraft adapter for {@link ExecutionController}. Suspends the server thread with
 * {@code MinecraftServer.managedBlock} while still ticking the network connection, so the
 * resume/step commands (and packets) that lift the pause can be received while parked.
 */
public final class McExecutionController implements ExecutionController {
    private final Supplier<MinecraftServer> server;

    public McExecutionController(Supplier<MinecraftServer> server) {
        this.server = server;
    }

    @Override
    public void parkUntil(BooleanSupplier resumed) {
        MinecraftServer s = server.get();
        if (s == null) {
            return;
        }
        s.managedBlock(() -> {
            // 26.2 first queues serverbound packets in MinecraftServer.packetProcessor(). A
            // paused server never reaches the tick stage that dispatches them, so ticking the
            // connection alone receives packets without ever handling them. Console lines
            // likewise sit in the dedicated server's own queue, drained only during a tick.
            // Pump all three here so players and the console can lift the pause while parked.
            s.getConnection().tick();
            s.packetProcessor().processQueuedPackets();
            if (s instanceof DedicatedServer dedicated) {
                dedicated.handleConsoleInputs();
            }
            return resumed.getAsBoolean() || !s.isRunning();
        });

        // The pause happened mid-tick while Minecraft's deadline kept advancing. Restart the
        // schedule from the current wall clock so the server neither logs a misleading
        // "Can't keep up" warning nor burst-runs hundreds of ticks to catch up.
        if (resumed.getAsBoolean() && s instanceof ServerTickScheduleController schedule) {
            schedule.bastion$resetTickSchedule();
        }
    }
}
