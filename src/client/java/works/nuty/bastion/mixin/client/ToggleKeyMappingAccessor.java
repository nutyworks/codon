package works.nuty.bastion.mixin.client;

import net.minecraft.client.ToggleKeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ToggleKeyMapping.class)
public interface ToggleKeyMappingAccessor {
    @Invoker("reset")
    void bastion$reset();

    @Accessor("releasedByScreenWhenDown")
    void bastion$setReleasedByScreenWhenDown(boolean value);
}
