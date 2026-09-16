package works.nuty.bastion.mixin.client;

import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import works.nuty.bastion.client.BastionClientMod;

@Mixin(DeltaTracker.Timer.class)
public abstract class DeltaTrackerMixin {
    @ModifyVariable(method = "updatePauseState", at = @At("HEAD"), argsOnly = true)
    private boolean bastion$freezeWorldInterpolation(boolean vanillaPaused) {
        return vanillaPaused || BastionClientMod.isWorldPaused();
    }
}
