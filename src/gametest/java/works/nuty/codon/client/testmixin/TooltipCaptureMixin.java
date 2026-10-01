package works.nuty.codon.client.testmixin;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.client.DebuggerTooltipGameTest;

/** Observes the actual deferred native tooltip; never changes its text or placement. */
@Mixin(GuiGraphicsExtractor.class)
abstract class TooltipCaptureMixin {
    @Inject(method = "tooltip", at = @At("HEAD"))
    private void codon$observeTooltip(Font font, List<ClientTooltipComponent> lines, int x, int y,
                                      ClientTooltipPositioner positioner, Identifier style, boolean showWithItem,
                                      CallbackInfo ci) {
        DebuggerTooltipGameTest.observe((GuiGraphicsExtractor) (Object) this, font, lines, x, y, positioner);
    }
}
