package works.nuty.codon.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Camera;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import works.nuty.codon.client.CodonClientMod;

@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    // The fourth Camera.entity() call hides LocalPlayer when the camera belongs to another entity.
    // Let only our paused body pass that check, preserving vanilla visibility/culling and rendering.
    @WrapOperation(method = "extractVisibleEntities", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Camera;entity()Lnet/minecraft/world/entity/Entity;", ordinal = 3))
    private Entity codon$showPausedBody(Camera camera, Operation<Entity> original, @Local Entity entity) {
        var freecam = CodonClientMod.freecam();
        if (entity instanceof LocalPlayer && freecam != null && freecam.freezes(entity)) return entity;
        return original.call(camera);
    }

    @WrapOperation(method = "extractVisibleEntities", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))
    private EntityRenderState codon$freezeBodyPose(LevelExtractor extractor, Entity entity, float partialTick,
                                                    Operation<EntityRenderState> original) {
        var freecam = CodonClientMod.freecam();
        // A fixed interpolation value also freezes walking, head rotation, and mounted poses.
        return original.call(extractor, entity, freecam != null && freecam.freezes(entity) ? 1.0F : partialTick);
    }
}
