package works.nuty.bastion.client.testmixin;

import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.bastion.client.ClientPauseProbe;

/** Observes texture-animation ticks without changing TextureManager behavior. */
@Mixin(TextureManager.class)
public abstract class TextureManagerMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void bastion$observeTick(CallbackInfo ci) {
        ClientPauseProbe.observeTextureTick();
    }
}
