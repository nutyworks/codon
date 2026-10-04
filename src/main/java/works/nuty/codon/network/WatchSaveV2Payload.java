package works.nuty.codon.network;

import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.persistence.WatchDefinitions;

/** One bounded page in an acknowledged, stop-and-wait Watch-definition save transfer. */
public record WatchSaveV2Payload(long transferId, int offset, boolean last, List<WatchSpec> definitions)
    implements CustomPacketPayload {
    public static final Type<WatchSaveV2Payload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("codon", "watch_save_v2"));
    public static final StreamCodec<FriendlyByteBuf, WatchSaveV2Payload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeVarLong(payload.transferId);
            buf.writeVarInt(payload.offset);
            buf.writeBoolean(payload.last);
            buf.writeUtf(WatchDefinitions.toPageJson(payload.definitions), WatchDefinitions.MAX_JSON_LENGTH);
        },
        buf -> new WatchSaveV2Payload(buf.readVarLong(), buf.readVarInt(), buf.readBoolean(),
            WatchDefinitions.fromPageJson(buf.readUtf(WatchDefinitions.MAX_JSON_LENGTH)))
    );

    public WatchSaveV2Payload {
        if (transferId <= 0 || offset < 0) throw new IllegalArgumentException("invalid watch transfer page");
        definitions = List.copyOf(definitions);
        WatchDefinitions.toPageJson(definitions);
    }

    public WatchSavePayload legacyPage() { return new WatchSavePayload(transferId, offset, last, definitions); }

    @Override public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
