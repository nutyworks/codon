package works.nuty.codon.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.nuty.codon.client.CodonClientMod;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "getCameraEntityPartialTicks", at = @At("HEAD"), cancellable = true)
    private void codon$keepFreecamSmooth(DeltaTracker tracker, CallbackInfoReturnable<Float> cir) {
        var freecam = CodonClientMod.freecam();
        if (freecam != null && freecam.isActive() && tracker instanceof DeltaTrackerAccessor timer) {
            cir.setReturnValue(timer.codon$unpausedPartialTick());
        }
    }
}
