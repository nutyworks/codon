package works.nuty.codon.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.nuty.codon.client.CodonClientMod;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @ModifyVariable(method = "calculateFov", at = @At("HEAD"), argsOnly = true)
    private float codon$freezePausedFov(float partialTick) {
        var freecam = CodonClientMod.freecam();
        // Camera.tick (including tickFov) stops with the world. Using the freecam's
        // live fraction here would replay the last sprint/flight FOV transition each tick.
        if (CodonClientMod.isWorldPaused() && freecam != null && freecam.isActive()) {
            return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
        }
        return partialTick;
    }

    @Inject(method = "getCameraEntityPartialTicks", at = @At("HEAD"), cancellable = true)
    private void codon$keepFreecamSmooth(DeltaTracker tracker, CallbackInfoReturnable<Float> cir) {
        var freecam = CodonClientMod.freecam();
        if (freecam != null && freecam.isActive() && tracker instanceof DeltaTrackerAccessor timer) {
            cir.setReturnValue(timer.codon$unpausedPartialTick());
        }
    }
}
