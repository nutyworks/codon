package works.nuty.codon.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.network.NbtTreeQueryPayload;
import works.nuty.codon.network.WatchEditorQueryPayload;
import works.nuty.codon.network.WatchQueryPayload;
import works.nuty.codon.network.WatchSavePayload;

/** Routes debugger commands and typed requests through the same parked-server mailbox. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerMixin {
    @WrapMethod(method = "handleCustomPayload")
    private void codon$routeRequest(ServerboundCustomPayloadPacket packet, Operation<Void> original) {
        var payload = packet.payload();
        if (payload instanceof WatchQueryPayload || payload instanceof WatchEditorQueryPayload
            || payload instanceof WatchSavePayload || payload instanceof NbtTreeQueryPayload) {
            var listener = (ServerGamePacketListenerImpl) (Object) this;
            // Wrap the entire handler, including Fabric's thread handoff: its ordinary packet
            // processor cannot run while parked. Immutable decoded requests join the control FIFO
            // before any subsequently received step command, even when the server is not paused.
            DebuggerTaskQueue.execute(((ServerCommonPacketListenerAccessor) listener).codon$server(), () -> {
                if (listener.isAcceptingMessages() && listener.player.connection == listener) original.call(packet);
            });
        } else {
            original.call(packet);
        }
    }

    @WrapOperation(method = "tryHandleChat", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/MinecraftServer;execute(Ljava/lang/Runnable;)V"))
    private void codon$routeControlCommand(MinecraftServer server, Runnable task, Operation<Void> original,
                                             @Local(argsOnly = true, name = "message") String message,
                                             @Local(argsOnly = true, name = "isCommand") boolean isCommand) {
        if (isCommand && DebuggerTaskQueue.isControlCommand(message)) {
            DebuggerTaskQueue.execute(server, task);
        } else {
            original.call(server, task);
        }
    }
}
