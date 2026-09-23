package works.nuty.codon.client.network;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import works.nuty.codon.client.state.ClientFunctionSourceState;
import works.nuty.codon.network.FunctionSourceListRequestPayload;
import works.nuty.codon.network.FunctionSourceListSyncPayload;
import works.nuty.codon.network.FunctionSourceReadRequestPayload;
import works.nuty.codon.network.FunctionSourceReadSyncPayload;

/** Client packet wiring for one read-only source browser state. */
public final class ClientSourceBrowseNetworking {
    private ClientSourceBrowseNetworking() { }

    /** Creates the state which the caller passes to {@code FunctionSourceScreen}. */
    public static ClientFunctionSourceState register() {
        ClientFunctionSourceState state = new ClientFunctionSourceState();
        ClientPlayNetworking.registerGlobalReceiver(FunctionSourceListSyncPayload.TYPE,
            (payload, context) -> context.client().execute(() -> state.accept(new ClientFunctionSourceState.ListPage(
                payload.requestId(), listStatus(payload.status()), payload.offset(), payload.last(), payload.functions()))));
        ClientPlayNetworking.registerGlobalReceiver(FunctionSourceReadSyncPayload.TYPE,
            (payload, context) -> context.client().execute(() -> state.accept(new ClientFunctionSourceState.SourcePage(
                payload.requestId(), sourceStatus(payload.status()), payload.function(), payload.provider(), payload.revision(),
                payload.truncated(), payload.offset(), payload.last(), payload.lines()))));
        ClientTickEvents.END_CLIENT_TICK.register(client -> sendPending(client, state));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> state.reset());
        return state;
    }

    private static void sendPending(Minecraft client, ClientFunctionSourceState state) {
        if (client.player == null) return;
        for (ClientFunctionSourceState.Request request : state.drainRequests()) {
            switch (request) {
                case ClientFunctionSourceState.Request.ListFunctions list ->
                    ClientPlayNetworking.send(new FunctionSourceListRequestPayload(list.requestId()));
                case ClientFunctionSourceState.Request.ReadFunction read ->
                    ClientPlayNetworking.send(new FunctionSourceReadRequestPayload(read.requestId(), read.function()));
            }
        }
    }

    private static ClientFunctionSourceState.Status listStatus(FunctionSourceListSyncPayload.Status status) {
        return switch (status) {
            case OK -> ClientFunctionSourceState.Status.READY;
            case UNAUTHORIZED -> ClientFunctionSourceState.Status.UNAUTHORIZED;
            case ERROR -> ClientFunctionSourceState.Status.ERROR;
        };
    }

    private static ClientFunctionSourceState.Status sourceStatus(FunctionSourceReadSyncPayload.Status status) {
        return switch (status) {
            case OK -> ClientFunctionSourceState.Status.READY;
            case NOT_FOUND -> ClientFunctionSourceState.Status.NOT_FOUND;
            case UNAUTHORIZED -> ClientFunctionSourceState.Status.UNAUTHORIZED;
            case ERROR -> ClientFunctionSourceState.Status.ERROR;
        };
    }
}
