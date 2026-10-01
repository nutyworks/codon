package works.nuty.codon.client.testmixin;

import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.client.WorldMarkerRenderProbe;

@Mixin(DrawableGizmoPrimitives.class)
abstract class WorldMarkerPrimitivesMixin {
    @Inject(method = "addLine", at = @At("HEAD"))
    private void codon$observeMarkerLine(Vec3 start, Vec3 end, int color, float width, CallbackInfo ci) {
        WorldMarkerRenderProbe.primitive(color);
    }

    @Inject(method = "addPoint", at = @At("HEAD"))
    private void codon$observeMarkerPoint(Vec3 point, int color, float width, CallbackInfo ci) {
        WorldMarkerRenderProbe.primitive(color);
    }
}
