package works.nuty.codon.client.testmixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.client.DebuggerRequestFeedbackGameTest;

/** Observe actual toast draw submissions; queued notices never reach this hook. */
@Mixin(SystemToast.class)
abstract class FeedbackToastCaptureMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void codon$observeToast(GuiGraphicsExtractor graphics, Font font, long visibleFor, CallbackInfo ci) {
        DebuggerRequestFeedbackGameTest.observeToastDraw((SystemToast) (Object) this);
    }
}
