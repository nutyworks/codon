package works.nuty.codon.client.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import works.nuty.codon.client.camera.DebuggerFreecam;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientBreakpointState;
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.client.state.ClientPauseEffects;
import works.nuty.codon.client.state.ClientWatchState;
import works.nuty.codon.client.state.ClientQueryScheduler;
import works.nuty.codon.client.state.ClientWatchUploadState;
import works.nuty.codon.core.model.TransferBudget;
import works.nuty.codon.network.WatchSaveV2Payload;
import works.nuty.codon.network.WatchSavePageAckPayload;
import works.nuty.codon.network.BreakpointSyncPayload;
import works.nuty.codon.network.BreakpointDefinitionsSyncPayload;
import works.nuty.codon.network.BreakpointEditPayload;
import works.nuty.codon.network.BreakpointEditResultPayload;
import works.nuty.codon.network.BreakpointStagePreviewRequestPayload;
import works.nuty.codon.network.BreakpointStagePreviewSyncPayload;
import works.nuty.codon.network.ExecutionFlowSyncPayload;
import works.nuty.codon.network.PauseSyncPayload;
import works.nuty.codon.network.ResumeSyncPayload;
import works.nuty.codon.network.StepSyncPayload;
import works.nuty.codon.network.ContinueSyncPayload;
import works.nuty.codon.network.WatchSyncPayload;
import works.nuty.codon.network.WatchChangesSyncPayload;
import works.nuty.codon.network.WatchChangesUnavailablePayload;
import works.nuty.codon.network.WatchDefinitionsSyncPayload;
import works.nuty.codon.network.WatchRestoreFailedPayload;
import works.nuty.codon.network.NbtTreeSyncPayload;
import works.nuty.codon.network.WatchEditorSyncPayload;
import works.nuty.codon.network.WatchEditorQueryPayload;
import works.nuty.codon.network.WatchQueryPayload;
import works.nuty.codon.network.WatchSavePayload;
import works.nuty.codon.network.NbtTreeQueryPayload;
import works.nuty.codon.network.WatchSaveSyncPayload;
import works.nuty.codon.core.model.StagePreviewLocation;
import works.nuty.codon.persistence.WatchDefinitions;
import works.nuty.codon.persistence.WatchDefinitionTransfer;

/**
 * Client-side receivers for the debugger sync payloads. Each handler hops onto the client thread
 * before touching the {@link ClientDebuggerState} mirror.
 */
public final class ClientNetworking {
    private static long nextWatchTransferId;
    private static ClientWatchState saveState;
    private static final ClientWatchUploadState watchUpload = new ClientWatchUploadState(System::nanoTime);
    private static long acknowledgedTransferId;
    private static long legacyTransferId;
    private static final ClientQueryScheduler queryScheduler = new ClientQueryScheduler(System::nanoTime);
    private ClientNetworking() {
    }

    public static void register(ClientDebuggerState state, DebuggerFreecam freecam, ClientPauseEffects effects) {
        WatchDefinitionTransfer joinedDefinitions = new WatchDefinitionTransfer();
        ClientPlayNetworking.registerGlobalReceiver(WatchDefinitionsSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                if (state.watches().initialDefinitionsReceived() || state.watches().initialRestoreFailed()) return;
                joinedDefinitions.accept(payload.transferId(), payload.offset(), payload.last(), payload.definitions())
                    .ifPresent(definitions -> restoreWatchDefinitions(state, definitions));
            }));
        ClientPlayNetworking.registerGlobalReceiver(WatchRestoreFailedPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                joinedDefinitions.reset();
                state.watches().rejectInitialDefinitions();
            }));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            joinedDefinitions.isActive(); // Expire incomplete join transfers even when no further packets arrive.
            expireWatchUpload();
            sendWatchQueries(client, state);
        });
        ClientPlayNetworking.registerGlobalReceiver(WatchEditorSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                queryScheduler.editorReply(payload.pauseId(), payload.requestId());
                state.watchEditor().accept(payload.pauseId(), payload.requestId(), payload.page());
            }));
        ClientPlayNetworking.registerGlobalReceiver(WatchSaveSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                boolean saved = payload.status() == WatchSaveSyncPayload.Status.SAVED;
                if (payload.transferId() == acknowledgedTransferId) {
                    if (watchUpload.finish(payload.transferId(), saved)) state.watches().saveFinished(payload.transferId(), saved);
                    expireWatchUpload();
                } else if (payload.transferId() == legacyTransferId) state.watches().saveFinished(payload.transferId(), saved);
            }));
        ClientPlayNetworking.registerGlobalReceiver(WatchSavePageAckPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                watchUpload.acknowledge(payload.transferId(), payload.nextOffset()).ifPresent(ClientNetworking::sendWatchPage);
                expireWatchUpload();
            }));
        ClientPlayNetworking.registerGlobalReceiver(WatchSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                queryScheduler.watchReply(payload.pauseId(), payload.requestId());
                state.watches().accept(payload.pauseId(), payload.requestId(), payload.result());
            }));
        ClientPlayNetworking.registerGlobalReceiver(WatchChangesSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> state.watches().acceptChanges(payload.pauseId(), payload.offset(), payload.last(), payload.changes())));
        ClientPlayNetworking.registerGlobalReceiver(WatchChangesUnavailablePayload.TYPE, (payload, context) ->
            context.client().execute(() -> state.watches().acceptUnavailableChanges(payload.pauseId(),
                payload.reason() == WatchChangesUnavailablePayload.Reason.TOO_LARGE)));
        ClientPlayNetworking.registerGlobalReceiver(NbtTreeSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                queryScheduler.nbtReply(payload.pauseId(), payload.requestId());
                state.nbt().accept(payload.pauseId(), payload.requestId(), payload.page());
            }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            joinedDefinitions.reset();
            watchUpload.reset();
            saveState = null;
            acknowledgedTransferId = legacyTransferId = 0;
            queryScheduler.reset();
            state.watches().endConnection();
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

        ClientPlayNetworking.registerGlobalReceiver(BreakpointDefinitionsSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> state.breakpoints().acceptPage(payload.transferId(), payload.offset(),
                payload.last(), payload.definitions())));
        ClientPlayNetworking.registerGlobalReceiver(BreakpointEditResultPayload.TYPE, (payload, context) ->
            context.client().execute(() -> state.breakpoints().finish(payload.requestId(),
                ClientBreakpointState.Result.valueOf(payload.status().name()))));
        ClientPlayNetworking.registerGlobalReceiver(BreakpointStagePreviewSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> state.stagePreviews().accept(payload.requestId(), payload.location(),
                ClientStagePreviewState.Status.valueOf(payload.status().name()), payload.savedCommand(),
                payload.stages().stream().map(span -> new ClientStagePreviewState.StageSpan(span.index(),
                    span.start(), span.end(), span.terminal())).toList())));

        ClientPlayNetworking.registerGlobalReceiver(ExecutionFlowSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> state.applyCompletedExecutionFlows(payload.flows())));
    }

    private static void restoreWatchDefinitions(ClientDebuggerState state,
                                                java.util.List<works.nuty.codon.core.model.WatchSpec> definitions) {
        saveState = state.watches();
        // Initial owner sync may arrive after local Add/Edit/Delete or a pause. Merge only the
        // still-present local definitions; no empty startup echo or live-query reset is needed.
        state.watches().initializeDefinitions(definitions, ClientNetworking::canUploadWatchDefinitions,
            ClientNetworking::sendWatchDefinitions);
    }

    static boolean canUploadWatchDefinitions(java.util.List<works.nuty.codon.core.model.WatchSpec> definitions) {
        try { WatchDefinitions.validatedPages(definitions); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static void sendWatchDefinitions(java.util.List<works.nuty.codon.core.model.WatchSpec> definitions) {
        ClientWatchState watches = saveState;
        if (watches == null) return;
        Minecraft client = Minecraft.getInstance();
        long transferId = nextTransferId();
        long startedAt = System.nanoTime();
        watches.saveStarted(transferId, TransferBudget.TIMEOUT_NANOS, startedAt);
        watchUpload.reset();
        acknowledgedTransferId = legacyTransferId = 0;
        if (client.player == null) {
            watches.saveFinished(transferId, false);
            return;
        }
        try {
            var pages = WatchDefinitions.validatedPages(definitions);
            if (ClientPlayNetworking.canSend(WatchSaveV2Payload.TYPE.id())) {
                acknowledgedTransferId = transferId;
                sendWatchPage(watchUpload.begin(transferId, pages, startedAt));
            } else if (ClientPlayNetworking.canSend(WatchSavePayload.TYPE.id())) {
                legacyTransferId = transferId;
                int offset = 0;
                for (int index = 0; index < pages.size(); index++) {
                    if (watches.saveStatus() != ClientWatchState.SaveStatus.SAVING) return;
                    var page = pages.get(index);
                    ClientPlayNetworking.send(new WatchSavePayload(transferId, offset, index == pages.size() - 1, page));
                    offset += page.size();
                }
            } else watches.saveFinished(transferId, false);
        } catch (IllegalArgumentException invalid) {
            watchUpload.reset();
            watches.saveFinished(transferId, false);
        }
    }

    private static void sendWatchPage(ClientWatchUploadState.Page page) {
        if (watchUpload.transferId() != page.transferId()) {
            expireWatchUpload();
            return;
        }
        ClientPlayNetworking.send(new WatchSaveV2Payload(page.transferId(), page.offset(), page.last(), page.definitions()));
    }

    private static void expireWatchUpload() {
        long expired = watchUpload.expire();
        if (expired > 0 && saveState != null) saveState.saveFinished(expired, false);
    }

    private static long nextTransferId() {
        nextWatchTransferId = nextWatchTransferId == Long.MAX_VALUE ? 1 : nextWatchTransferId + 1;
        return nextWatchTransferId;
    }

    public static boolean sendBreakpointEdit(ClientDebuggerState state, ClientBreakpointState.Action action,
                                             works.nuty.codon.core.model.BreakpointDefinition definition) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !state.breakpoints().ready()
            || !ClientPlayNetworking.canSend(BreakpointEditPayload.TYPE.id())) return false;
        ClientBreakpointState.Edit edit = state.breakpoints().begin(action, definition);
        if (edit == null) return false;
        ClientPlayNetworking.send(new BreakpointEditPayload(edit.requestId(),
            BreakpointEditPayload.Action.valueOf(edit.action().name()), edit.definition()));
        return true;
    }

    public static boolean requestStagePreview(ClientDebuggerState state,
                                             works.nuty.codon.core.model.SourceLocation location) {
        return requestStagePreview(state, location, false);
    }

    public static boolean requestAutomaticStagePreview(ClientDebuggerState state,
                                             works.nuty.codon.core.model.SourceLocation location) {
        return requestStagePreview(state, location, true);
    }

    private static boolean requestStagePreview(ClientDebuggerState state,
                                             works.nuty.codon.core.model.SourceLocation location, boolean automatic) {
        if (!StagePreviewLocation.supported(location)) return false;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !ClientPlayNetworking.canSend(BreakpointStagePreviewRequestPayload.TYPE.id()))
            return false;
        long requestId = automatic ? state.stagePreviews().beginAutomatic(location) : state.stagePreviews().begin(location);
        if (requestId == 0) return false;
        ClientPlayNetworking.send(new BreakpointStagePreviewRequestPayload(requestId, location));
        return true;
    }

    /** Send only credited reads; a pending step waits for every Watch from its pause. */
    public static void sendWatchQueries(Minecraft client, ClientDebuggerState state) {
        if (client.player == null) return;
        queryScheduler.pump(state, sender(client));
    }

    public static void requestControl(Minecraft client, ClientDebuggerState state, long pauseId,
                                      String command, boolean readBeforeStep) {
        if (client.player == null) return;
        queryScheduler.requestControl(state, pauseId, command, readBeforeStep, sender(client));
    }

    private static ClientQueryScheduler.Sender sender(Minecraft client) {
        return new ClientQueryScheduler.Sender() {
            @Override public void editor(works.nuty.codon.client.state.ClientWatchEditorState.Query query) {
                ClientPlayNetworking.send(new WatchEditorQueryPayload(query.pauseId(), query.requestId(),
                    query.sourceIndex(), query.query()));
            }
            @Override public void watch(ClientWatchState.Query query) {
                var spec = query.capturedEntity() == null ? query.spec() : query.spec().withExecutor(query.capturedEntity());
                ClientPlayNetworking.send(new WatchQueryPayload(query.pauseId(), query.requestId(), query.sourceIndex(), spec));
            }
            @Override public void nbt(works.nuty.codon.client.state.ClientNbtState.Query query) {
                ClientPlayNetworking.send(new NbtTreeQueryPayload(query.pauseId(), query.requestId(), query.sourceIndex(),
                    query.offset(), query.path()));
            }
            @Override public void control(long pauseId, String command) {
                if (client.player != null) client.player.connection.sendCommand("codon " + command + " " + pauseId);
            }
        };
    }
}
