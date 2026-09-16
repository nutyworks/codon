package works.nuty.bastion.client.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import works.nuty.bastion.client.state.ClientDebuggerState;
import works.nuty.bastion.network.BreakpointSyncPayload;
import works.nuty.bastion.network.PauseSyncPayload;
import works.nuty.bastion.network.ResumeSyncPayload;

/**
 * Client-side receivers for the debugger sync payloads. Each handler hops onto the client thread
 * before touching the {@link ClientDebuggerState} mirror.
 */
public final class ClientNetworking {
    private ClientNetworking() {
    }

    public static void register(ClientDebuggerState state) {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> state.reset());
        ClientPlayNetworking.registerGlobalReceiver(PauseSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> state.applyPause(payload.snapshot())));

        ClientPlayNetworking.registerGlobalReceiver(ResumeSyncPayload.TYPE, (payload, context) ->
            context.client().execute(state::applyResume));

        ClientPlayNetworking.registerGlobalReceiver(BreakpointSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> state.applyBreakpoints(payload.blocks())));
    }
}
