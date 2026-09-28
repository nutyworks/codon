package works.nuty.codon.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.client.ui.ScreenLayers;

/** Background widgets cannot hover; narration follows the active modal layer. */
@Mixin(Screen.class)
abstract class ScreenLayerMixin {
    @WrapOperation(method = "extractRenderStateWithTooltipAndSubtitles", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/Screen;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
    private void codon$suppressCoveredHover(Screen screen, GuiGraphicsExtractor graphics, int x, int y,
                                            float delta, Operation<Void> original) {
        boolean covered = ScreenLayers.get(screen) != null;
        original.call(screen, graphics, covered ? -1 : x, covered ? -1 : y, delta);
    }

    @Inject(method = "handleDelayedNarration", at = @At("HEAD"), cancellable = true)
    private void codon$narrateLayer(CallbackInfo ci) {
        Screen layer = ScreenLayers.get((Screen) (Object) this);
        if (layer == null) return;
        layer.handleDelayedNarration();
        ci.cancel();
    }

    @Inject(method = "triggerImmediateNarration", at = @At("HEAD"), cancellable = true)
    private void codon$narrateLayerImmediately(boolean onlyNew, CallbackInfo ci) {
        Screen layer = ScreenLayers.get((Screen) (Object) this);
        if (layer == null) return;
        layer.triggerImmediateNarration(onlyNew);
        ci.cancel();
    }
}
