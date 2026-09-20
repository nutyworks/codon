package works.nuty.codon.network;

import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import works.nuty.codon.core.model.WatchEditorQuery;

/** One bounded Watch-editor read request from the authenticated client. */
public record WatchEditorQueryPayload(long pauseId, long requestId, int sourceIndex, WatchEditorQuery query)
    implements CustomPacketPayload {
    public static final Type<WatchEditorQueryPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("codon", "watch_editor_query"));
    public static final StreamCodec<FriendlyByteBuf, WatchEditorQueryPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeVarLong(payload.pauseId);
            buf.writeVarLong(payload.requestId);
            buf.writeVarInt(payload.sourceIndex);
            buf.writeUtf(WatchEditorQueryCodec.toJson(payload.query), WatchEditorQueryCodec.MAX_JSON_LENGTH);
        },
        buf -> new WatchEditorQueryPayload(buf.readVarLong(), buf.readVarLong(), buf.readVarInt(),
            WatchEditorQueryCodec.fromJson(buf.readUtf(WatchEditorQueryCodec.MAX_JSON_LENGTH)))
    );

    public WatchEditorQueryPayload {
        if (pauseId < 0 || requestId <= 0 || sourceIndex < -1) throw new IllegalArgumentException("invalid editor request");
        query = Objects.requireNonNull(query, "query");
    }

    @Override public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
