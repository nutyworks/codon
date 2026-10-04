package works.nuty.codon.network;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.PlayerList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import works.nuty.codon.CodonMod;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.core.model.*;
import works.nuty.codon.core.port.ExecutionController;
import works.nuty.codon.core.service.*;
import works.nuty.codon.persistence.WorldWatchPersistence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises production publication and the registered JOIN callback below all client UI filtering. */
class DebuggerRecipientAuthorizationTest {
    private static final BlockLocation BLOCK = new BlockLocation(731, 80, -216, "minecraft:overworld");
    private static final SourceLocation LOCATION = new SourceLocation.Block(BLOCK);
    private static final BreakpointDefinition DEFINITION = BreakpointDefinition.plain(BreakpointTarget.whole(LOCATION));
    private static final WatchSpec WATCH = new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "private:state", "secret");
    private static final PauseSnapshot SNAPSHOT = new PauseSnapshot(LOCATION,
        CommandSnippet.plain("tell owner private-command-argument"), 0, List.of(),
        List.of(new PauseSource(new Vec3d(731, 80, -216), 0, 0, null, "minecraft:overworld")),
        PauseReason.BREAKPOINT, 41);
    private static MinecraftServer server;
    private static PlayerList playerList;
    private static DebuggerEngine joinEngine;
    private static WorldWatchPersistence watches;
    private static NetworkDebuggerEventSink sink;
    private final Map<ServerPlayer, List<CustomPacketPayload>> sent = new HashMap<>();
    private final Map<ServerPlayer, Set<Identifier>> unsupported = new HashMap<>();
    private final Map<ServerPlayer, PermissionSet> permissions = new HashMap<>();
    private MockedStatic<ServerPlayNetworking> networking;
    private MockedStatic<CodonMod> mod;
    private ServerPlayer owner;
    private ServerPlayer visitor;

    @BeforeAll static void initialize() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        server = mock(MinecraftServer.class);
        playerList = mock(PlayerList.class);
        joinEngine = mock(DebuggerEngine.class);
        watches = mock(WorldWatchPersistence.class);
        sink = new NetworkDebuggerEventSink(() -> server);
        CodonNetworking.registerJoinSync(joinEngine, watches, sink);
    }

    @BeforeEach void setup() {
        reset(server, playerList, joinEngine, watches);
        ServerLifecycleEvents.SERVER_STOPPED.invoker().onServerStopped(server);
        sink.resetWatchChanges();
        owner = player(true);
        visitor = player(false);
        when(server.getPlayerList()).thenReturn(playerList);
        when(server.isSameThread()).thenReturn(true);
        when(playerList.getPlayers()).thenReturn(List.of(owner, visitor));
        when(joinEngine.blockBreakpoints()).thenReturn(Set.of(BLOCK));
        when(joinEngine.breakpointDefinitions()).thenReturn(List.of(DEFINITION));
        when(joinEngine.currentSnapshot()).thenReturn(SNAPSHOT);
        when(watches.get(owner.getUUID())).thenReturn(List.of(WATCH));
        when(watches.get(visitor.getUUID())).thenReturn(List.of(WATCH));
        mod = mockStatic(CodonMod.class);
        mod.when(CodonMod::engine).thenReturn(joinEngine);
        networking = mockStatic(ServerPlayNetworking.class);
        networking.when(() -> ServerPlayNetworking.canSend(any(ServerPlayer.class), any(Identifier.class)))
            .thenAnswer(call -> !unsupported.get(call.getArgument(0)).contains(call.getArgument(1)));
        networking.when(() -> ServerPlayNetworking.send(any(ServerPlayer.class), any(CustomPacketPayload.class)))
            .thenAnswer(call -> { sent.get(call.getArgument(0)).add(call.getArgument(1)); return null; });
    }

    @AfterEach void cleanup() {
        if (networking != null) networking.close();
        if (mod != null) mod.close();
    }

    @Test void dormantBreakpointCompletionOnlyPublishesPrivateFlowToOwner() {
        var history = new ExecutionFlowHistory();
        var engine = new DebuggerEngine(new BreakpointRegistry(), new StepController(), new CallStack(),
            mock(ExecutionController.class), sink, history);
        engine.toggleBlockBreakpoint(BLOCK);
        assertTrue(engine.isActive());
        clearSent();
        engine.onExecutionStarted();
        var recorder = history.start(91, new SourceLocation.Player(UUID.randomUUID(), "private-player"));
        recorder.beginStage(SNAPSHOT.command(), List.of(), 0, true);
        recorder.executionStarted();
        recorder.executionResult(true);
        engine.onExecutionFinished();
        assertFalse(engine.isPaused(), "Completion disclosure must be tested without ever pausing");
        var flow = only(owner, ExecutionFlowSyncPayload.class);
        assertEquals(SNAPSHOT.command(), flow.flows().getFirst().stages().getFirst().command());
        assertTrue(sent.get(visitor).isEmpty(), "Advertising the flow channel is not owner authorization");
    }

    @Test void livePauseAndBothBreakpointRepresentationsOnlyReachOwner() {
        sink.paused(SNAPSHOT);
        sink.breakpointsChanged(Set.of(BLOCK));
        assertEquals(SNAPSHOT, only(owner, PauseSyncPayload.class).snapshot());
        assertEquals(41, only(owner, WatchChangesSyncPayload.class).pauseId());
        assertEquals(List.of(BLOCK), only(owner, BreakpointSyncPayload.class).blocks());
        assertEquals(List.of(DEFINITION), only(owner, BreakpointDefinitionsSyncPayload.class).definitions());
        assertTrue(sent.get(visitor).isEmpty());
    }

    @Test void joinDoesNotReadOrPublishStoredStateForNonOwner() {
        join(visitor);
        assertTrue(sent.get(visitor).isEmpty());
        verifyNoInteractions(joinEngine);
        verify(watches, never()).get(any());
    }

    @Test void ownerJoinKeepsOwnWatchPaginationBreakpointsAndActivePause() {
        var definitions = java.util.stream.IntStream.range(0, 256)
            .mapToObj(index -> new WatchSpec(WatchSpec.Kind.SCORE, "points_" + index, "")).toList();
        when(watches.get(owner.getUUID())).thenReturn(definitions);
        sink.paused(SNAPSHOT);
        clearSent();
        join(owner);
        var pages = payloads(owner, WatchDefinitionsSyncPayload.class);
        assertTrue(pages.size() > 1);
        assertTrue(pages.getFirst().transferId() > 0);
        List<WatchSpec> restored = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            var page = pages.get(i);
            assertEquals(pages.getFirst().transferId(), page.transferId());
            assertEquals(restored.size(), page.offset());
            assertEquals(i == pages.size() - 1, page.last());
            restored.addAll(page.definitions());
        }
        assertEquals(definitions, restored);
        verify(watches).get(owner.getUUID());
        verify(watches, never()).get(visitor.getUUID());
        assertEquals(List.of(BLOCK), only(owner, BreakpointSyncPayload.class).blocks());
        assertEquals(List.of(DEFINITION), only(owner, BreakpointDefinitionsSyncPayload.class).definitions());
        assertEquals(SNAPSHOT, only(owner, PauseSyncPayload.class).snapshot());
        assertEquals(41, only(owner, WatchChangesSyncPayload.class).pauseId());
    }

    @Test void ownerJoinKeepsEmptyWatchTransferAndLegacyCapabilities() {
        when(watches.get(owner.getUUID())).thenReturn(List.of());
        unsupported.get(owner).add(BreakpointDefinitionsSyncPayload.TYPE.id());
        unsupported.get(owner).add(WatchChangesSyncPayload.TYPE.id());
        join(owner);
        var page = only(owner, WatchDefinitionsSyncPayload.class);
        assertTrue(page.last());
        assertEquals(0, page.offset());
        assertEquals(List.of(), page.definitions());
        assertEquals(List.of(BLOCK), only(owner, BreakpointSyncPayload.class).blocks());
        assertEquals(SNAPSHOT, only(owner, PauseSyncPayload.class).snapshot());
        assertTrue(payloads(owner, BreakpointDefinitionsSyncPayload.class).isEmpty());
    }

    @Test void promotionAfterUnauthorizedJoinRestoresSavingHandshakeOnce() {
        when(playerList.getPlayers()).thenReturn(List.of(visitor));
        join(visitor);
        tick();
        assertTrue(sent.get(visitor).isEmpty());
        verify(watches, never()).get(any());
        when(permissions.get(visitor).hasPermission(Permissions.COMMANDS_OWNER)).thenReturn(true);
        tick();
        var page = only(visitor, WatchDefinitionsSyncPayload.class);
        assertTrue(page.last(), "Complete handshake is required before the client installs its save listener");
        assertEquals(List.of(WATCH), page.definitions());
        assertEquals(SNAPSHOT, only(visitor, PauseSyncPayload.class).snapshot());
        only(visitor, BreakpointDefinitionsSyncPayload.class);
        clearSent();
        tick();
        assertTrue(sent.get(visitor).isEmpty(), "Normal ticks must not overwrite an owner's watch edits");
        verify(watches, times(1)).get(visitor.getUUID());
        when(permissions.get(visitor).hasPermission(Permissions.COMMANDS_OWNER)).thenReturn(false);
        tick();
        assertTrue(sent.get(visitor).isEmpty());
        when(permissions.get(visitor).hasPermission(Permissions.COMMANDS_OWNER)).thenReturn(true);
        tick();
        assertTrue(payloads(visitor, WatchDefinitionsSyncPayload.class).isEmpty(),
            "Re-promotion must not replace watch edits made after the first restore");
        only(visitor, BreakpointSyncPayload.class);
        only(visitor, BreakpointDefinitionsSyncPayload.class);
        only(visitor, PauseSyncPayload.class);
        verify(watches, times(1)).get(visitor.getUUID());
    }

    @Test void joinedOwnersAreNotResynchronizedUntilDisconnectOrShutdown() {
        when(playerList.getPlayers()).thenReturn(List.of(owner));
        join(owner);
        clearSent();
        tick();
        assertTrue(sent.get(owner).isEmpty());
        when(permissions.get(owner).hasPermission(Permissions.COMMANDS_OWNER)).thenReturn(false);
        tick();
        ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(owner.connection, server);
        when(permissions.get(owner).hasPermission(Permissions.COMMANDS_OWNER)).thenReturn(true);
        tick();
        only(owner, WatchDefinitionsSyncPayload.class);
        clearSent();
        when(permissions.get(owner).hasPermission(Permissions.COMMANDS_OWNER)).thenReturn(false);
        tick();
        ServerLifecycleEvents.SERVER_STOPPED.invoker().onServerStopped(server);
        when(permissions.get(owner).hasPermission(Permissions.COMMANDS_OWNER)).thenReturn(true);
        tick();
        only(owner, WatchDefinitionsSyncPayload.class);
    }

    @Test void oversizedCountIsRejectedBeforeAnyJoinOrPromotionPage() {
        rejectOversizedRestore(java.util.stream.IntStream.range(0, 8193)
            .mapToObj(i -> new WatchSpec(WatchSpec.Kind.SCORE, "saved" + i, "")).toList());
    }

    @Test void oversizedSerializedTextIsRejectedBeforeAnyJoinOrPromotionPage() {
        rejectOversizedRestore(java.util.stream.IntStream.range(0, 6000)
            .mapToObj(i -> new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:" + "x".repeat(120),
                "\\".repeat(120) + i)).toList());
    }

    private void rejectOversizedRestore(List<WatchSpec> definitions) {
        when(watches.get(owner.getUUID())).thenReturn(definitions);
        when(watches.get(visitor.getUUID())).thenReturn(definitions);
        join(owner);
        assertTrue(payloads(owner, WatchDefinitionsSyncPayload.class).isEmpty(),
            "JOIN must validate the entire saved list before sending a partial snapshot");
        join(visitor);
        assertTrue(sent.get(visitor).isEmpty());
        when(permissions.get(visitor).hasPermission(Permissions.COMMANDS_OWNER)).thenReturn(true);
        tick();
        assertTrue(payloads(visitor, WatchDefinitionsSyncPayload.class).isEmpty(),
            "Promotion uses the same restore budget as JOIN");
        only(owner, WatchRestoreFailedPayload.class);
        only(visitor, WatchRestoreFailedPayload.class);
        only(owner, BreakpointSyncPayload.class);
        only(visitor, PauseSyncPayload.class);
        clearSent();
        tick();
        when(permissions.get(owner).hasPermission(Permissions.COMMANDS_OWNER)).thenReturn(false);
        tick();
        when(permissions.get(owner).hasPermission(Permissions.COMMANDS_OWNER)).thenReturn(true);
        tick();
        assertTrue(payloads(owner, WatchRestoreFailedPayload.class).isEmpty());
        assertTrue(payloads(visitor, WatchRestoreFailedPayload.class).isEmpty());
        verify(watches, times(1)).get(owner.getUUID());
        verify(watches, times(1)).get(visitor.getUUID());
        verify(owner, times(1)).sendSystemMessage(any());
        verify(visitor, times(1)).sendSystemMessage(any());
    }

    @Test void oldClientReceivesOneRestoreWarningAndCanRestoreARepairedListOnReconnect() {
        when(playerList.getPlayers()).thenReturn(List.of(owner));
        unsupported.get(owner).add(WatchRestoreFailedPayload.TYPE.id());
        when(watches.get(owner.getUUID())).thenReturn(java.util.stream.IntStream.range(0, 8193)
            .mapToObj(i -> new WatchSpec(WatchSpec.Kind.SCORE, "saved" + i, "")).toList());
        join(owner);
        tick();
        assertTrue(payloads(owner, WatchRestoreFailedPayload.class).isEmpty());
        assertTrue(payloads(owner, WatchDefinitionsSyncPayload.class).isEmpty());
        verify(owner).sendSystemMessage(argThat(message -> message.getContents() instanceof TranslatableContents content
            && content.getKey().equals("codon.watch.restore.failed")));
        verify(watches).get(owner.getUUID());
        clearSent();
        when(watches.get(owner.getUUID())).thenReturn(List.of(WATCH));
        ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(owner.connection, server);
        join(owner);
        assertEquals(List.of(WATCH), only(owner, WatchDefinitionsSyncPayload.class).definitions());
    }

    @Test void missingWatchChannelStillInitializesAfterRevocationAndLaterCapability() {
        when(playerList.getPlayers()).thenReturn(List.of(owner));
        unsupported.get(owner).add(WatchDefinitionsSyncPayload.TYPE.id());
        join(owner);
        assertTrue(payloads(owner, WatchDefinitionsSyncPayload.class).isEmpty());
        verify(watches, never()).get(any());
        clearSent();
        when(permissions.get(owner).hasPermission(Permissions.COMMANDS_OWNER)).thenReturn(false);
        tick();
        unsupported.get(owner).clear();
        tick();
        assertTrue(sent.get(owner).isEmpty(), "New capability cannot authorize a revoked connection");
        verify(watches, never()).get(any());
        when(permissions.get(owner).hasPermission(Permissions.COMMANDS_OWNER)).thenReturn(true);
        tick();
        assertEquals(List.of(WATCH), only(owner, WatchDefinitionsSyncPayload.class).definitions());
        verify(watches).get(owner.getUUID());
        clearSent();
        tick();
        assertTrue(sent.get(owner).isEmpty());
    }

    @Test void newlyAvailableChannelsInitializeWithoutRepeatingOtherHandshakes() {
        when(playerList.getPlayers()).thenReturn(List.of(owner));
        unsupported.get(owner).add(WatchDefinitionsSyncPayload.TYPE.id());
        unsupported.get(owner).add(BreakpointDefinitionsSyncPayload.TYPE.id());
        unsupported.get(owner).add(PauseSyncPayload.TYPE.id());
        join(owner);
        only(owner, BreakpointSyncPayload.class);
        verify(watches, never()).get(any());
        clearSent();
        tick();
        assertTrue(sent.get(owner).isEmpty());
        unsupported.get(owner).clear();
        tick();
        only(owner, WatchDefinitionsSyncPayload.class);
        only(owner, BreakpointDefinitionsSyncPayload.class);
        only(owner, PauseSyncPayload.class);
        assertTrue(payloads(owner, BreakpointSyncPayload.class).isEmpty());
        clearSent();
        tick();
        assertTrue(sent.get(owner).isEmpty());
    }

    @Test void reconnectWithSameUuidStillGetsItsOwnHandshake() {
        join(owner);
        ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(owner.connection, server);
        var reconnected = player(false);
        UUID samePlayer = owner.getUUID();
        when(reconnected.getUUID()).thenReturn(samePlayer);
        when(playerList.getPlayers()).thenReturn(List.of(reconnected));
        tick();
        assertTrue(sent.get(reconnected).isEmpty(), "A reused UUID cannot inherit the old connection's authorization");
        when(permissions.get(reconnected).hasPermission(Permissions.COMMANDS_OWNER)).thenReturn(true);
        tick();
        assertEquals(List.of(WATCH), only(reconnected, WatchDefinitionsSyncPayload.class).definitions());
        clearSent();
        when(permissions.get(reconnected).hasPermission(Permissions.COMMANDS_OWNER)).thenReturn(false);
        sink.paused(SNAPSHOT);
        sink.breakpointsChanged(Set.of(BLOCK));
        assertTrue(sent.get(reconnected).isEmpty(), "Revocation takes effect before the next cleanup tick");
    }

    @Test void revocationIsRecheckedForLiveStateAndRejoin() {
        sink.paused(SNAPSHOT);
        clearSent();
        when(permissions.get(owner).hasPermission(Permissions.COMMANDS_OWNER)).thenReturn(false);
        sink.paused(SNAPSHOT);
        sink.executionFlowsCompleted(List.of(new ExecutionFlowTrace(1, LOCATION, List.of(), false)));
        sink.breakpointsChanged(Set.of(BLOCK));
        sink.sendWatchChanges(owner, 41);
        sink.sendBreakpointDefinitions(owner, List.of(DEFINITION));
        join(owner);
        assertTrue(sent.get(owner).isEmpty());
        assertTrue(sent.get(visitor).isEmpty());
        verify(watches, never()).get(any());
    }

    @Test void stepAndContinueGiveNonOwnersOnlyTerminalCleanup() {
        var state = new ClientDebuggerState();
        state.applyPause(SNAPSHOT);
        sink.stepping();
        only(owner, StepSyncPayload.class);
        only(visitor, ResumeSyncPayload.class);
        assertEquals(1, sent.get(visitor).size());
        clearSent();
        when(permissions.get(owner).hasPermission(Permissions.COMMANDS_OWNER)).thenReturn(false);
        sink.continued();
        only(owner, ResumeSyncPayload.class);
        assertEquals(1, sent.get(owner).size());
        state.applyResume();
        assertFalse(state.isPaused());
        assertFalse(state.isStepping());
        assertFalse(state.isContinuing());
        assertNull(state.snapshot());
    }

    @Test void ownerTransitionsAndTerminalResumeKeepLegacyFallback() {
        sink.continued();
        only(owner, ContinueSyncPayload.class);
        clearSent();
        unsupported.get(owner).add(StepSyncPayload.TYPE.id());
        unsupported.get(owner).add(ContinueSyncPayload.TYPE.id());
        sink.stepping();
        only(owner, ResumeSyncPayload.class);
        clearSent();
        sink.continued();
        only(owner, ResumeSyncPayload.class);
        clearSent();
        when(permissions.get(owner).hasPermission(Permissions.COMMANDS_OWNER)).thenReturn(false);
        sink.resumed();
        only(owner, ResumeSyncPayload.class);
        only(visitor, ResumeSyncPayload.class);
    }

    @Test void disconnectedAndUnsupportedClientsReceiveNothing() {
        when(owner.connection.isAcceptingMessages()).thenReturn(false);
        sink.paused(SNAPSHOT);
        sink.breakpointsChanged(Set.of(BLOCK));
        sink.executionFlowsCompleted(List.of());
        join(owner);
        sink.sendWatchChanges(owner, 41);
        sink.continued();
        sink.stepping();
        sink.resumed();
        assertTrue(sent.get(owner).isEmpty());
        clearSent();
        when(owner.connection.isAcceptingMessages()).thenReturn(true);
        networking.when(() -> ServerPlayNetworking.canSend(eq(owner), any(Identifier.class))).thenReturn(false);
        sink.paused(SNAPSHOT);
        sink.breakpointsChanged(Set.of(BLOCK));
        sink.executionFlowsCompleted(List.of());
        join(owner);
        sink.continued();
        sink.stepping();
        sink.resumed();
        assertTrue(sent.get(owner).isEmpty());
    }

    @Test void staleWatchChangesAndDisconnectTransferCleanupRemainIntact() {
        sink.paused(SNAPSHOT);
        clearSent();
        sink.sendWatchChanges(owner, 40);
        sink.sendWatchChanges(owner, 0);
        assertTrue(sent.get(owner).isEmpty());
        ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(visitor.connection, server);
        verify(watches).resetTransfer(visitor.getUUID());
    }

    @Test void oversizedLivePauseStaysPausedUntilExplicitResume() {
        var controller = mock(ExecutionController.class);
        var engine = new DebuggerEngine(new BreakpointRegistry(), new StepController(), new CallStack(), controller, sink);
        engine.toggleBlockBreakpoint(BLOCK);
        clearSent();
        var sources = Collections.nCopies(ClientboundLimits.MAX_PAUSE_SOURCES + 1, SNAPSHOT.pauseSources().getFirst());
        when(controller.parkUntil(any())).thenAnswer(call -> {
            assertTrue(engine.isPaused(), "Presentation cleanup must not resume server execution");
            assertEquals(sources, engine.currentSnapshot().pauseSources(), "Keep the server's source indices intact");
            assertUnavailablePause();
            assertTrue(sent.get(visitor).isEmpty());
            verify(visitor, never()).sendSystemMessage(any());
            engine.resume();
            assertFalse(engine.isPaused(), "Administrative resume remains available");
            return ExecutionController.ParkResult.RESUMED;
        });
        engine.onCommandStage(new CommandStageEvent(81, 0, LOCATION, SNAPSHOT.command(), () -> sources));
        verify(controller).parkUntil(any());
        assertFalse(engine.isPaused());
    }

    @Test void oversizedJoinStackUsesOwnerOnlyWarningAndCleanup() {
        var frame = new CallFrame(0, LOCATION, SNAPSHOT.command());
        when(joinEngine.currentSnapshot()).thenReturn(new PauseSnapshot(LOCATION, SNAPSHOT.command(), 0,
            Collections.nCopies(ClientboundLimits.MAX_CALL_STACK + 1, frame), SNAPSHOT.pauseSources(),
            PauseReason.BREAKPOINT, 42));
        join(owner);
        assertUnavailablePause();
        assertEquals(List.of(WATCH), only(owner, WatchDefinitionsSyncPayload.class).definitions());
        verify(joinEngine, never()).resume();
        join(visitor);
        assertTrue(sent.get(visitor).isEmpty());
        verify(visitor, never()).sendSystemMessage(any());
    }

    @Test void supportedBoundarySnapshotIsSentIntact() {
        var frame = new CallFrame(0, LOCATION, SNAPSHOT.command());
        var snapshot = new PauseSnapshot(LOCATION, SNAPSHOT.command(), 0,
            Collections.nCopies(ClientboundLimits.MAX_CALL_STACK, frame),
            Collections.nCopies(ClientboundLimits.MAX_PAUSE_SOURCES, SNAPSHOT.pauseSources().getFirst()),
            PauseReason.BREAKPOINT, 43);
        assertTrue(sink.sendPauseSnapshot(owner, snapshot));
        assertSame(snapshot, only(owner, PauseSyncPayload.class).snapshot());
        verify(owner, never()).sendSystemMessage(any());
        assertFalse(sink.sendPauseSnapshot(visitor, snapshot));
        assertTrue(sent.get(visitor).isEmpty());
    }

    private void assertUnavailablePause() {
        only(owner, ResumeSyncPayload.class);
        assertTrue(payloads(owner, PauseSyncPayload.class).isEmpty());
        assertTrue(payloads(owner, WatchChangesSyncPayload.class).isEmpty());
        verify(owner).sendSystemMessage(argThat(message ->
            message.getContents() instanceof TranslatableContents text
                && text.getKey().equals("codon.pause.snapshot_too_large")
                && text.getFallback().contains("/codon resume")));
    }

    private ServerPlayer player(boolean authorized) {
        var player = mock(ServerPlayer.class);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        var source = mock(CommandSourceStack.class);
        var permission = mock(PermissionSet.class);
        when(player.connection.isAcceptingMessages()).thenReturn(true);
        when(player.connection.getPlayer()).thenReturn(player);
        when(player.createCommandSourceStack()).thenReturn(source);
        when(source.permissions()).thenReturn(permission);
        when(permission.hasPermission(Permissions.COMMANDS_OWNER)).thenReturn(authorized);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        permissions.put(player, permission);
        sent.put(player, new ArrayList<>());
        unsupported.put(player, new HashSet<>());
        return player;
    }

    private void join(ServerPlayer player) {
        ServerPlayConnectionEvents.JOIN.invoker().onPlayReady(player.connection, null, server);
    }

    private void tick() { ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server); }

    private void clearSent() { sent.values().forEach(List::clear); }

    private <T extends CustomPacketPayload> List<T> payloads(ServerPlayer player, Class<T> type) {
        return sent.get(player).stream().filter(type::isInstance).map(type::cast).toList();
    }

    private <T extends CustomPacketPayload> T only(ServerPlayer player, Class<T> type) {
        var payloads = payloads(player, type);
        assertEquals(1, payloads.size(), type.getSimpleName());
        return payloads.getFirst();
    }
}
