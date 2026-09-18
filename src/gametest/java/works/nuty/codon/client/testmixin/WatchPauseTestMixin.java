package works.nuty.codon.client.testmixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl;
import org.spongepowered.asm.mixin.Mixin;
import works.nuty.codon.adapter.McExecutionController;
import works.nuty.codon.core.port.ExecutionController;

import java.util.function.BooleanSupplier;

/**
 * Test harness only: Fabric normally requires a server tick for every rendered client tick.
 * A real debugger pause intentionally stops that server tick. Temporarily withdraw its phaser
 * participant while the unmodified production mailbox parks, so UI/network tests can progress.
 */
@Mixin(value = McExecutionController.class, remap = false)
abstract class WatchPauseTestMixin {
    @WrapMethod(method = "parkUntil")
    private ExecutionController.ParkResult codon$allowClientTicksWhileParked(BooleanSupplier resumed,
        Operation<ExecutionController.ParkResult> original) {
        // Fabric otherwise queues one server semaphore permit on every client tick while parked.
        // Those stale permits can replay test handoffs on the wrong thread after resuming.
        ThreadingImpl.serverCanAcceptTasks = false;
        ThreadingImpl.PHASER.arriveAndDeregister();
        try {
            return original.call(resumed);
        } finally {
            int phase = ThreadingImpl.PHASER.register();
            // Rejoin in the tick phase, never halfway through the test thread's task handoffs.
            if ((phase & 1) == ThreadingImpl.PHASE_TICK) {
                ThreadingImpl.PHASER.arriveAndAwaitAdvance();
            }
            ThreadingImpl.serverCanAcceptTasks = true;
        }
    }
}
