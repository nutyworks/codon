package works.nuty.codon.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.client.CodonClientMod;

/** Network state continues to arrive while simulation/interpolation ticks are suspended. */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    @Shadow private ClientLevel level;

    @Inject(method = "handleEntityPositionSync", at = @At("TAIL"))
    private void codon$applyPausedPosition(ClientboundEntityPositionSyncPacket packet, CallbackInfo ci) {
        if (!CodonClientMod.isWorldPaused()) return;
        Entity entity = level.getEntity(packet.id());
        if (entity == null) return;
        // Also cover locally controlled vehicles: at a debugger stop the server is authoritative.
        entity.getInterpolation().cancel();
        entity.snapTo(packet.position().endPosition(), packet.yRot(), packet.xRot());
        codon$clearMinecartInterpolation(entity);
    }

    @Inject(method = "handleTeleportEntity", at = @At("TAIL"))
    private void codon$finishPausedTeleport(ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
        codon$finishInterpolation(level.getEntity(packet.id()));
    }

    @Inject(method = "handleMoveEntity", at = @At("TAIL"))
    private void codon$finishPausedMovement(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
        codon$finishInterpolation(packet.getEntity(level));
    }

    @Inject(method = "handleRotateMob", at = @At("TAIL"))
    private void codon$applyPausedHead(ClientboundRotateHeadPacket packet, CallbackInfo ci) {
        if (!CodonClientMod.isWorldPaused()) return;
        Entity entity = packet.getEntity(level);
        if (entity == null) return;
        entity.lerpHeadTo(packet.getYHeadRot(), 0);
        entity.setYHeadRot(packet.getYHeadRot());
        if (entity instanceof LivingEntity living) living.yHeadRotO = living.getYHeadRot();
    }

    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void codon$applyPausedPlayerPosition(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        codon$finishInterpolation(net.minecraft.client.Minecraft.getInstance().player);
    }

    @Inject(method = "handleRotatePlayer", at = @At("TAIL"))
    private void codon$applyPausedPlayerRotation(ClientboundPlayerRotationPacket packet, CallbackInfo ci) {
        codon$finishInterpolation(net.minecraft.client.Minecraft.getInstance().player);
    }

    @Unique
    private static void codon$finishInterpolation(Entity entity) {
        if (entity == null || !CodonClientMod.isWorldPaused()) return;
        var target = entity.getInterpolation().target();
        entity.getInterpolation().cancel();
        if (target != null) entity.snapTo(target.position(), target.yRot(), target.xRot());
        entity.setOldPosAndRot();
        codon$clearMinecartInterpolation(entity);
    }

    @Unique
    private static void codon$clearMinecartInterpolation(Entity entity) {
        if (entity instanceof AbstractMinecart minecart && minecart.getBehavior() instanceof NewMinecartBehavior behavior) {
            // Experimental minecarts keep a separate render path, outside Entity.interpolation.
            behavior.lerpSteps.clear();
            behavior.currentLerpSteps.clear();
            behavior.currentLerpStepsTotalWeight = 0;
            behavior.setOldLerpValues();
        }
    }
}
