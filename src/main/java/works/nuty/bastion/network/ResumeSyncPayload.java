package works.nuty.bastion.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/** S2C: the debugger resumed; the client should hide the paused UI. */
public record ResumeSyncPayload() implements CustomPacketPayload {
    public static final Type<ResumeSyncPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("bastion", "resume_sync"));

    public static final StreamCodec<FriendlyByteBuf, ResumeSyncPayload> CODEC =
        StreamCodec.unit(new ResumeSyncPayload());

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
