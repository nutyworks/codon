package works.nuty.codon.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.SharedConstants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.core.service.DebuggerEngine;
import works.nuty.codon.persistence.WorldWatchPersistence;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WatchSaveRegistrationTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @SuppressWarnings("unchecked")
    @Test void registersV2AndPageCreditAlongsideLegacyAndFinalResult() {
        PayloadTypeRegistry<RegistryFriendlyByteBuf> inbound = mock(PayloadTypeRegistry.class);
        PayloadTypeRegistry<RegistryFriendlyByteBuf> outbound = mock(PayloadTypeRegistry.class);
        try (var registry = mockStatic(PayloadTypeRegistry.class)) {
            registry.when(PayloadTypeRegistry::serverboundPlay).thenReturn(inbound);
            registry.when(PayloadTypeRegistry::clientboundPlay).thenReturn(outbound);
            CodonNetworking.registerPayloadTypes();
            verify(inbound).register(WatchSavePayload.TYPE, WatchSavePayload.CODEC);
            verify(inbound).register(WatchSaveV2Payload.TYPE, WatchSaveV2Payload.CODEC);
            verify(outbound).register(WatchSavePageAckPayload.TYPE, WatchSavePageAckPayload.CODEC);
            verify(outbound).register(WatchSaveSyncPayload.TYPE, WatchSaveSyncPayload.CODEC);
        }
    }

    @Test void bothSaveVersionsRouteAuthenticatedPlayerToTheirHandlerOverload() {
        var legacy = new AtomicReference<ServerPlayNetworking.PlayPayloadHandler<WatchSavePayload>>();
        var v2 = new AtomicReference<ServerPlayNetworking.PlayPayloadHandler<WatchSaveV2Payload>>();
        try (var networking = mockStatic(ServerPlayNetworking.class);
             var handlers = mockConstruction(DebuggerRequestHandler.class)) {
            networking.when(() -> ServerPlayNetworking.registerGlobalReceiver(eq(WatchSavePayload.TYPE), any()))
                .thenAnswer(call -> { legacy.set(call.getArgument(1)); return true; });
            networking.when(() -> ServerPlayNetworking.registerGlobalReceiver(eq(WatchSaveV2Payload.TYPE), any()))
                .thenAnswer(call -> { v2.set(call.getArgument(1)); return true; });
            CodonNetworking.registerRequests(mock(DebuggerEngine.class), mock(WorldWatchPersistence.class));
            assertNotNull(legacy.get());
            assertNotNull(v2.get());
            assertEquals(1, handlers.constructed().size());
            var player = mock(ServerPlayer.class);
            var context = mock(ServerPlayNetworking.Context.class);
            when(context.player()).thenReturn(player);
            var definitions = List.of(new WatchSpec(WatchSpec.Kind.SCORE, "points", ""));
            var oldPage = new WatchSavePayload(1, 0, true, definitions);
            var newPage = new WatchSaveV2Payload(2, 0, false, definitions);
            legacy.get().receive(oldPage, context);
            v2.get().receive(newPage, context);
            verify(handlers.constructed().getFirst()).save(player, oldPage);
            verify(handlers.constructed().getFirst()).save(player, newPage);
        }
    }
}
