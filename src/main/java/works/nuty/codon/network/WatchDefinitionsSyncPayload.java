package works.nuty.codon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.persistence.WatchDefinitions;

import java.util.List;

/** One bounded page of private world-local watch definitions, restored after its final page. */
public record WatchDefinitionsSyncPayload(long transferId, int offset, boolean last, List<WatchSpec> definitions) implements CustomPacketPayload {
    public static final Type<WatchDefinitionsSyncPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("codon", "watch_definitions_v3"));
    public static final StreamCodec<FriendlyByteBuf, WatchDefinitionsSyncPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeVarLong(payload.transferId);
            buf.writeVarInt(payload.offset);
            buf.writeBoolean(payload.last);
            buf.writeUtf(WatchDefinitions.toPageJson(payload.definitions), WatchDefinitions.MAX_JSON_LENGTH);
        },
        buf -> new WatchDefinitionsSyncPayload(buf.readVarLong(), buf.readVarInt(), buf.readBoolean(),
            WatchDefinitions.fromPageJson(buf.readUtf(WatchDefinitions.MAX_JSON_LENGTH)))
    );

    public WatchDefinitionsSyncPayload {
        if (transferId <= 0 || offset < 0) throw new IllegalArgumentException("invalid watch transfer page");
        definitions = List.copyOf(definitions);
    }

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
