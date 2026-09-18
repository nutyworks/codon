package works.nuty.codon.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.nuty.codon.client.CodonClientMod;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @ModifyExpressionValue(method = {"tick", "isPaused"}, at = @At(value = "FIELD",
        target = "Lnet/minecraft/client/Minecraft;pause:Z"))
    private boolean codon$pauseWorldSimulation(boolean vanillaPaused) {
        // Reuse all vanilla pause guards: entities, block entities, weather, particles, HUD,
        // game-renderer timers, and delayed/ticking sounds. The outer client loop stays live.
        return vanillaPaused || CodonClientMod.isWorldPaused();
    }

    @Inject(method = {"tick", "renderFrame"}, at = @At("HEAD"))
    private void codon$synchronizePauseEffects(CallbackInfo ci) {
        var effects = CodonClientMod.pauseEffects();
        if (effects != null) effects.synchronize((Minecraft) (Object) this);
    }

    @WrapWithCondition(method = "runTick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/texture/TextureManager;tick()V"))
    private boolean codon$pauseAnimatedTextures(TextureManager textures) {
        return !CodonClientMod.isWorldPaused();
    }

    @WrapWithCondition(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/sounds/MusicManager;tick()V"))
    private boolean codon$pauseMusicTimers(MusicManager music) {
        return !CodonClientMod.isWorldPaused();
    }

    @Inject(method = "handleKeybinds", at = @At("HEAD"), cancellable = true)
    private void codon$cameraInputOnly(CallbackInfo ci) {
        var freecam = CodonClientMod.freecam();
        if (freecam != null && freecam.isActive()) {
            freecam.handlePausedKeybinds((Minecraft) (Object) this);
            ci.cancel();
        }
    }

    @Inject(method = {"startUseItem", "continueAttack", "pickBlockOrEntity"}, at = @At("HEAD"), cancellable = true)
    private void codon$blockGameplayAction(CallbackInfo ci) {
        var freecam = CodonClientMod.freecam();
        if (freecam != null && freecam.isActive()) ci.cancel();
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void codon$blockAttack(CallbackInfoReturnable<Boolean> cir) {
        var freecam = CodonClientMod.freecam();
        if (freecam != null && freecam.isActive()) cir.setReturnValue(false);
    }
}
