package works.nuty.codon.adapter;

import net.minecraft.server.MinecraftServer;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;

/** The tick-deferred, outbound-only part of a debugger stop. Runs on the server thread. */
public final class PausedWorldState {
    private PausedWorldState() {}

    public static void synchronize(MinecraftServer server) {
        for (var player : java.util.List.copyOf(server.getPlayerList().getPlayers())) {
            player.level().getChunkSource().move(player);
            // Pending, already-ready chunks must precede pairing entities in those chunks.
            // This is one outbound batch, not connection.tick() or a chunk-loading task pump.
            player.connection.chunkSender.sendNextChunks(player);
        }
        flushChunks(server);
        for (var level : server.getAllLevels()) {
            ((PausedStateSynchronizer) level.getChunkSource().chunkMap).codon$syncPausedState();
        }
        for (var player : java.util.List.copyOf(server.getPlayerList().getPlayers())) {
            ((PausedStateSynchronizer) player).codon$syncPausedState();
        }
    }

    /** Lighting finishes asynchronously, so publish its completed updates while still parked. */
    public static void flushChunks(MinecraftServer server) {
        for (var level : server.getAllLevels()) {
            ((PausedStateSynchronizer) level.getChunkSource()).codon$syncPausedState();
        }
    }

    public record LightUpdate(LightLayer layer, SectionPos pos) {}
}
