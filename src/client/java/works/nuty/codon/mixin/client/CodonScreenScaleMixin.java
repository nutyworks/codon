package works.nuty.codon.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import works.nuty.codon.client.ui.CodonGuiGraphics;
import works.nuty.codon.client.ui.ScaledCodonScreen;

/** Scale the entire Codon screen pass, including deferred tooltips and IME overlays. */
@Mixin(Screen.class)
abstract class CodonScreenScaleMixin {
    @WrapMethod(method = "init(II)V")
    private void codon$init(int width, int height, Operation<Void> original) {
        if ((Object) this instanceof ScaledCodonScreen screen
            && screen.uiPreferences().uiScaleMode() == works.nuty.codon.client.state.DebuggerPreferences.UiScaleMode.CUSTOM) {
            var scale = screen.uiScale();
            original.call(scale.width(), scale.height());
        } else original.call(width, height);
    }

    @WrapMethod(method = "resize")
    private void codon$resize(int width, int height, Operation<Void> original) {
        if ((Object) this instanceof ScaledCodonScreen screen
            && screen.uiPreferences().uiScaleMode() == works.nuty.codon.client.state.DebuggerPreferences.UiScaleMode.CUSTOM) {
            var scale = screen.uiScale();
            original.call(scale.width(), scale.height());
        } else original.call(width, height);
    }

    @WrapMethod(method = "extractRenderStateWithTooltipAndSubtitles")
    private void codon$extract(GuiGraphicsExtractor graphics, int x, int y, float delta, Operation<Void> original) {
        if ((Object) this instanceof ScaledCodonScreen screen && !(graphics instanceof CodonGuiGraphics)) {
            var scale = screen.uiScale();
            // Scale preferences may change while this screen is open. Resize only Codon widgets.
            if (screen.width != scale.width() || screen.height != scale.height()) screen.resize(scale.width(), scale.height());
            int localX = (int) Math.floor(scale.toLocal(x));
            int localY = (int) Math.floor(scale.toLocal(y));
            original.call(new CodonGuiGraphics(graphics, scale, localX, localY), localX, localY, delta);
        } else original.call(graphics, x, y, delta);
    }
}
