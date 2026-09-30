package works.nuty.codon.client.testmixin;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Fabric cancels native focus callbacks; simulate the focused flag for reset assertions. */
@Mixin(Window.class)
public interface WindowFocusAccessor {
    @Accessor("focused") void codon$focus(boolean focused);
}
