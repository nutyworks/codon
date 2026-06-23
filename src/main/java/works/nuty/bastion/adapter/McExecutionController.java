package works.nuty.bastion.adapter;

import net.minecraft.server.MinecraftServer;
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
            s.getConnection().tick();
            return resumed.getAsBoolean() || !s.isRunning();
        });
    }
}
