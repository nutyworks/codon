package works.nuty.bastion.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.renderer.WorldBorderRenderer;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import works.nuty.bastion.client.BastionClientMod;

/** These presentation classes use wall time rather than the game's paused tick clock. */
@Mixin({Hud.class, LerpingBossEvent.class, WorldBorderRenderer.class, TextureTransform.class})
public abstract class EffectClockMixin {
    @ModifyExpressionValue(method = {"*", "<init>"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/util/Util;getMillis()J"))
    private static long bastion$pauseEffectTime(long realTimeMillis) {
        return BastionClientMod.effectTimeMillis(realTimeMillis);
    }
}
