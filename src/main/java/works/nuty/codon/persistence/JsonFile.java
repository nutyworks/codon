package works.nuty.codon.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** UTF-8 JSON files replaced only after the complete new document has been written. */
public final class JsonFile {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private final Path path;

    public JsonFile(Path path) {
        this.path = path.toAbsolutePath();
    }

    public Optional<JsonObject> read() throws IOException {
        String json;
        try {
            json = Files.readString(path);
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        }
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) throw new JsonParseException("Expected a JSON object");
            return Optional.of(element.getAsJsonObject());
        } catch (JsonParseException | IllegalStateException invalid) {
            throw new IOException("Cannot read " + path, invalid);
        }
    }

    public void write(JsonObject document) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), path.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(document) + "\n");
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
