package works.nuty.codon.client.testmixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.client.WorldMarkerRenderProbe;

@Mixin(LevelRenderer.class)
abstract class WorldMarkerFrameMixin {
    @Inject(method = "finalizeGizmoCollection", at = @At("HEAD"))
    private void codon$beginMarkerFrame(CallbackInfo ci) { WorldMarkerRenderProbe.beginFrame(); }

    @Inject(method = "finalizeGizmoCollection", at = @At("RETURN"))
    private void codon$finishMarkerFrame(CallbackInfo ci) { WorldMarkerRenderProbe.endFrame(); }
}
