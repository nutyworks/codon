package works.nuty.codon.mixin.client;

import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import works.nuty.codon.client.CodonClientMod;

@Mixin(DeltaTracker.Timer.class)
public abstract class DeltaTrackerMixin {
    @ModifyVariable(method = "updatePauseState", at = @At("HEAD"), argsOnly = true)
    private boolean codon$freezeWorldInterpolation(boolean vanillaPaused) {
        return vanillaPaused || CodonClientMod.isWorldPaused();
    }
}
