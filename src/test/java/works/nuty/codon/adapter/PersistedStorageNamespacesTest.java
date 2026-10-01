package works.nuty.codon.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PersistedStorageNamespacesTest {
    @TempDir Path root;

    @Test void discoversOnlyValidImmediateStorageFiles() throws IOException {
        file("zeta/command_storage.dat");
        file("alpha/command_storage.dat");
        file("UPPERCASE/command_storage.dat");
        file("other/scoreboard.dat");
        file("nested/deep/command_storage.dat");
        file("command_storage.dat");
        Files.createDirectories(root.resolve("empty/command_storage.dat"));
        assertEquals(List.of("alpha", "zeta"), PersistedStorageNamespaces.namespaces(root));
    }

    @Test void doesNotFollowNamespaceOrStorageFileLinks() throws IOException {
        Path external = Files.createDirectory(root.resolve("external"));
        Files.writeString(external.resolve("command_storage.dat"), "persisted");
        Path data = Files.createDirectory(root.resolve("data"));
        Files.createSymbolicLink(data.resolve("linked"), external);
        Files.createDirectories(data.resolve("file_link"));
        Files.createSymbolicLink(data.resolve("file_link/command_storage.dat"), external.resolve("command_storage.dat"));
        assertEquals(List.of(), PersistedStorageNamespaces.namespaces(data));
    }

    @Test void absentDataDirectoryIsEmptyWithoutCreatingIt() throws IOException {
        Path missing = root.resolve("missing");
        assertEquals(List.of(), PersistedStorageNamespaces.namespaces(missing));
        assertFalse(Files.exists(missing));
    }

    private void file(String path) throws IOException {
        Path target = root.resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, "persisted");
    }
}
