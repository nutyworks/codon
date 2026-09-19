package works.nuty.codon.mixin;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import works.nuty.codon.adapter.PausedStateSynchronizer;

import java.util.List;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
abstract class TrackedEntityMixin implements PausedStateSynchronizer {
    @Shadow @Final private ServerEntity serverEntity;
    @Shadow @Final private Entity entity;
    @Shadow public abstract void updatePlayers(List<ServerPlayer> players);

    @Override
    public void codon$syncPausedState() {
        if (entity.isRemoved()) return;
        updatePlayers(((ServerLevel) entity.level()).players());
        ((PausedStateSynchronizer) serverEntity).codon$syncPausedState();
    }
}
