package works.nuty.bastion.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import works.nuty.bastion.adapter.DebuggerTaskQueue;

/** Routes only validated chat commands for debugger control through the parked-server mailbox. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerMixin {
    @WrapOperation(method = "tryHandleChat", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/MinecraftServer;execute(Ljava/lang/Runnable;)V"))
    private void bastion$routeControlCommand(MinecraftServer server, Runnable task, Operation<Void> original,
                                             @Local(argsOnly = true, name = "message") String message,
                                             @Local(argsOnly = true, name = "isCommand") boolean isCommand) {
        if (isCommand && DebuggerTaskQueue.isControlCommand(message)) {
            DebuggerTaskQueue.execute(server, task);
        } else {
            original.call(server, task);
        }
    }
}
