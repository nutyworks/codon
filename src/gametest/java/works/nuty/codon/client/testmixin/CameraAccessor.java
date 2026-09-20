package works.nuty.codon.client.testmixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Camera.class)
public interface CameraAccessor {
    @Accessor("oldFovModifier") float codon$oldFovModifier();
    @Accessor("oldFovModifier") void codon$oldFovModifier(float value);
    @Accessor("fovModifier") float codon$fovModifier();
    @Accessor("fovModifier") void codon$fovModifier(float value);
}
