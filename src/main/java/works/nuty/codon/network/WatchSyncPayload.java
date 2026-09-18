package works.nuty.codon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import works.nuty.codon.core.model.WatchResult;

/** A private reply to one authenticated debugger user's read-only watch query. */
public record WatchSyncPayload(long pauseId, long requestId, WatchResult result) implements CustomPacketPayload {
    public static final Type<WatchSyncPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("codon", "watch_sync_v2"));
    public static final StreamCodec<FriendlyByteBuf, WatchSyncPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeVarLong(payload.pauseId);
            buf.writeVarLong(payload.requestId);
            buf.writeEnum(payload.result.status());
            buf.writeUtf(payload.result.value(), WatchResult.MAX_VALUE_LENGTH);
            buf.writeUtf(payload.result.targetKey(), WatchResult.MAX_TARGET_LENGTH);
            buf.writeUtf(payload.result.targetName(), WatchResult.MAX_TARGET_NAME_LENGTH);
        },
        buf -> new WatchSyncPayload(buf.readVarLong(), buf.readVarLong(),
            new WatchResult(buf.readEnum(WatchResult.Status.class),
                buf.readUtf(WatchResult.MAX_VALUE_LENGTH), buf.readUtf(WatchResult.MAX_TARGET_LENGTH),
                buf.readUtf(WatchResult.MAX_TARGET_NAME_LENGTH)))
    );

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
