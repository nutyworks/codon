package works.nuty.codon.mixin;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.adapter.PausedStateSynchronizer;
import works.nuty.codon.adapter.PausedWorldState.LightUpdate;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheMixin implements PausedStateSynchronizer {
    @Shadow @Final private Set<ChunkHolder> chunkHoldersToBroadcast;
    @Shadow public abstract ThreadedLevelLightEngine getLightEngine();
    @Shadow protected abstract ChunkHolder getVisibleChunkIfPresent(long pos);
    @Shadow protected abstract void broadcastChangedChunks(ProfilerFiller profiler);

    // Vanilla marshals these notifications onto the ordinary chunk task queue. Retain only the
    // completed-light notification, so a parked server need not pump that general-purpose queue.
    @Unique private final Set<LightUpdate> codon$pendingLight = ConcurrentHashMap.newKeySet();

    @Inject(method = "onLightUpdate", at = @At("HEAD"))
    private void codon$rememberCompletedLight(LightLayer layer, SectionPos pos, CallbackInfo ci) {
        codon$pendingLight.add(new LightUpdate(layer, pos));
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void codon$clearNormalTickLight(BooleanSupplier haveTime, boolean tickChunks, CallbackInfo ci) {
        // Normal ticks retain vanilla's notification path; do not retain an extra history.
        codon$pendingLight.clear();
    }

    @Override
    public void codon$syncPausedState() {
        getLightEngine().tryScheduleUpdate();
        for (LightUpdate update : codon$pendingLight) {
            if (!codon$pendingLight.remove(update)) continue;
            ChunkHolder holder = getVisibleChunkIfPresent(update.pos().chunk().pack());
            if (holder != null && holder.sectionLightChanged(update.layer(), update.pos().y())) {
                chunkHoldersToBroadcast.add(holder);
            }
        }
        broadcastChangedChunks(Profiler.get());
    }
}
