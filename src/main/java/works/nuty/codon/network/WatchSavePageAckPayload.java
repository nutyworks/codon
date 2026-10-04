package works.nuty.codon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/** Accepted nonfinal page; only WatchSaveSyncPayload confirms a durable save. */
public record WatchSavePageAckPayload(long transferId, int nextOffset) implements CustomPacketPayload {
    public static final Type<WatchSavePageAckPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("codon", "watch_save_page_ack"));
    public static final StreamCodec<FriendlyByteBuf, WatchSavePageAckPayload> CODEC = StreamCodec.of(
        (buf, payload) -> { buf.writeVarLong(payload.transferId); buf.writeVarInt(payload.nextOffset); },
        buf -> new WatchSavePageAckPayload(buf.readVarLong(), buf.readVarInt()));

    public WatchSavePageAckPayload {
        if (transferId <= 0 || nextOffset <= 0) throw new IllegalArgumentException("invalid watch page acknowledgement");
    }

    @Override public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
