package works.nuty.codon.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.components.toasts.Toast;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import works.nuty.codon.client.CodonClientMod;
import works.nuty.codon.client.ui.DebuggerFeedbackToast;

@Mixin(targets = "net.minecraft.client.gui.components.toasts.ToastManager$ToastInstance")
public abstract class ToastInstanceClockMixin {
    @Shadow @Final private Toast toast;

    @ModifyExpressionValue(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;getMillis()J"))
    private long codon$notificationTime(long realTimeMillis) {
        // Request feedback must enter/leave the screen while the inspected world is paused.
        return toast instanceof DebuggerFeedbackToast ? realTimeMillis : CodonClientMod.effectTimeMillis(realTimeMillis);
    }
}
