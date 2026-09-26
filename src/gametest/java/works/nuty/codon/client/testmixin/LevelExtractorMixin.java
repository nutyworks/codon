package works.nuty.codon.client.testmixin;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.nuty.codon.client.FreecamRenderProbe;

@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Inject(method = "isEntityVisible", at = @At("RETURN"))
    private void codon$observeVisibility(Entity entity, Frustum frustum, double x, double y, double z,
                                          float partialTick, long chunkFadeDuration, CallbackInfoReturnable<Boolean> cir) {
        Minecraft client = Minecraft.getInstance();
        if (entity == client.player) {
            FreecamRenderProbe.observeVisibility(entity, cir.getReturnValue(),
                client.levelRenderer.isSectionCompiledAndVisible(entity.blockPosition(), chunkFadeDuration),
                client.levelRenderer.entityRenderDispatcher().shouldRender(entity, frustum, x, y, z, partialTick));
        }
    }

    @WrapOperation(method = "extractVisibleEntities", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))
    private EntityRenderState codon$observePlayerPose(LevelExtractor extractor, Entity entity, float partialTick,
                                                     Operation<EntityRenderState> original) {
        EntityRenderState result = original.call(extractor, entity, partialTick);
        FreecamRenderProbe.observeBodySubmission(entity);
        return result;
    }
    @Inject(method = "extractEntity", at = @At("HEAD"))
    private void codon$observeExtractedPose(Entity entity, float partialTick,
                                           CallbackInfoReturnable<EntityRenderState> cir) {
        FreecamRenderProbe.observe(entity, partialTick);
    }
}
