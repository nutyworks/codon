package works.nuty.codon.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.components.toasts.NowPlayingToast;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import works.nuty.codon.client.CodonClientMod;

@Mixin(NowPlayingToast.class)
public abstract class NowPlayingToastMixin {
    @ModifyExpressionValue(method = "tickMusicNotes", at = @At(value = "INVOKE",
        target = "Ljava/lang/System;currentTimeMillis()J"))
    private static long codon$pauseMusicNoteColors(long wallTimeMillis) {
        // This timestamp is used only for a local animation interval, so give it the same
        // monotonic presentation clock as toast lifetime instead of an independently aging clock.
        return CodonClientMod.effectTimeMillis(Util.getMillis());
    }
}
