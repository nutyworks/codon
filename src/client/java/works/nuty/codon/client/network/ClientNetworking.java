package works.nuty.codon.client.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import works.nuty.codon.client.camera.DebuggerFreecam;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientPauseEffects;
import works.nuty.codon.client.state.ClientWatchState;
import works.nuty.codon.network.BreakpointSyncPayload;
import works.nuty.codon.network.ExecutionFlowSyncPayload;
import works.nuty.codon.network.PauseSyncPayload;
import works.nuty.codon.network.ResumeSyncPayload;
import works.nuty.codon.network.StepSyncPayload;
import works.nuty.codon.network.ContinueSyncPayload;
import works.nuty.codon.network.WatchSyncPayload;
import works.nuty.codon.network.WatchChangesSyncPayload;
import works.nuty.codon.network.WatchDefinitionsSyncPayload;
import works.nuty.codon.network.NbtTreeSyncPayload;
import works.nuty.codon.network.WatchEditorSyncPayload;
import works.nuty.codon.network.WatchEditorQueryPayload;
import works.nuty.codon.network.WatchQueryPayload;
import works.nuty.codon.network.WatchSavePayload;
import works.nuty.codon.network.NbtTreeQueryPayload;
import works.nuty.codon.network.WatchSaveSyncPayload;
import works.nuty.codon.persistence.WatchDefinitions;
import works.nuty.codon.persistence.WatchDefinitionTransfer;

/**
 * Client-side receivers for the debugger sync payloads. Each handler hops onto the client thread
 * before touching the {@link ClientDebuggerState} mirror.
 */
public final class ClientNetworking {
    private static long nextWatchTransferId;
    private static ClientWatchState saveState;
    private ClientNetworking() {
    }

    public static void register(ClientDebuggerState state, DebuggerFreecam freecam, ClientPauseEffects effects) {
        WatchDefinitionTransfer joinedDefinitions = new WatchDefinitionTransfer();
        ClientPlayNetworking.registerGlobalReceiver(WatchDefinitionsSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                joinedDefinitions.accept(payload.transferId(), payload.offset(), payload.last(), payload.definitions())
                    .ifPresent(definitions -> restoreWatchDefinitions(context.client(), state, definitions));
            }));
        ClientTickEvents.END_CLIENT_TICK.register(client -> sendWatchQueries(client, state));
        ClientPlayNetworking.registerGlobalReceiver(WatchEditorSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> state.watchEditor().accept(payload.pauseId(), payload.requestId(), payload.page())));
        ClientPlayNetworking.registerGlobalReceiver(WatchSaveSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> state.watches().saveFinished(payload.transferId(), payload.status() == WatchSaveSyncPayload.Status.SAVED)));
        ClientPlayNetworking.registerGlobalReceiver(WatchSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> state.watches().accept(payload.pauseId(), payload.requestId(), payload.result())));
        ClientPlayNetworking.registerGlobalReceiver(WatchChangesSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> state.watches().acceptChanges(payload.pauseId(), payload.offset(), payload.last(), payload.changes())));
        ClientPlayNetworking.registerGlobalReceiver(NbtTreeSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> state.nbt().accept(payload.pauseId(), payload.requestId(), payload.page())));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            joinedDefinitions.reset();
            state.watches().setChangeListener(ignored -> {});
            state.reset();
            freecam.synchronize(client);
            effects.synchronize(client);
        });
        ClientPlayNetworking.registerGlobalReceiver(PauseSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                state.applyPause(payload.snapshot());
                sendWatchQueries(context.client(), state);
                freecam.synchronize(context.client());
                effects.synchronize(context.client());
            }));

        ClientPlayNetworking.registerGlobalReceiver(ResumeSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                state.applyResume();
                freecam.synchronize(context.client());
                effects.synchronize(context.client());
            }));

        ClientPlayNetworking.registerGlobalReceiver(StepSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                state.applyStep();
                freecam.synchronize(context.client());
                effects.synchronize(context.client());
            }));

        ClientPlayNetworking.registerGlobalReceiver(ContinueSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                state.applyContinue();
                freecam.synchronize(context.client());
                effects.synchronize(context.client());
            }));

        ClientPlayNetworking.registerGlobalReceiver(BreakpointSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> state.applyBreakpoints(payload.blocks())));

        ClientPlayNetworking.registerGlobalReceiver(ExecutionFlowSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> state.applyCompletedExecutionFlows(payload.flows())));
    }

    private static void restoreWatchDefinitions(Minecraft client, ClientDebuggerState state,
                                                java.util.List<works.nuty.codon.core.model.WatchSpec> definitions) {
        state.watches().restoreDefinitions(definitions);
        if (state.snapshot() != null) state.watches().paused(state.snapshot().pauseId(), state.selectedPauseSourceIndex());
        // Only attach after every join page arrives; empty startup/reset state must never erase a save.
        saveState = state.watches();
        state.watches().setChangeListener(ClientNetworking::sendWatchDefinitions);
    }

    private static void sendWatchDefinitions(java.util.List<works.nuty.codon.core.model.WatchSpec> definitions) {
        ClientWatchState watches = saveState;
        if (watches == null) return;
        Minecraft client = Minecraft.getInstance();
        long transferId = nextTransferId();
        watches.saveStarted(transferId);
        if (client.player == null) {
            watches.saveFinished(transferId, false);
            return;
        }
        int offset = 0;
        var pages = WatchDefinitions.pages(definitions);
        for (int index = 0; index < pages.size(); index++) {
            var page = pages.get(index);
            ClientPlayNetworking.send(new WatchSavePayload(transferId, offset, index == pages.size() - 1, page));
            offset += page.size();
        }
    }

    private static long nextTransferId() {
        nextWatchTransferId = nextWatchTransferId == Long.MAX_VALUE ? 1 : nextWatchTransferId + 1;
        return nextWatchTransferId;
    }

    /** Enqueue reads before a step command so even immediate input retains its before-step capture. */
    public static void sendWatchQueries(Minecraft client, ClientDebuggerState state) {
        if (client.player == null) return;
        for (var query : state.watchEditor().drainQueries()) {
            ClientPlayNetworking.send(new WatchEditorQueryPayload(query.pauseId(), query.requestId(),
                query.sourceIndex(), query.query()));
        }
        if (!state.isPaused()) return;
        for (var query : state.watches().drainQueries()) {
            var spec = query.spec();
            if (query.capturedEntity() != null) spec = spec.withExecutor(query.capturedEntity());
            ClientPlayNetworking.send(new WatchQueryPayload(query.pauseId(), query.requestId(), query.sourceIndex(), spec));
        }
        for (var query : state.nbt().drainQueries()) {
            ClientPlayNetworking.send(new NbtTreeQueryPayload(query.pauseId(), query.requestId(), query.sourceIndex(),
                query.offset(), query.path()));
        }
    }
}
