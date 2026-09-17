package works.nuty.bastion.client.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import works.nuty.bastion.client.camera.DebuggerFreecam;
import works.nuty.bastion.client.state.ClientDebuggerState;
import works.nuty.bastion.client.state.ClientPauseEffects;
import works.nuty.bastion.network.BreakpointSyncPayload;
import works.nuty.bastion.network.ExecutionFlowSyncPayload;
import works.nuty.bastion.network.PauseSyncPayload;
import works.nuty.bastion.network.ResumeSyncPayload;
import works.nuty.bastion.network.StepSyncPayload;

/**
 * Client-side receivers for the debugger sync payloads. Each handler hops onto the client thread
 * before touching the {@link ClientDebuggerState} mirror.
 */
public final class ClientNetworking {
    private ClientNetworking() {
    }

    public static void register(ClientDebuggerState state, DebuggerFreecam freecam, ClientPauseEffects effects) {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            state.reset();
            freecam.synchronize(client);
            effects.synchronize(client);
        });
        ClientPlayNetworking.registerGlobalReceiver(PauseSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                state.applyPause(payload.snapshot());
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

        ClientPlayNetworking.registerGlobalReceiver(BreakpointSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> state.applyBreakpoints(payload.blocks())));

        ClientPlayNetworking.registerGlobalReceiver(ExecutionFlowSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> state.applyCompletedExecutionFlows(payload.flows())));
    }
}
