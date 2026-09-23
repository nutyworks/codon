package works.nuty.codon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/** Per-request acknowledgement; a separate server snapshot supplies the authoritative state. */
public record BreakpointEditResultPayload(long requestId, Status status) implements CustomPacketPayload {
    public enum Status { APPLIED, STALE_SOURCE, INVALID_TARGET, NO_PERMISSION, FAILED }
    public static final Type<BreakpointEditResultPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("codon", "breakpoint_edit_result"));
    public static final StreamCodec<FriendlyByteBuf, BreakpointEditResultPayload> CODEC = StreamCodec.of(
        (buf, payload) -> { buf.writeVarLong(payload.requestId()); buf.writeEnum(payload.status()); },
        buf -> new BreakpointEditResultPayload(buf.readVarLong(), buf.readEnum(Status.class)));
    public BreakpointEditResultPayload {
        if (requestId <= 0) throw new IllegalArgumentException("Invalid breakpoint response");
    }
    @Override public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
