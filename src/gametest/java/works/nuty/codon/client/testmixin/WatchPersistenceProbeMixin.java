package works.nuty.codon.client.testmixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.client.WatchUploadIdleExpiryGameTest;
import works.nuty.codon.persistence.WorldWatchPersistence;

import java.nio.file.Path;
import java.util.UUID;

/** Test-only observation of the actual composition-root persistence; no lifecycle behavior changes. */
@Mixin(value = WorldWatchPersistence.class, remap = false)
abstract class WatchPersistenceProbeMixin {
    @Inject(method = "openWorld(Ljava/nio/file/Path;Ljava/util/UUID;Ljava/util/UUID;)V", at = @At("RETURN"))
    private void codon$capturePersistence(Path world, UUID currentOwner, UUID previousOwner, CallbackInfo ci) {
        WatchUploadIdleExpiryGameTest.capturePersistence((WorldWatchPersistence) (Object) this);
    }
}
