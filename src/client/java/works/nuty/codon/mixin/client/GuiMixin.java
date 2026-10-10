package works.nuty.codon.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.client.CodonClientMod;
import works.nuty.codon.client.ui.DebuggerFeedbackToast;

@Mixin(Gui.class)
abstract class GuiMixin {
    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/components/toasts/ToastManager;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V"))
    private void codon$renderFeedback(ToastManager manager, GuiGraphicsExtractor graphics, Operation<Void> original) {
        original.call(manager, graphics);
        DebuggerFeedbackToast.extractRenderState(graphics);
    }

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void codon$resetUiVisibility(Screen screen, CallbackInfo ci) {
        var input = CodonClientMod.input();
        if (input != null) input.resetUiVisibility();
    }
}
