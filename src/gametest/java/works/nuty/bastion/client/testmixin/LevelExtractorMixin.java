package works.nuty.bastion.client.testmixin;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.nuty.bastion.client.FreecamRenderProbe;

@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Inject(method = "isEntityVisible", at = @At("RETURN"))
    private void bastion$observeVisibility(Entity entity, Frustum frustum, double x, double y, double z,
                                          CallbackInfoReturnable<Boolean> cir) {
        Minecraft client = Minecraft.getInstance();
        if (entity == client.player) {
            FreecamRenderProbe.observeVisibility(entity, cir.getReturnValue(),
                client.levelRenderer.isSectionCompiledAndVisible(entity.blockPosition()),
                client.levelRenderer.entityRenderDispatcher().shouldRender(entity, frustum, x, y, z));
        }
    }

    @Inject(method = "extractEntity", at = @At("HEAD"))
    private void bastion$observePlayerPose(Entity entity, float partialTick,
                                           CallbackInfoReturnable<EntityRenderState> cir) {
        FreecamRenderProbe.observe(entity, partialTick);
    }
}
