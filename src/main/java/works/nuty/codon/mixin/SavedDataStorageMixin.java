package works.nuty.codon.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.serialization.DataResult;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import works.nuty.codon.adapter.StorageReadStatus;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Observe codec errors without changing vanilla's partial-result/cache/write behavior. */
@Mixin(SavedDataStorage.class)
public abstract class SavedDataStorageMixin implements StorageReadStatus {
    @Unique private final Set<String> codon$incompleteStorageReads = new HashSet<>();

    @WrapOperation(method = "readSavedData", at = @At(value = "INVOKE",
        target = "Lcom/mojang/serialization/DataResult;resultOrPartial(Ljava/util/function/Consumer;)Ljava/util/Optional;"))
    private <T extends SavedData> Optional<T> codon$observeStorageDecode(DataResult<T> result,
            Consumer<String> onError, Operation<Optional<T>> original,
            @Local(argsOnly = true) SavedDataType<T> type) {
        if (type.id().getPath().equals("command_storage")) {
            String namespace = type.id().getNamespace();
            if (result.error().isPresent()) codon$incompleteStorageReads.add(namespace);
            else codon$incompleteStorageReads.remove(namespace);
        }
        return original.call(result, onError);
    }

    @Override
    public boolean codon$hadIncompleteStorageRead(String namespace) {
        return codon$incompleteStorageReads.contains(namespace);
    }
}
