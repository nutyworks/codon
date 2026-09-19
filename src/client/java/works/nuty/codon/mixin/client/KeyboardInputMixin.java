package works.nuty.codon.mixin.client;

import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.client.CodonClientMod;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void codon$keepNavigationInCamera(CallbackInfo ci) {
        var freecam = CodonClientMod.freecam();
        if (freecam != null && freecam.isActive()) {
            keyPresses = Input.EMPTY;
            moveVector = Vec2.ZERO;
            ci.cancel();
        }
    }
}
