package works.nuty.codon.client.testmixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.client.WorldMarkerRenderProbe;

@Mixin(GameRenderer.class)
abstract class WorldMarkerScreenshotMixin {
    @Inject(method = "render", at = @At("RETURN"))
    private void codon$captureCompletedMarkerFrame(CallbackInfo ci) { WorldMarkerRenderProbe.captureFrame(); }
}
