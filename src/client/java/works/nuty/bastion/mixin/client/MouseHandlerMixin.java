package works.nuty.bastion.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.bastion.client.BastionClientMod;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void bastion$turnCamera(LocalPlayer player, double horizontal, double vertical, Operation<Void> original) {
        var freecam = BastionClientMod.freecam();
        if (freecam != null && freecam.isActive()) freecam.turn(horizontal, vertical);
        else original.call(player, horizontal, vertical);
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void bastion$blockHotbarScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        var freecam = BastionClientMod.freecam();
        if (freecam != null && freecam.isActive() && Minecraft.getInstance().gui.screen() == null) ci.cancel();
    }
}
