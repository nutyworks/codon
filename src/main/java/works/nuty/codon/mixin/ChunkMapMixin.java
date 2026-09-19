package works.nuty.codon.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import works.nuty.codon.adapter.PausedStateSynchronizer;

@Mixin(ChunkMap.class)
abstract class ChunkMapMixin implements PausedStateSynchronizer {
    @Shadow @Final private Int2ObjectMap<?> entityMap;

    @Override
    public void codon$syncPausedState() {
        // Re-evaluate visibility after teleports, using vanilla tracking recipients. No entity,
        // chunk-ticket, spawning, or player tick is run here.
        for (Object tracked : java.util.List.copyOf(entityMap.values())) {
            ((PausedStateSynchronizer) tracked).codon$syncPausedState();
        }
    }
}
