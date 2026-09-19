package works.nuty.codon.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.client.CodonClientMod;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
    // Vanilla suppresses position updates for detached cameras. Keep the real body's
    // gravity/velocity authoritative while freecam owns only navigation input.
    @ModifyExpressionValue(method = "sendPosition", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/player/LocalPlayer;isControlledCamera()Z"))
    private boolean codon$sendBodyPosition(boolean original) {
        var freecam = CodonClientMod.freecam();
        return original || (freecam != null && freecam.isActive());
    }

    @Inject(method = {"tick", "rideTick"}, at = @At("HEAD"), cancellable = true)
    private void codon$freezePlayer(CallbackInfo ci) {
        var freecam = CodonClientMod.freecam();
        if (freecam != null && freecam.freezes((LocalPlayer) (Object) this)) ci.cancel();
    }
}
