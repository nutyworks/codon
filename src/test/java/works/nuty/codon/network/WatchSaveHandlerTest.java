package works.nuty.codon.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.persistence.WorldWatchPersistence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WatchSaveHandlerTest {
    @TempDir Path world;
    private static final WatchSpec FIRST = new WatchSpec(WatchSpec.Kind.SCORE, "first", "");
    private static final WatchSpec SECOND = new WatchSpec(WatchSpec.Kind.SCORE, "second", "");

    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void v2AcknowledgesStagingAndOnlyReportsSavedAfterFinalPersistence() {
        try (Fixture fixture = new Fixture()) {
            fixture.handler.save(fixture.player, new WatchSaveV2Payload(1, 0, false, List.of(FIRST)));
            assertEquals(List.of(new WatchSavePageAckPayload(1, 1)), fixture.sent);
            assertTrue(fixture.watches.get(fixture.player.getUUID()).isEmpty(), "progress is not a durable save");
            fixture.handler.save(fixture.player, new WatchSaveV2Payload(1, 1, true, List.of(SECOND)));
            assertEquals(new WatchSaveSyncPayload(1, WatchSaveSyncPayload.Status.SAVED), fixture.sent.getLast());
            assertEquals(2, fixture.sent.size(), "final page has no progress acknowledgement");
            assertEquals(List.of(FIRST, SECOND), fixture.watches.get(fixture.player.getUUID()));
        }
    }

    @Test void missingPageRejectsLaterCompletionAndPreservesPreviousDefinitions() {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.watches.save(fixture.player.getUUID(), List.of(SECOND)));
            fixture.handler.save(fixture.player, new WatchSaveV2Payload(2, 0, false, List.of(FIRST)));
            fixture.handler.save(fixture.player, new WatchSaveV2Payload(2, 2, true, List.of(SECOND)));
            assertEquals(new WatchSaveSyncPayload(2, WatchSaveSyncPayload.Status.INVALID), fixture.sent.getLast());
            fixture.handler.save(fixture.player, new WatchSaveV2Payload(2, 1, true, List.of(SECOND)));
            assertEquals(new WatchSaveSyncPayload(2, WatchSaveSyncPayload.Status.INVALID), fixture.sent.getLast());
            assertEquals(List.of(SECOND), fixture.watches.get(fixture.player.getUUID()));
        }
    }

    @Test void authorizationAndNegotiatedReplySupportPrecedeStaging() {
        try (Fixture fixture = new Fixture()) {
            when(fixture.source.permissions()).thenReturn(net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS);
            fixture.handler.save(fixture.player, new WatchSaveV2Payload(3, 0, true, List.of(FIRST)));
            assertEquals(List.of(new WatchSaveSyncPayload(3, WatchSaveSyncPayload.Status.FAILED)), fixture.sent);
            when(fixture.source.permissions()).thenReturn(LevelBasedPermissionSet.OWNER);
            fixture.network.when(() -> ServerPlayNetworking.canSend(fixture.player, WatchSavePageAckPayload.TYPE.id()))
                .thenReturn(false);
            fixture.handler.save(fixture.player, new WatchSaveV2Payload(4, 0, true, List.of(FIRST)));
            assertEquals(new WatchSaveSyncPayload(4, WatchSaveSyncPayload.Status.FAILED), fixture.sent.getLast());
            assertTrue(fixture.watches.get(fixture.player.getUUID()).isEmpty());
        }
    }

    @Test void legacyPagesKeepFinalOnlyAcknowledgementAndPersistenceFailuresStayFailures() {
        try (Fixture fixture = new Fixture()) {
            fixture.handler.save(fixture.player, new WatchSavePayload(5, 0, false, List.of(FIRST)));
            assertTrue(fixture.sent.isEmpty());
            fixture.handler.save(fixture.player, new WatchSavePayload(5, 1, true, List.of(SECOND)));
            assertEquals(List.of(new WatchSaveSyncPayload(5, WatchSaveSyncPayload.Status.SAVED)), fixture.sent);
            WorldWatchPersistence unavailable = new WorldWatchPersistence(problem -> { });
            new DebuggerRequestHandler(null, unavailable).save(fixture.player,
                new WatchSaveV2Payload(6, 0, true, List.of(FIRST)));
            assertEquals(new WatchSaveSyncPayload(6, WatchSaveSyncPayload.Status.FAILED), fixture.sent.getLast());
        }
    }

    private final class Fixture implements AutoCloseable {
        final ServerPlayer player = mock(ServerPlayer.class);
        final CommandSourceStack source = mock(CommandSourceStack.class);
        final List<CustomPacketPayload> sent = new ArrayList<>();
        final WorldWatchPersistence watches = new WorldWatchPersistence(problem -> fail(problem));
        final DebuggerRequestHandler handler = new DebuggerRequestHandler(null, watches);
        final MockedStatic<ServerPlayNetworking> network = mockStatic(ServerPlayNetworking.class);

        Fixture() {
            player.connection = mock(ServerGamePacketListenerImpl.class);
            when(player.connection.isAcceptingMessages()).thenReturn(true);
            when(player.createCommandSourceStack()).thenReturn(source);
            when(source.permissions()).thenReturn(LevelBasedPermissionSet.OWNER);
            when(player.getUUID()).thenReturn(UUID.randomUUID());
            watches.openWorld(world, null, null);
            network.when(() -> ServerPlayNetworking.canSend(eq(player), any(Identifier.class))).thenReturn(true);
            network.when(() -> ServerPlayNetworking.send(eq(player), any(CustomPacketPayload.class))).thenAnswer(call -> {
                sent.add(call.getArgument(1));
                return null;
            });
        }

        @Override public void close() {
            watches.closeWorld();
            network.close();
        }
    }
}
