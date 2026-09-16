package works.nuty.bastion.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import works.nuty.bastion.adapter.DebuggerTaskQueue;

/** Keeps connection-disconnect cleanup out of the parked server's general task queue. */
@Mixin(ServerCommonPacketListenerImpl.class)
abstract class ServerCommonPacketListenerMixin {
    @WrapOperation(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;executeBlocking(Ljava/lang/Runnable;)V"))
    private void bastion$routeDisconnect(MinecraftServer server, Runnable cleanup, Operation<Void> original) {
        if (server.isSameThread()) {
            original.call(server, cleanup);
        } else {
            DebuggerTaskQueue.executeBlocking(server, cleanup);
        }
    }
}
