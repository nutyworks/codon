package works.nuty.codon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/** The saved snapshot cannot fit the restore budget; session edits must not replace it. */
public record WatchRestoreFailedPayload() implements CustomPacketPayload {
    public static final Type<WatchRestoreFailedPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("codon", "watch_restore_failed"));
    public static final StreamCodec<FriendlyByteBuf, WatchRestoreFailedPayload> CODEC =
        StreamCodec.unit(new WatchRestoreFailedPayload());

    @Override public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
