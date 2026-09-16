package works.nuty.bastion.mixin.client;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.bastion.client.BastionClientMod;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
    @Inject(method = {"tick", "rideTick"}, at = @At("HEAD"), cancellable = true)
    private void bastion$freezePlayer(CallbackInfo ci) {
        var freecam = BastionClientMod.freecam();
        if (freecam != null && freecam.freezes((LocalPlayer) (Object) this)) ci.cancel();
    }
}
