package works.nuty.bastion.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.bastion.client.BastionClientMod;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void bastion$freezePlayerAndVehicle(Entity entity, CallbackInfo ci) {
        var freecam = BastionClientMod.freecam();
        if (freecam != null && freecam.freezes(entity)) ci.cancel();
    }
}
