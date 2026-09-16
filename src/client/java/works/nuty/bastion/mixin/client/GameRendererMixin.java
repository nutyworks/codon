package works.nuty.bastion.mixin.client;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.bastion.client.BastionClientMod;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void bastion$hideFreecamHands(CallbackInfo ci) {
        // Vanilla draws LocalPlayer's hands even when a different entity owns the camera.
        // Only the paused body in the world should have arms/items while our view is detached.
        var freecam = BastionClientMod.freecam();
        if (freecam != null && freecam.isActive()) ci.cancel();
    }
}
