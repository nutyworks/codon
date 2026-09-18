package works.nuty.codon.mixin.client;

import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DeltaTracker.Timer.class)
public interface DeltaTrackerAccessor {
    @Accessor("deltaTickResidual")
    float codon$unpausedPartialTick();
}
