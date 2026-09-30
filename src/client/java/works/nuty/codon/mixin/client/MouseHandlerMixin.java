package works.nuty.codon.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.client.CodonClientMod;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onButton", at = @At("HEAD"))
    private void codon$hideUi(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        var input = CodonClientMod.input();
        if (input != null && window == client.getWindow().handle())
            input.handleHideMouse(new MouseButtonEvent(0, 0, button), action);
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
