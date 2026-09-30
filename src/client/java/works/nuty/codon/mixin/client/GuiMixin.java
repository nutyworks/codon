package works.nuty.codon.mixin.client;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.client.CodonClientMod;

@Mixin(Gui.class)
abstract class GuiMixin {
    @Inject(method = "setScreen", at = @At("HEAD"))
    private void codon$resetUiVisibility(Screen screen, CallbackInfo ci) {
        var input = CodonClientMod.input();
        if (input != null) input.resetUiVisibility();
    }
}
