package works.nuty.bastion.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/** S2C: run to the next breakpoint, retaining freecam until the current execution finishes. */
public record ContinueSyncPayload() implements CustomPacketPayload {
    public static final Type<ContinueSyncPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("bastion", "continue_sync"));

    public static final StreamCodec<FriendlyByteBuf, ContinueSyncPayload> CODEC =
        StreamCodec.unit(new ContinueSyncPayload());

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
