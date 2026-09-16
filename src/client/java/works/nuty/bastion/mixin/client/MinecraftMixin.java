package works.nuty.bastion.mixin.client;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.nuty.bastion.client.BastionClientMod;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "handleKeybinds", at = @At("HEAD"), cancellable = true)
    private void bastion$cameraInputOnly(CallbackInfo ci) {
        var freecam = BastionClientMod.freecam();
        if (freecam != null && freecam.isActive()) {
            freecam.handlePausedKeybinds((Minecraft) (Object) this);
            ci.cancel();
        }
    }

    @Inject(method = {"startUseItem", "continueAttack", "pickBlockOrEntity"}, at = @At("HEAD"), cancellable = true)
    private void bastion$blockGameplayAction(CallbackInfo ci) {
        var freecam = BastionClientMod.freecam();
        if (freecam != null && freecam.isActive()) ci.cancel();
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void bastion$blockAttack(CallbackInfoReturnable<Boolean> cir) {
        var freecam = BastionClientMod.freecam();
        if (freecam != null && freecam.isActive()) cir.setReturnValue(false);
    }
}
