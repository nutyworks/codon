package works.nuty.codon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import works.nuty.codon.core.model.BlockLocation;

import java.util.List;

/** S2C: the current set of block breakpoints, so the client can render their in-world markers. */
public record BreakpointSyncPayload(List<BlockLocation> blocks) implements CustomPacketPayload {
    public static final Type<BreakpointSyncPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("codon", "breakpoint_sync"));

    public static final StreamCodec<FriendlyByteBuf, BreakpointSyncPayload> CODEC = StreamCodec.of(
        (buf, payload) -> NetworkCodecs.writeBlockLocations(buf, payload.blocks()),
        buf -> new BreakpointSyncPayload(NetworkCodecs.readBlockLocations(buf))
    );

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
