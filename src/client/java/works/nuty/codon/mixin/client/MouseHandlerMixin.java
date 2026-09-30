package works.nuty.codon.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.client.CodonClientMod;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @ModifyExpressionValue(method = {"onButton", "onScroll", "handleAccumulatedMovement"}, at = {
        @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;getScaledXPos(Lcom/mojang/blaze3d/platform/Window;)D"),
        @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;getScaledYPos(Lcom/mojang/blaze3d/platform/Window;)D")
    })
    private double codon$pointerCoordinate(double coordinate) {
        return works.nuty.codon.client.ui.ScaledCodonScreen.inputCoordinate(coordinate);
    }

    @ModifyExpressionValue(method = "handleAccumulatedMovement", at = {
        @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;getScaledXPos(Lcom/mojang/blaze3d/platform/Window;D)D"),
        @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;getScaledYPos(Lcom/mojang/blaze3d/platform/Window;D)D")
    })
    private double codon$dragDelta(double amount) {
        return works.nuty.codon.client.ui.ScaledCodonScreen.inputCoordinate(amount);
    }

    @WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void codon$turnCamera(LocalPlayer player, double horizontal, double vertical, Operation<Void> original) {
        var freecam = CodonClientMod.freecam();
        if (freecam != null && freecam.isActive()) freecam.turn(horizontal, vertical);
        else original.call(player, horizontal, vertical);
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void codon$blockHotbarScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        var freecam = CodonClientMod.freecam();
        if (freecam != null && freecam.isActive() && Minecraft.getInstance().gui.screen() == null) ci.cancel();
    }
}
