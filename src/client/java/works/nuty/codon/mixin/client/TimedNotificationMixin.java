package works.nuty.codon.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import works.nuty.codon.client.CodonClientMod;

@Mixin(targets = {
    "net.minecraft.client.gui.components.SubtitleOverlay$Subtitle",
    "net.minecraft.client.gui.components.toasts.ToastManager$ToastInstance"
})
public abstract class TimedNotificationMixin {
    // Use the same clock when creating, refreshing, and expiring notifications; adjusting only
    // their render time would either resurrect old entries or make them expire on resume.
    @ModifyExpressionValue(method = {"*", "<init>"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/util/Util;getMillis()J"))
    private static long codon$pauseNotificationTime(long realTimeMillis) {
        return CodonClientMod.effectTimeMillis(realTimeMillis);
    }
}
