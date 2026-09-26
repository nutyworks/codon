package works.nuty.codon.client.testmixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps Fabric's client/server phases advancing while vanilla shutdown waits for the server. */
@Mixin(IntegratedServer.class)
abstract class IntegratedServerGameTestMixin {
    @WrapOperation(method = "halt", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/server/IntegratedServer;executeBlocking(Ljava/lang/Runnable;)V"))
    private void codon$waitForShutdownTask(IntegratedServer server, Runnable cleanup, Operation<Void> original) {
        var client = ThreadingImpl.unsafeClientInstance;
        if (client == null || !client.isSameThread() || ThreadingImpl.testThread == null) {
            original.call(server, cleanup);
            return;
        }

        // halt() waits before Minecraft's renderFrame shutdown loop, where Fabric normally pumps
        // these phases. A plain join here deadlocks if the server already awaits the next phase.
        var completion = server.submit(cleanup);
        while (!completion.isDone()) {
            ((MinecraftGameTestPhaseInvoker) client).codon$advanceGameTestPhase();
        }
        completion.join();
    }
}
