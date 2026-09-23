package works.nuty.codon.network;

import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import works.nuty.codon.core.model.FunctionId;

/** Requests a single effective function source by its stable id. */
public record FunctionSourceReadRequestPayload(long requestId, FunctionId function) implements CustomPacketPayload {
    public static final Type<FunctionSourceReadRequestPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("codon", "function_source_read_request"));
    public static final StreamCodec<FriendlyByteBuf, FunctionSourceReadRequestPayload> CODEC = StreamCodec.of(
        (buf, payload) -> { buf.writeVarLong(payload.requestId); FunctionSourceWire.writeId(buf, payload.function); },
        buf -> new FunctionSourceReadRequestPayload(buf.readVarLong(), FunctionSourceWire.readId(buf))
    );

    public FunctionSourceReadRequestPayload {
        if (requestId <= 0) throw new IllegalArgumentException("invalid function source read request");
        function = Objects.requireNonNull(function, "function");
        FunctionSourceWire.validateId(function);
    }

    @Override public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
