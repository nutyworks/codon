package works.nuty.codon.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.client.CodonClientMod;
import works.nuty.codon.client.ui.FunctionSourceScreen;

@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
    @Shadow private boolean usedDebugKeyAsModifier;
    // Source consumes its F3 press, so vanilla never sees that gesture begin.
    @Unique private boolean codon$sourceOwnsF3;

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void codon$debuggerInput(long window, int action, KeyEvent event, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        var input = CodonClientMod.input();
        if (window == client.getWindow().handle()) {
            codon$trackSourceF3(client, action, event);
            if (input != null) {
                input.handleHideKey(event, action);
                if (input.handleWorldControlKey(event, action)) ci.cancel();
            }
        }
    }

    /**
     * Once Source has closed, the release of an F3 it consumed reaches vanilla's shared modifier/overlay
     * release, which toggles the overlay unless the key was used as a modifier. Mark it used instead of
     * cancelling, so vanilla still clears the modifier mapping. Repeats and an open Source are untouched.
     */
    @Unique
    private void codon$trackSourceF3(Minecraft client, int action, KeyEvent event) {
        if (event.key() != InputConstants.KEY_F3) return;
        boolean inSource = client.gui.screen() instanceof FunctionSourceScreen;
        if (action == InputConstants.PRESS) {
            codon$sourceOwnsF3 = inSource;
        } else if (action == InputConstants.RELEASE && codon$sourceOwnsF3) {
            codon$sourceOwnsF3 = false;
            var options = client.options;
            if (!inSource && options.keyDebugModifier.matches(event) && options.keyDebugOverlay.matches(event))
                usedDebugKeyAsModifier = true;
        }
    }
}
