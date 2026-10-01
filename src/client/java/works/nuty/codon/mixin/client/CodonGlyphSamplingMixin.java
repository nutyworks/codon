package works.nuty.codon.mixin.client;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.font.TextRenderable;
import org.joml.Matrix4fc;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GlyphRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.nuty.codon.client.ui.CodonTextPose;
import works.nuty.codon.client.ui.CodonTextPipelines;
import works.nuty.codon.client.ui.CodonGlyphCoverage;

/** Filters only a Codon glyph draw; shared font textures retain vanilla state. */
@Mixin(GlyphRenderState.class)
abstract class CodonGlyphSamplingMixin {
    @WrapOperation(method = "buildVertices", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/font/TextRenderable;render(Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;IZ)V"))
    private void codon$coverageBounds(TextRenderable text, Matrix4fc matrix, VertexConsumer target, int light, boolean gui, Operation<Void> original) {
        var glyph = (GlyphRenderState) (Object) this;
        if (!codon$filtered(glyph)) { original.call(text, matrix, target, light, gui); return; }
        var coverage = new CodonGlyphCoverage(target, ((CodonTextPose) glyph.pose()).gameScale());
        original.call(text, matrix, coverage, light, gui);
        coverage.finish();
    }

    @Inject(method = "pipeline", at = @At("RETURN"), cancellable = true)
    private void codon$coverage(CallbackInfoReturnable<RenderPipeline> result) {
        var glyph = (GlyphRenderState) (Object) this;
        if (codon$filtered(glyph)) result.setReturnValue(result.getReturnValue() == RenderPipelines.GUI_TEXT_GRAYSCALE
            ? CodonTextPipelines.GRAYSCALE : CodonTextPipelines.COLOR);
    }

    @Unique private static boolean codon$filtered(GlyphRenderState glyph) {
        if (!(glyph.pose() instanceof CodonTextPose pose) || !pose.needsFiltering()) return false;
        var pipeline = glyph.renderable().guiPipeline();
        boolean grayscale = pipeline == RenderPipelines.GUI_TEXT_GRAYSCALE;
        return grayscale || pipeline == RenderPipelines.GUI_TEXT;
    }
}
