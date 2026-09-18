package works.nuty.codon.mixin.client;

import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.nuty.codon.client.CodonClientMod;
import works.nuty.codon.client.state.DebuggerSoundControl;

import java.util.Map;

/** Keeps OpenAL and the sound-engine scheduler aligned with debugger world pause. */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin implements DebuggerSoundControl {
    @Shadow private Map<SoundInstance, ChannelAccess.ChannelHandle> instanceToChannel;

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void codon$dropNewUiSoundsWhilePaused(SoundInstance instance,
                                                    CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        // Debugger controls still work, but silent UI feedback must not accumulate paused
        // handles and burst into sound when execution resumes.
        if (CodonClientMod.isAudioPaused() && instance.getSource() == SoundSource.UI) {
            cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
        }
    }

    @Override
    public void codon$resumeAfterDebuggerPause(boolean vanillaPaused) {
        instanceToChannel.forEach((instance, handle) -> {
            if (!vanillaPaused || instance.getSource() == SoundSource.MUSIC || instance.getSource() == SoundSource.UI) {
                handle.execute(Channel::unpause);
            }
        });
    }
}
