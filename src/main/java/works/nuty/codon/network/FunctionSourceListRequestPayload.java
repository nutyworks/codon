package works.nuty.codon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/** Requests the current effective datapack-function index. */
public record FunctionSourceListRequestPayload(long requestId) implements CustomPacketPayload {
    public static final Type<FunctionSourceListRequestPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("codon", "function_source_list_request"));
    public static final StreamCodec<FriendlyByteBuf, FunctionSourceListRequestPayload> CODEC = StreamCodec.of(
        (buf, payload) -> buf.writeVarLong(payload.requestId),
        buf -> new FunctionSourceListRequestPayload(buf.readVarLong())
    );

    public FunctionSourceListRequestPayload {
        if (requestId <= 0) throw new IllegalArgumentException("invalid function source list request");
    }

    @Override public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
