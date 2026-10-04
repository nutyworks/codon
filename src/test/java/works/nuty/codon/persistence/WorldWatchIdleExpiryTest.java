package works.nuty.codon.persistence;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import works.nuty.codon.adapter.McExecutionController;
import works.nuty.codon.core.model.TransferBudget;
import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.core.port.ExecutionController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorldWatchIdleExpiryTest {
    @TempDir Path world;
    private static final WatchSpec SAVED = new WatchSpec(WatchSpec.Kind.SCORE, "saved", "");
    private static final WatchSpec FIRST = new WatchSpec(WatchSpec.Kind.SCORE, "first", "");
    private static final WatchSpec SECOND = new WatchSpec(WatchSpec.Kind.SCORE, "second", "");

    @Test void idleSweepReleasesExpiredStagingAndPreservesActiveTransferAndSavedSnapshot() throws Exception {
        AtomicLong clock = new AtomicLong();
        var persistence = new WorldWatchPersistence(problem -> fail(problem), clock::get);
        UUID idle = UUID.randomUUID(), active = UUID.randomUUID();
        persistence.openWorld(world);
        assertTrue(persistence.save(idle, List.of(SAVED)));
        Path file = world.resolve("data/codon-watches").resolve(idle + ".json");
        String savedBytes = Files.readString(file);
        assertEquals(WorldWatchPersistence.ChunkSaveResult.ACCEPTED,
            persistence.saveChunk(idle, 1, 0, false, List.of(FIRST)));

        clock.set(TransferBudget.TIMEOUT_NANOS - 1);
        persistence.expireTransfers();
        assertTrue(staged(persistence).containsKey(idle), "the full transfer lifetime remains available");
        assertEquals(WorldWatchPersistence.ChunkSaveResult.ACCEPTED,
            persistence.saveChunk(active, 2, 0, false, List.of(FIRST)));

        // No more packets arrive for the idle player, including at the timeout boundary.
        clock.incrementAndGet();
        persistence.expireTransfers();
        assertEquals(java.util.Set.of(active), staged(persistence).keySet());
        assertEquals(List.of(SAVED), persistence.get(idle));
        assertEquals(savedBytes, Files.readString(file));
        assertEquals(WorldWatchPersistence.ChunkSaveResult.ACCEPTED,
            persistence.saveChunk(active, 2, 1, true, List.of(SECOND)));
        assertEquals(List.of(FIRST, SECOND), persistence.get(active));
        assertTrue(staged(persistence).isEmpty());
        persistence.closeWorld();
        persistence.openWorld(world);
        assertEquals(List.of(SAVED), persistence.get(idle));
        assertEquals(List.of(FIRST, SECOND), persistence.get(active));
        persistence.closeWorld();
    }

    @Test void aLaterPageDoesNotExtendTheDeadlineAndFreshUploadRecovers() throws Exception {
        AtomicLong clock = new AtomicLong();
        var persistence = new WorldWatchPersistence(problem -> fail(problem), clock::get);
        UUID player = UUID.randomUUID();
        persistence.openWorld(world);
        assertTrue(persistence.save(player, List.of(SAVED)));
        assertEquals(WorldWatchPersistence.ChunkSaveResult.ACCEPTED,
            persistence.saveChunk(player, 1, 0, false, List.of(FIRST)));
        clock.set(TransferBudget.TIMEOUT_NANOS - 1);
        assertEquals(WorldWatchPersistence.ChunkSaveResult.ACCEPTED,
            persistence.saveChunk(player, 1, 1, false, List.of(SECOND)));
        clock.incrementAndGet();
        persistence.expireTransfers();
        assertTrue(staged(persistence).isEmpty());
        assertEquals(WorldWatchPersistence.ChunkSaveResult.INVALID,
            persistence.saveChunk(player, 1, 2, true, List.of()));
        assertEquals(List.of(SAVED), persistence.get(player));
        assertEquals(WorldWatchPersistence.ChunkSaveResult.ACCEPTED,
            persistence.saveChunk(player, 2, 0, true, List.of(FIRST)));
        assertEquals(List.of(FIRST), persistence.get(player));

        assertEquals(WorldWatchPersistence.ChunkSaveResult.ACCEPTED,
            persistence.saveChunk(player, 3, 0, false, List.of(SECOND)));
        persistence.resetTransfer(player); // Existing disconnect cleanup.
        persistence.expireTransfers();
        assertTrue(staged(persistence).isEmpty());
        assertEquals(List.of(FIRST), persistence.get(player));
        assertEquals(WorldWatchPersistence.ChunkSaveResult.ACCEPTED,
            persistence.saveChunk(player, 4, 0, true, List.of(SECOND)));
        persistence.closeWorld();
        persistence.openWorld(world);
        assertEquals(List.of(SECOND), persistence.get(player));
        persistence.closeWorld();
    }

    @Test void pausedServerRunsExpiryWithoutANormalTickOrAnotherPacket() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        AtomicLong clock = new AtomicLong();
        var persistence = new WorldWatchPersistence(problem -> fail(problem), clock::get);
        UUID player = UUID.randomUUID();
        persistence.openWorld(world);
        assertTrue(persistence.save(player, List.of(SAVED)));
        persistence.saveChunk(player, 1, 0, false, List.of(FIRST));
        clock.set(TransferBudget.TIMEOUT_NANOS);

        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        when(server.isRunning()).thenReturn(true);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayers()).thenReturn(List.of());
        when(server.getAllLevels()).thenReturn(List.of());
        AtomicBoolean swept = new AtomicBoolean();
        var controller = new McExecutionController(() -> server, () -> {
            assertTrue(McExecutionController.isParked());
            persistence.expireTransfers();
            swept.set(true);
        });
        AtomicInteger resumeChecks = new AtomicInteger();
        assertEquals(ExecutionController.ParkResult.RESUMED,
            controller.parkUntil(() -> swept.get() || resumeChecks.incrementAndGet() >= 10));
        assertTrue(swept.get());
        assertFalse(McExecutionController.isParked());
        assertTrue(staged(persistence).isEmpty());
        assertEquals(List.of(SAVED), persistence.get(player));
        persistence.closeWorld();
    }

    private static Map<?, ?> staged(WorldWatchPersistence persistence) throws Exception {
        var field = WorldWatchPersistence.class.getDeclaredField("stagedTransfers");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(persistence);
    }
}
