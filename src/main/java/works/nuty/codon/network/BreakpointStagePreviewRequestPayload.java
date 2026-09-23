package works.nuty.codon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import works.nuty.codon.core.model.SourceLocation;

/** Requests a server-authoritative stage preview for one saved command source. */
public record BreakpointStagePreviewRequestPayload(long requestId, SourceLocation location)
    implements CustomPacketPayload {
    public static final Type<BreakpointStagePreviewRequestPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("codon", "breakpoint_stage_preview_request"));
    public static final StreamCodec<FriendlyByteBuf, BreakpointStagePreviewRequestPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeVarLong(payload.requestId);
            BreakpointStagePreviewWire.writeLocation(buf, payload.location);
        }, buf -> new BreakpointStagePreviewRequestPayload(buf.readVarLong(), BreakpointStagePreviewWire.readLocation(buf)));

    public BreakpointStagePreviewRequestPayload {
        if (requestId <= 0) throw new IllegalArgumentException("invalid stage preview request");
        location = BreakpointStagePreviewWire.validateLocation(location);
    }

    @Override public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
