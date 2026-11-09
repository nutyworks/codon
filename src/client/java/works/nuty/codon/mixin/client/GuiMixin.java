package works.nuty.codon.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.client.CodonClientMod;
import works.nuty.codon.client.ui.DebuggerFeedbackToast;

@Mixin(Gui.class)
abstract class GuiMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void codon$renderFeedback(DeltaTracker tracker, boolean renderHud, boolean renderToasts,
                                      CallbackInfo ci, @Local GuiGraphicsExtractor graphics) {
        if (renderToasts) DebuggerFeedbackToast.extractRenderState(graphics);
    }

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void codon$resetUiVisibility(Screen screen, CallbackInfo ci) {
        var input = CodonClientMod.input();
        if (input != null) input.resetUiVisibility();
    }
}
