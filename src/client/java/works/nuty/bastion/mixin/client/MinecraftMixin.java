package works.nuty.bastion.mixin.client;

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
import works.nuty.bastion.client.BastionClientMod;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @ModifyExpressionValue(method = {"tick", "isPaused"}, at = @At(value = "FIELD",
        target = "Lnet/minecraft/client/Minecraft;pause:Z"))
    private boolean bastion$pauseWorldSimulation(boolean vanillaPaused) {
        // Reuse all vanilla pause guards: entities, block entities, weather, particles, HUD,
        // game-renderer timers, and delayed/ticking sounds. The outer client loop stays live.
        return vanillaPaused || BastionClientMod.isWorldPaused();
    }

    @Inject(method = {"tick", "renderFrame"}, at = @At("HEAD"))
    private void bastion$synchronizePauseEffects(CallbackInfo ci) {
        var effects = BastionClientMod.pauseEffects();
        if (effects != null) effects.synchronize((Minecraft) (Object) this);
    }

    @WrapWithCondition(method = "runTick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/texture/TextureManager;tick()V"))
    private boolean bastion$pauseAnimatedTextures(TextureManager textures) {
        return !BastionClientMod.isWorldPaused();
    }

    @WrapWithCondition(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/sounds/MusicManager;tick()V"))
    private boolean bastion$pauseMusicTimers(MusicManager music) {
        return !BastionClientMod.isWorldPaused();
    }

    @Inject(method = "handleKeybinds", at = @At("HEAD"), cancellable = true)
    private void bastion$cameraInputOnly(CallbackInfo ci) {
        var freecam = BastionClientMod.freecam();
        if (freecam != null && freecam.isActive()) {
            freecam.handlePausedKeybinds((Minecraft) (Object) this);
            ci.cancel();
        }
    }

    @Inject(method = {"startUseItem", "continueAttack", "pickBlockOrEntity"}, at = @At("HEAD"), cancellable = true)
    private void bastion$blockGameplayAction(CallbackInfo ci) {
        var freecam = BastionClientMod.freecam();
        if (freecam != null && freecam.isActive()) ci.cancel();
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void bastion$blockAttack(CallbackInfoReturnable<Boolean> cir) {
        var freecam = BastionClientMod.freecam();
        if (freecam != null && freecam.isActive()) cir.setReturnValue(false);
    }
}
