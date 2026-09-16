package works.nuty.bastion.client.testmixin;

import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.bastion.client.FreecamRenderProbe;

/** Observe actual first-person hand submissions without changing rendering. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Inject(method = "submitHandsWithItems", at = @At("HEAD"))
    private void bastion$observeHands(CallbackInfo ci) {
        FreecamRenderProbe.observeHands();
    }
}
