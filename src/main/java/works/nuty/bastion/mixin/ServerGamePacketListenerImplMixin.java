package works.nuty.bastion.mixin;

import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.bastion.BastionMod;
import works.nuty.bastion.core.service.DebuggerEngine;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
    private void bastion$forceExecuteCommand(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        DebuggerEngine engine = BastionMod.engine();
        if (engine != null && engine.isPaused()) {
            this.player.server.getCommands().performPrefixedCommand(
                this.player.createCommandSourceStack(),
                packet.command()
            );

            ci.cancel();
        }
    }
}
