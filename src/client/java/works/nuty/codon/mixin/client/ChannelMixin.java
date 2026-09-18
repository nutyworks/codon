package works.nuty.codon.mixin.client;

import com.mojang.blaze3d.audio.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.client.CodonClientMod;

@Mixin(Channel.class)
public abstract class ChannelMixin {
    @Shadow public abstract void pause();

    @Inject(method = "play", at = @At("TAIL"))
    private void codon$pauseNewChannel(CallbackInfo ci) {
        // Buffers and streams can finish loading after the client has entered pause.
        if (CodonClientMod.isAudioPaused()) pause();
    }

    @Inject(method = "unpause", at = @At("HEAD"), cancellable = true)
    private void codon$keepChannelPaused(CallbackInfo ci) {
        // Closing a screen invokes SoundManager.resume even while the debugger is paused.
        if (CodonClientMod.isAudioPaused()) ci.cancel();
    }
}
