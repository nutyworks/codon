package works.nuty.codon.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import works.nuty.codon.core.model.WatchSpec;
import static org.junit.jupiter.api.Assertions.*;

class WatchRestorePreservationTest {
    @TempDir Path world;

    @Test void oversizedPersistedCountCannotBeReplacedByAPartialClientUpload() throws Exception {
        preservesRejectedRestore(IntStream.range(0, 8193)
            .mapToObj(i -> new WatchSpec(WatchSpec.Kind.SCORE, "saved" + i, "")).toList());
    }

    @Test void oversizedPersistedSerializedTextCannotBeReplacedByAPartialClientUpload() throws Exception {
        preservesRejectedRestore(IntStream.range(0, 6000)
            .mapToObj(i -> new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:" + "x".repeat(120),
                "\\".repeat(120) + i)).toList());
    }

    private void preservesRejectedRestore(List<WatchSpec> stored) throws Exception {
        UUID player = UUID.randomUUID();
        var persistence = new WorldWatchPersistence(failure -> fail(failure));
        persistence.openWorld(world);
        assertTrue(persistence.save(player, stored), "Existing disk format remains transport-unbounded");
        persistence.closeWorld();
        Path file = world.resolve("data/codon-watches/" + player + ".json");
        byte[] before = Files.readAllBytes(file);
        persistence.openWorld(world);
        assertEquals(stored, persistence.get(player));
        var local = List.of(new WatchSpec(WatchSpec.Kind.SCORE, "local", ""));
        assertEquals(WorldWatchPersistence.ChunkSaveResult.SAVE_FAILED,
            persistence.saveChunk(player, 1, 0, true, local));
        persistence.resetTransfer(player);
        assertEquals(WorldWatchPersistence.ChunkSaveResult.SAVE_FAILED,
            persistence.saveChunk(player, 2, 0, false, local), "Reconnect cannot erase unseen saved rows");
        assertEquals(stored, persistence.get(player));
        persistence.closeWorld();
        assertArrayEquals(before, Files.readAllBytes(file));
    }
}
