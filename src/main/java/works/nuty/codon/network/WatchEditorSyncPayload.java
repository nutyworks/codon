package works.nuty.codon.network;

import java.util.ArrayList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.WatchEditorPage;
import works.nuty.codon.core.model.WatchResult;

/** Private response to a Watch-editor query. */
public record WatchEditorSyncPayload(long pauseId, long requestId, WatchEditorPage page) implements CustomPacketPayload {
    public static final Type<WatchEditorSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("codon", "watch_editor_sync"));
    public static final StreamCodec<FriendlyByteBuf, WatchEditorSyncPayload> CODEC = StreamCodec.of((buf, p) -> {
        buf.writeVarLong(p.pauseId); buf.writeVarLong(p.requestId); buf.writeEnum(p.page.status());
        buf.writeVarInt(p.page.offset()); buf.writeBoolean(p.page.hasMore()); buf.writeBoolean(p.page.preview() != null);
        if (p.page.preview() != null) writeResult(buf, p.page.preview());
        buf.writeVarInt(p.page.options().size());
        for (var option : p.page.options()) {
            buf.writeUtf(option.value(), WatchEditorPage.MAX_VALUE_LENGTH); buf.writeUtf(option.label(), WatchEditorPage.MAX_LABEL_LENGTH);
            buf.writeUtf(option.detail(), WatchEditorPage.MAX_DETAIL_LENGTH); buf.writeBoolean(option.expandable());
        }
    }, buf -> {
        long pauseId = buf.readVarLong(), requestId = buf.readVarLong();
        WatchResult.Status status = buf.readEnum(WatchResult.Status.class); int offset = buf.readVarInt(); boolean hasMore = buf.readBoolean();
        WatchResult preview = buf.readBoolean() ? readResult(buf) : null;
        int count = buf.readVarInt();
        if (count < 0 || count > WatchEditorPage.PAGE_SIZE) throw new IllegalArgumentException("invalid editor option count");
        var options = new ArrayList<WatchEditorPage.Option>();
        for (int i = 0; i < count; i++) options.add(new WatchEditorPage.Option(buf.readUtf(WatchEditorPage.MAX_VALUE_LENGTH),
            buf.readUtf(WatchEditorPage.MAX_LABEL_LENGTH), buf.readUtf(WatchEditorPage.MAX_DETAIL_LENGTH), buf.readBoolean()));
        return new WatchEditorSyncPayload(pauseId, requestId, new WatchEditorPage(status, options, offset, hasMore, preview));
    });
    @Override public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static void writeResult(FriendlyByteBuf buf, WatchResult result) {
        buf.writeEnum(result.status()); buf.writeUtf(result.value(), WatchResult.MAX_VALUE_LENGTH);
        buf.writeUtf(result.targetKey(), WatchResult.MAX_TARGET_LENGTH); buf.writeUtf(result.targetName(), WatchResult.MAX_TARGET_NAME_LENGTH);
    }
    private static WatchResult readResult(FriendlyByteBuf buf) {
        return new WatchResult(buf.readEnum(WatchResult.Status.class), buf.readUtf(WatchResult.MAX_VALUE_LENGTH),
            buf.readUtf(WatchResult.MAX_TARGET_LENGTH), buf.readUtf(WatchResult.MAX_TARGET_NAME_LENGTH));
    }
}
