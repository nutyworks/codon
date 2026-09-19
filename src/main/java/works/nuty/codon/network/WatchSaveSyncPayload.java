package works.nuty.codon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/** Completion acknowledgement for a complete watch-definition save transfer. */
public record WatchSaveSyncPayload(long transferId, Status status) implements CustomPacketPayload {
    public enum Status { SAVED, FAILED, INVALID }
    public static final Type<WatchSaveSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("codon", "watch_save_sync"));
    public static final StreamCodec<FriendlyByteBuf, WatchSaveSyncPayload> CODEC = StreamCodec.of(
        (buf, payload) -> { buf.writeVarLong(payload.transferId); buf.writeEnum(payload.status); },
        buf -> new WatchSaveSyncPayload(buf.readVarLong(), buf.readEnum(Status.class)));
    public WatchSaveSyncPayload { if (transferId <= 0) throw new IllegalArgumentException("invalid watch transfer"); }
    @Override public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
