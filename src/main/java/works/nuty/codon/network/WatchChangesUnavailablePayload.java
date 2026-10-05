package works.nuty.codon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/** The complete automatic change set cannot be presented for this pause. */
public record WatchChangesUnavailablePayload(long pauseId, Reason reason) implements CustomPacketPayload {
    public enum Reason { TOO_LARGE, CAPTURE_FAILED }
    public static final Type<WatchChangesUnavailablePayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("codon", "watch_changes_unavailable_v1"));
    public static final StreamCodec<FriendlyByteBuf, WatchChangesUnavailablePayload> CODEC = StreamCodec.of(
        (buf, payload) -> { buf.writeVarLong(payload.pauseId()); buf.writeEnum(payload.reason()); },
        buf -> new WatchChangesUnavailablePayload(buf.readVarLong(), buf.readEnum(Reason.class)));

    public WatchChangesUnavailablePayload {
        if (pauseId <= 0) throw new IllegalArgumentException("invalid pause id");
        java.util.Objects.requireNonNull(reason);
    }

    @Override public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
