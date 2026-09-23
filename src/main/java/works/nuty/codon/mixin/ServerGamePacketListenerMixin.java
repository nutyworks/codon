package works.nuty.codon.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundSetCommandBlockPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
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

/** Routes debugger commands and typed requests through the same parked-server mailbox. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerMixin {
    @WrapMethod(method = "handleCustomPayload")
    private void codon$routeRequest(ServerboundCustomPayloadPacket packet, Operation<Void> original) {
        var payload = packet.payload();
        if (payload instanceof WatchQueryPayload || payload instanceof WatchEditorQueryPayload
            || payload instanceof WatchSavePayload || payload instanceof NbtTreeQueryPayload
            || payload instanceof BreakpointEditPayload || payload instanceof BreakpointStagePreviewRequestPayload
            || payload instanceof FunctionSourceListRequestPayload
            || payload instanceof FunctionSourceReadRequestPayload) {
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

    /** Save a command block even while paused, then disable stages tied to its old text. */
    @WrapMethod(method = "handleSetCommandBlock")
    private void codon$commandBlockSaved(ServerboundSetCommandBlockPacket packet, Operation<Void> original) {
        var listener = (ServerGamePacketListenerImpl) (Object) this;
        DebuggerTaskQueue.execute(((ServerCommonPacketListenerAccessor) listener).codon$server(), () -> {
            if (!listener.isAcceptingMessages() || listener.player.connection != listener) return;
            ServerLevel level = listener.player.level();
            String previousCommand = level.getBlockEntity(packet.getPos()) instanceof CommandBlockEntity previous
                ? previous.getCommandBlock().getCommand() : "";
            original.call(packet);
            if (!(level.getBlockEntity(packet.getPos()) instanceof CommandBlockEntity entity)) return;
            var engine = CodonMod.engine();
            if (engine == null) return;
            var block = SourceMapper.toBlockLocation(packet.getPos(), level.dimension().identifier().toString());
            engine.disableStaleStages(new SourceLocation.Block(block), previousCommand,
                entity.getCommandBlock().getCommand());
        });
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
