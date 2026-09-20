package works.nuty.codon.network;

import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import works.nuty.codon.core.model.WatchSpec;

/** One bounded Watch value request from the authenticated client. */
public record WatchQueryPayload(long pauseId, long requestId, int sourceIndex, WatchSpec spec)
    implements CustomPacketPayload {
    public static final Type<WatchQueryPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("codon", "watch_query"));
    public static final StreamCodec<FriendlyByteBuf, WatchQueryPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeVarLong(payload.pauseId);
            buf.writeVarLong(payload.requestId);
            buf.writeVarInt(payload.sourceIndex);
            WatchSpecCodec.encode(buf, payload.spec);
        },
        buf -> new WatchQueryPayload(buf.readVarLong(), buf.readVarLong(), buf.readVarInt(), WatchSpecCodec.decode(buf))
    );

    public WatchQueryPayload {
        if (pauseId <= 0 || requestId <= 0 || sourceIndex < -1) throw new IllegalArgumentException("invalid watch request");
        spec = Objects.requireNonNull(spec, "spec");
    }

    @Override public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
