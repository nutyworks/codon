package works.nuty.codon.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import works.nuty.codon.client.ui.CodonGuiGraphics;

@Mixin(GuiGraphicsExtractor.class)
abstract class CodonTextExtractionMixin {
    @WrapOperation(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
        at = @At(value = "NEW", target = "(Lorg/joml/Matrix3x2fc;)Lorg/joml/Matrix3x2f;"))
    private Matrix3x2f codon$textPose(Matrix3x2fc pose, Operation<Matrix3x2f> original) {
        return (Object) this instanceof CodonGuiGraphics graphics ? graphics.textPose(pose) : original.call(pose);
    }
}
