package works.nuty.bastion.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import works.nuty.bastion.core.model.NbtPage;
import works.nuty.bastion.core.model.WatchResult;

public record NbtTreeSyncPayload(long pauseId, long requestId, NbtPage page) implements CustomPacketPayload {
    public static final Type<NbtTreeSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("bastion", "nbt_tree_sync"));
    public static final StreamCodec<FriendlyByteBuf, NbtTreeSyncPayload> CODEC = StreamCodec.of((buf, p) -> {
        buf.writeVarLong(p.pauseId); buf.writeVarLong(p.requestId); buf.writeEnum(p.page.status());
        buf.writeVarInt(p.page.offset()); buf.writeVarInt(p.page.totalChildren()); buf.writeVarInt(p.page.children().size());
        for (var n : p.page.children()) { buf.writeUtf(n.name(), NbtPage.MAX_NAME_LENGTH); buf.writeUtf(n.path(), NbtPage.MAX_PATH_LENGTH); buf.writeUtf(n.preview(), NbtPage.MAX_PREVIEW_LENGTH); buf.writeBoolean(n.expandable()); }
    }, buf -> {
        long pauseId = buf.readVarLong(), requestId = buf.readVarLong();
        var status = buf.readEnum(WatchResult.Status.class); int offset = buf.readVarInt(), total = buf.readVarInt(), size = buf.readVarInt();
        if (size < 0 || size > NbtPage.PAGE_SIZE) throw new IllegalArgumentException("Invalid NBT page size");
        var nodes = new java.util.ArrayList<NbtPage.Node>();
        for (int i = 0; i < size; i++) nodes.add(new NbtPage.Node(buf.readUtf(NbtPage.MAX_NAME_LENGTH), buf.readUtf(NbtPage.MAX_PATH_LENGTH), buf.readUtf(NbtPage.MAX_PREVIEW_LENGTH), buf.readBoolean()));
        return new NbtTreeSyncPayload(pauseId, requestId, new NbtPage(status, nodes, offset, total));
    });
    @Override public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
