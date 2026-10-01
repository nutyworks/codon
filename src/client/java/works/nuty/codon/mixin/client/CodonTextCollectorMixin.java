package works.nuty.codon.mixin.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import works.nuty.codon.client.ui.CodonGuiGraphics;

/** Vanilla widgets may submit text through a collector instead of graphics.text. */
@Mixin(targets = "net.minecraft.client.gui.GuiGraphicsExtractor$RenderingTextCollector")
abstract class CodonTextCollectorMixin {
    @Shadow @Final private GuiGraphicsExtractor this$0;

    @ModifyArg(method = "accept(Lnet/minecraft/client/gui/TextAlignment;IILnet/minecraft/client/gui/ActiveTextCollector$Parameters;Lnet/minecraft/util/FormattedCharSequence;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/state/gui/GuiTextRenderState;<init>(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;Lorg/joml/Matrix3x2fc;IIIIZZLnet/minecraft/client/gui/navigation/ScreenRectangle;)V"), index = 2)
    private Matrix3x2fc codon$textPose(Matrix3x2fc pose) {
        return this$0 instanceof CodonGuiGraphics graphics ? graphics.textPose(pose) : pose;
    }
}
