package works.nuty.bastion.mixin.client;

import com.mojang.blaze3d.audio.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.bastion.client.BastionClientMod;

@Mixin(Channel.class)
public abstract class ChannelMixin {
    @Shadow public abstract void pause();

    @Inject(method = "play", at = @At("TAIL"))
    private void bastion$pauseNewChannel(CallbackInfo ci) {
        // Buffers and streams can finish loading after the client has entered pause.
        if (BastionClientMod.isAudioPaused()) pause();
    }

    @Inject(method = "unpause", at = @At("HEAD"), cancellable = true)
    private void bastion$keepChannelPaused(CallbackInfo ci) {
        // Closing a screen invokes SoundManager.resume even while the debugger is paused.
        if (BastionClientMod.isAudioPaused()) ci.cancel();
    }
}
