package works.nuty.codon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import works.nuty.codon.core.model.PauseSnapshot;

/** S2C: the debugger paused; carries the full {@link PauseSnapshot} for the client to render. */
public record PauseSyncPayload(PauseSnapshot snapshot) implements CustomPacketPayload {
    public static final Type<PauseSyncPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("codon", "pause_sync_v5"));

    public static final StreamCodec<FriendlyByteBuf, PauseSyncPayload> CODEC = StreamCodec.of(
        (buf, payload) -> NetworkCodecs.writeSnapshot(buf, payload.snapshot()),
        buf -> new PauseSyncPayload(NetworkCodecs.readSnapshot(buf))
    );

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
