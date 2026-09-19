package works.nuty.codon.mixin;

import com.mojang.datafixers.util.Pair;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundProjectilePowerPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.InterpolationTracker;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import works.nuty.codon.adapter.PausedStateSynchronizer;

import java.util.ArrayList;
import java.util.List;

@Mixin(ServerEntity.class)
abstract class ServerEntityMixin implements PausedStateSynchronizer {
    @Shadow @Final private Entity entity;
    @Shadow @Final private ServerEntity.Synchronizer synchronizer;
    @Shadow @Final private InterpolationTracker interpolationTracker;
    @Shadow @Final private VecDeltaCodec positionCodec;
    @Shadow private byte lastSentYRot;
    @Shadow private byte lastSentXRot;
    @Shadow private byte lastSentYHeadRot;
    @Shadow private Vec3 lastSentMovement;
    @Shadow private List<Entity> lastPassengers;
    @Shadow private boolean wasRiding;
    @Shadow private boolean wasOnGround;
    @Shadow protected abstract void sendDirtyEntityData();

    @Override
    public void codon$syncPausedState() {
        // sendChanges() is not an outbound-only method: it advances tracker counters and runs
        // item-frame map updates. Use its serialization paths without that ticking work.
        entity.updateDataBeforeSync();
        sendDirtyEntityData();
        synchronizer.sendToTrackingPlayers(ClientboundEntityPositionSyncPacket.of(entity));
        positionCodec.setBase(entity.trackingPosition());
        interpolationTracker.clear();
        if (entity instanceof AbstractMinecart minecart && minecart.getBehavior() instanceof NewMinecartBehavior behavior) {
            behavior.lerpSteps.clear();
        }
        lastSentYRot = Mth.packDegrees(entity.getYRot());
        lastSentXRot = Mth.packDegrees(entity.getXRot());
        lastSentYHeadRot = Mth.packDegrees(entity.getYHeadRot());
        synchronizer.sendToTrackingPlayers(new ClientboundRotateHeadPacket(entity, lastSentYHeadRot));
        lastSentMovement = entity.getDeltaMovement();
        synchronizer.sendToTrackingPlayersAndSelf(new ClientboundSetEntityMotionPacket(entity));
        if (entity instanceof AbstractHurtingProjectile projectile) {
            synchronizer.sendToTrackingPlayers(new ClientboundProjectilePowerPacket(entity.getId(), projectile.accelerationPower));
        }
        wasRiding = entity.isPassenger();
        wasOnGround = entity.onGround();
        if (!entity.getPassengers().equals(lastPassengers)) {
            synchronizer.sendToTrackingPlayers(new ClientboundSetPassengersPacket(entity));
            lastPassengers = List.copyOf(entity.getPassengers());
        }
        if (entity instanceof Leashable leashable) {
            synchronizer.sendToTrackingPlayers(new ClientboundSetEntityLinkPacket(entity, leashable.getLeashHolder()));
        }
        if (entity instanceof LivingEntity living) {
            List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();
            for (EquipmentSlot slot : EquipmentSlot.VALUES) {
                equipment.add(Pair.of(slot, living.getItemBySlot(slot).copy()));
            }
            // Do not invoke collectEquipmentChanges: it also applies enchantment/location effects.
            // Include empty slots so clearing equipment is visible at the same pause.
            synchronizer.sendToTrackingPlayers(new ClientboundSetEquipmentPacket(entity.getId(), equipment));
        }
    }
}
