package works.nuty.codon.mixin;

import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.CommandStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Read-only load confirmation: vanilla get() returns an empty tag even when loading failed. */
@Mixin(CommandStorage.class)
public interface CommandStorageAccessor {
    @Accessor("namespaces")
    Map<String, SavedData> codon$loadedNamespaces();
}
