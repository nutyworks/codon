package works.nuty.codon.adapter;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the actual 26.3 reader/codec in a headless JVM, using only temporary files. */
class StorageReadFailureTest {
    @TempDir Path data;

    @BeforeAll static void detectVersion() {
        SharedConstants.tryDetectVersion();
    }

    @Test void validAndEmptyPersistedContainersAreLoadedWithoutDirtying() throws Exception {
        write("valid", contents(false, false));
        write("empty", new CompoundTag());
        try (var saved = reader()) {
            var storage = new CommandStorage(saved);
            assertEquals(0, storage.get(id("valid")).getIntOr("changed", -1));
            assertTrue(loaded(storage).containsKey("valid"));
            assertTrue(storage.get(id("empty")).isEmpty());
            assertTrue(loaded(storage).containsKey("empty"));
            assertTrue(loaded(storage).values().stream().noneMatch(SavedData::isDirty));
            System.out.println("Storage read control: valid0 and genuine empty container loaded, not dirty");
        }
    }

    @Test void truncatedAndInvalidByteFilesDoNotCacheEmptyContainers() throws Exception {
        Path truncated = write("truncated", contents(false, false));
        byte[] original = Files.readAllBytes(truncated);
        Files.write(truncated, Arrays.copyOf(original, 8));
        Path invalid = data.resolve("invalid/command_storage.dat");
        Files.createDirectories(invalid.getParent());
        Files.write(invalid, new byte[]{99, 11, 12});
        try (var saved = reader()) {
            var storage = new CommandStorage(saved);
            for (String ns : new String[]{"truncated", "invalid"}) {
                assertTrue(storage.get(id(ns)).isEmpty());
                assertFalse(loaded(storage).containsKey(ns), "Failed read must not cache an empty container");
                assertTrue(storage.keys().findAny().isEmpty());
                System.out.println("Storage failed read: " + ns + " returned empty tag, namespace absent");
            }
        }
        assertArrayEquals(Arrays.copyOf(original, 8), Files.readAllBytes(truncated));
        assertArrayEquals(new byte[]{99, 11, 12}, Files.readAllBytes(invalid));
    }

    @Test void vanillaPartiallyDecodesMalformedContentsIntoLoadedContainers() throws Exception {
        write("invalid_entry", contents(true, false));
        write("mixed_entry", contents(true, true));
        try (var saved = reader()) {
            var storage = new CommandStorage(saved);
            for (String ns : new String[]{"invalid_entry", "mixed_entry"}) {
                var value = storage.get(id(ns));
                System.out.println("Storage malformed decode: " + ns + " loaded=" + loaded(storage).containsKey(ns)
                    + " value=" + value + " keys=" + storage.keys().toList());
                // JUnit does not transform Minecraft with Mixins: this proves the vanilla behavior
                // that the actual snapshot regression must reject, without changing its reader.
                assertTrue(loaded(storage).containsKey(ns), "Vanilla exposes a partial decoded container");
                assertEquals(ns.equals("mixed_entry") ? 0 : -1, value.getIntOr("changed", -1));
                assertFalse(loaded(storage).get(ns).isDirty());
            }
        }
    }

    private SavedDataStorage reader() {
        return new SavedDataStorage(data, DataFixers.getDataFixer(), RegistryAccess.EMPTY);
    }

    private Path write(String namespace, CompoundTag contents) throws Exception {
        var payload = new CompoundTag();
        payload.put("contents", contents);
        var root = new CompoundTag();
        root.put("data", payload);
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        Path path = data.resolve(namespace + "/command_storage.dat");
        Files.createDirectories(path.getParent());
        NbtIo.writeCompressed(root, path);
        return path;
    }

    private static CompoundTag contents(boolean malformed, boolean mixed) {
        var result = new CompoundTag();
        var target = new CompoundTag();
        target.putInt("changed", 0);
        if (malformed) result.putString("invalid", "not a compound");
        if (!malformed || mixed) result.put("acceptance", target);
        return result;
    }

    private static Identifier id(String namespace) {
        return Identifier.fromNamespaceAndPath(namespace, "acceptance");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, SavedData> loaded(CommandStorage storage) throws Exception {
        var field = CommandStorage.class.getDeclaredField("namespaces");
        field.setAccessible(true);
        return (Map<String, SavedData>) field.get(storage);
    }
}
