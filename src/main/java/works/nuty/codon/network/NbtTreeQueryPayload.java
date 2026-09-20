package works.nuty.codon.network;

import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import works.nuty.codon.core.model.NbtPage;

/** One bounded NBT-tree page request from the authenticated client. */
public record NbtTreeQueryPayload(long pauseId, long requestId, int sourceIndex, int offset, String path)
    implements CustomPacketPayload {
    public static final Type<NbtTreeQueryPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("codon", "nbt_tree_query"));
    public static final StreamCodec<FriendlyByteBuf, NbtTreeQueryPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeVarLong(payload.pauseId);
            buf.writeVarLong(payload.requestId);
            buf.writeVarInt(payload.sourceIndex);
            buf.writeVarInt(payload.offset);
            buf.writeUtf(payload.path, NbtPage.MAX_PATH_LENGTH);
        },
        buf -> new NbtTreeQueryPayload(buf.readVarLong(), buf.readVarLong(), buf.readVarInt(), buf.readVarInt(),
            buf.readUtf(NbtPage.MAX_PATH_LENGTH))
    );

    public NbtTreeQueryPayload {
        if (pauseId <= 0 || requestId <= 0 || sourceIndex < -1 || offset < 0) {
            throw new IllegalArgumentException("invalid NBT tree request");
        }
        path = Objects.requireNonNull(path, "path");
        if (path.length() > NbtPage.MAX_PATH_LENGTH) throw new IllegalArgumentException("NBT path too long");
    }

    @Override public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
