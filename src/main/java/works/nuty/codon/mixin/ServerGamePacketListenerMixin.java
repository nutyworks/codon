package works.nuty.codon.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundSetCommandBlockPacket;
import net.minecraft.world.level.BaseCommandBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.adapter.SourceMapper;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.network.BreakpointEditPayload;
import works.nuty.codon.network.BreakpointStagePreviewRequestPayload;
import works.nuty.codon.network.FunctionSourceListRequestPayload;
import works.nuty.codon.network.FunctionSourceReadRequestPayload;
import works.nuty.codon.network.NbtTreeQueryPayload;
import works.nuty.codon.network.WatchEditorQueryPayload;
import works.nuty.codon.network.WatchQueryPayload;
import works.nuty.codon.network.WatchSavePayload;
import works.nuty.codon.network.WatchSaveV2Payload;

/** Routes debugger commands and typed requests through the same parked-server mailbox. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerMixin {
    @WrapMethod(method = "handleCustomPayload")
    private void codon$routeRequest(ServerboundCustomPayloadPacket packet, Operation<Void> original) {
        var payload = packet.payload();
        if (payload instanceof WatchQueryPayload || payload instanceof WatchEditorQueryPayload
            || payload instanceof WatchSavePayload || payload instanceof WatchSaveV2Payload
            || payload instanceof NbtTreeQueryPayload
            || payload instanceof BreakpointEditPayload || payload instanceof BreakpointStagePreviewRequestPayload
            || payload instanceof FunctionSourceListRequestPayload
            || payload instanceof FunctionSourceReadRequestPayload) {
            var listener = (ServerGamePacketListenerImpl) (Object) this;
            // Wrap the entire handler, including Fabric's thread handoff: its ordinary packet
            // processor cannot run while parked. Immutable decoded requests join the control FIFO
            // before any subsequently received step command, even when the server is not paused.
            DebuggerTaskQueue.executeNetwork(((ServerCommonPacketListenerAccessor) listener).codon$server(), listener, false, () -> {
                if (listener.isAcceptingMessages() && listener.player.connection == listener) original.call(packet);
            });
        } else {
            original.call(packet);
        }
    }

    /** Keep vanilla's complete permission and edit path available while paused. */
    @WrapMethod(method = "handleSetCommandBlock")
    private void codon$commandBlockSaved(ServerboundSetCommandBlockPacket packet, Operation<Void> original) {
        var listener = (ServerGamePacketListenerImpl) (Object) this;
        DebuggerTaskQueue.executeNetwork(((ServerCommonPacketListenerAccessor) listener).codon$server(), listener, false, () -> {
            if (!listener.isAcceptingMessages() || listener.player.connection != listener) return;
            original.call(packet);
        });
    }

    /** Observe the actual authorized mutation; never add a world lookup around vanilla. */
    @WrapOperation(method = "handleSetCommandBlock", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/BaseCommandBlock;setCommand(Ljava/lang/String;)V"))
    private void codon$commandTextChanged(BaseCommandBlock commandBlock, String command, Operation<Void> original,
                                         @Local(argsOnly = true) ServerboundSetCommandBlockPacket packet) {
        String previousCommand = commandBlock.getCommand();
        original.call(commandBlock, command);
        var engine = CodonMod.engine();
        if (engine == null) return;
        var listener = (ServerGamePacketListenerImpl) (Object) this;
        var level = listener.player.level();
        var block = SourceMapper.toBlockLocation(packet.getPos(), level.dimension().identifier().toString());
        engine.disableStaleStages(new SourceLocation.Block(block), previousCommand, commandBlock.getCommand());
    }

    @WrapOperation(method = "tryHandleChat", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/MinecraftServer;execute(Ljava/lang/Runnable;)V"))
    private void codon$routeControlCommand(MinecraftServer server, Runnable task, Operation<Void> original,
                                             @Local(argsOnly = true, name = "message") String message,
                                             @Local(argsOnly = true, name = "isCommand") boolean isCommand) {
        if (isCommand && DebuggerTaskQueue.isControlCommand(message)) {
            var listener = (ServerGamePacketListenerImpl) (Object) this;
            DebuggerTaskQueue.executeNetwork(server, listener, true, () -> {
                if (listener.isAcceptingMessages() && listener.player.connection == listener) task.run();
            });
        } else {
            original.call(server, task);
        }
    }
}
