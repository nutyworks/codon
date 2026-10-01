package works.nuty.codon.adapter;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import works.nuty.codon.mixin.CommandStorageAccessor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.TreeSet;

/** Discover cold persisted namespaces only when a debugger snapshot needs them. */
public final class PersistedStorageNamespaces {
    private PersistedStorageNamespaces() { }

    public static void loadForSnapshot(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Storage discovery requires the server thread");
        var storage = server.getCommandStorage();
        var loaded = ((CommandStorageAccessor) storage).codon$loadedNamespaces();
        var reads = (StorageReadStatus) server.getDataStorage();
        try {
            for (String namespace : namespaces(server.getWorldPath(LevelResource.DATA))) {
                if (reads.codon$hadIncompleteStorageRead(namespace)) {
                    throw new IllegalStateException("Incomplete persisted storage namespace " + namespace);
                }
                if (loaded.containsKey(namespace)) continue;
                // get() loads an existing namespace without creating a container, key or dirty value.
                storage.get(Identifier.fromNamespaceAndPath(namespace, "__codon_snapshot_probe__"));
                if (!loaded.containsKey(namespace) || reads.codon$hadIncompleteStorageRead(namespace)) {
                    throw new IllegalStateException("Could not read persisted storage namespace " + namespace);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not discover persisted storage namespaces", failure);
        }
    }

    /** Minecraft 26.3's SavedData identifier namespace:command_storage maps to this one-level path. */
    static List<String> namespaces(Path dataDirectory) throws IOException {
        var result = new TreeSet<String>();
        try (var entries = Files.newDirectoryStream(dataDirectory)) {
            for (Path entry : entries) {
                String namespace = entry.getFileName().toString();
                if (!Identifier.isValidNamespace(namespace)) continue;
                try {
                    if (Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory()
                        && Files.readAttributes(entry.resolve("command_storage.dat"), BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS).isRegularFile()) result.add(namespace);
                } catch (NoSuchFileException missing) {
                    // An unrelated namespace directory, or a file removed before this snapshot.
                }
            }
        } catch (NoSuchFileException missing) {
            if (!Files.notExists(dataDirectory)) throw missing;
        }
        return List.copyOf(result);
    }
}
