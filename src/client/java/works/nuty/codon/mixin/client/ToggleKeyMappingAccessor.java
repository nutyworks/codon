package works.nuty.codon.mixin.client;

import net.minecraft.client.ToggleKeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ToggleKeyMapping.class)
public interface ToggleKeyMappingAccessor {
    @Invoker("reset")
    void codon$reset();

    @Accessor("releasedByScreenWhenDown")
    void codon$setReleasedByScreenWhenDown(boolean value);
}
