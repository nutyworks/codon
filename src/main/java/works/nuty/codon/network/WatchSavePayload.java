package works.nuty.codon.network;

import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.persistence.WatchDefinitions;

/** One bounded page in a Watch-definition save transfer. */
public record WatchSavePayload(long transferId, int offset, boolean last, List<WatchSpec> definitions)
    implements CustomPacketPayload {
    public static final Type<WatchSavePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("codon", "watch_save"));
    public static final StreamCodec<FriendlyByteBuf, WatchSavePayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeVarLong(payload.transferId);
            buf.writeVarInt(payload.offset);
            buf.writeBoolean(payload.last);
            buf.writeUtf(WatchDefinitions.toPageJson(payload.definitions), WatchDefinitions.MAX_JSON_LENGTH);
        },
        buf -> new WatchSavePayload(buf.readVarLong(), buf.readVarInt(), buf.readBoolean(),
            WatchDefinitions.fromPageJson(buf.readUtf(WatchDefinitions.MAX_JSON_LENGTH)))
    );

    public WatchSavePayload {
        if (transferId <= 0 || offset < 0) throw new IllegalArgumentException("invalid watch transfer page");
        definitions = List.copyOf(definitions);
        WatchDefinitions.toPageJson(definitions);
    }

    @Override public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
