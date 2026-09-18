package works.nuty.codon.client.testmixin;

import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.client.ClientPauseProbe;

/** Observes texture-animation ticks without changing TextureManager behavior. */
@Mixin(TextureManager.class)
public abstract class TextureManagerMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void codon$observeTick(CallbackInfo ci) {
        ClientPauseProbe.observeTextureTick();
    }
}
