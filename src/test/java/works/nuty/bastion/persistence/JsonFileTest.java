package works.nuty.bastion.persistence;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JsonFileTest {
    @TempDir Path directory;

    @Test
    void roundTripsUtf8AndReplacesAnExistingFileWithoutLeavingTemporaryFiles() throws Exception {
        JsonFile file = new JsonFile(directory.resolve("nested/settings.json"));
        assertTrue(file.read().isEmpty());
        JsonObject first = new JsonObject();
        first.addProperty("label", "중단점");
        file.write(first);
        assertEquals(first, file.read().orElseThrow());
        JsonObject second = new JsonObject();
        second.addProperty("version", 1);
        file.write(second);
        assertEquals(second, file.read().orElseThrow());
        try (var paths = Files.list(directory.resolve("nested"))) {
            assertEquals(1, paths.count());
        }
    }

    @Test
    void failedReplacementPreservesDestinationAndCleansTemporaryFiles() throws Exception {
        Path destination = Files.createDirectories(directory.resolve("settings.json"));
        Path existing = destination.resolve("keep.txt");
        Files.writeString(existing, "keep");
        JsonFile file = new JsonFile(destination);
        assertThrows(IOException.class, () -> file.write(new JsonObject()));
        assertEquals("keep", Files.readString(existing));
        try (var paths = Files.list(directory)) {
            assertEquals(1, paths.count());
        }
    }
}
