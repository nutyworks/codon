package works.nuty.codon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import works.nuty.codon.core.model.ExecutionFlowTrace;

import java.util.List;

/** S2C: immutable results from the most recently completed outer command execution scope. */
public record ExecutionFlowSyncPayload(List<ExecutionFlowTrace> flows) implements CustomPacketPayload {
    public static final Type<ExecutionFlowSyncPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("codon", "execution_flow_sync_v1"));

    public static final StreamCodec<FriendlyByteBuf, ExecutionFlowSyncPayload> CODEC = StreamCodec.of(
        (buf, payload) -> NetworkCodecs.writeExecutionFlows(buf, payload.flows()),
        buf -> new ExecutionFlowSyncPayload(NetworkCodecs.readExecutionFlows(buf))
    );

    public ExecutionFlowSyncPayload {
        flows = List.copyOf(flows);
    }

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
