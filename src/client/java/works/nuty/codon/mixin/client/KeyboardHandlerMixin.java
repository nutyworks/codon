package works.nuty.codon.mixin.client;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.client.CodonClientMod;

@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void codon$debuggerInput(long window, int action, KeyEvent event, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        var input = CodonClientMod.input();
        if (input != null && window == client.getWindow().handle()) {
            input.handleHideKey(event, action);
            if (input.handleWorldControlKey(event, action)) ci.cancel();
        }
    }
}
