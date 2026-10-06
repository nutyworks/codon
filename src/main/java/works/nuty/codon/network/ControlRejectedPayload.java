package works.nuty.codon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/** Correlated rejection only. Successful advancement still uses the authoritative sync packets. */
public record ControlRejectedPayload(long pauseId, long requestId, Reason reason) implements CustomPacketPayload {
    public enum Reason { NOT_PAUSED, STALE_PAUSE }
    public static final Type<ControlRejectedPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("codon", "control_rejected"));
    public static final StreamCodec<FriendlyByteBuf, ControlRejectedPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeVarLong(payload.pauseId()); buf.writeVarLong(payload.requestId()); buf.writeEnum(payload.reason());
        },
        buf -> new ControlRejectedPayload(buf.readVarLong(), buf.readVarLong(), buf.readEnum(Reason.class)));

    public ControlRejectedPayload {
        if (pauseId <= 0 || requestId <= 0 || reason == null) throw new IllegalArgumentException("Invalid control rejection");
    }

    @Override public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
