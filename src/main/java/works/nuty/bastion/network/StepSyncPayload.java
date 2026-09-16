package works.nuty.bastion.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/** S2C: execution is stepping; retain the camera until the next pause or terminal resume. */
public record StepSyncPayload() implements CustomPacketPayload {
    public static final Type<StepSyncPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("bastion", "step_sync"));

    public static final StreamCodec<FriendlyByteBuf, StepSyncPayload> CODEC =
        StreamCodec.unit(new StepSyncPayload());

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
