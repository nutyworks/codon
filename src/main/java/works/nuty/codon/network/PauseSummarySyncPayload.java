package works.nuty.codon.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import works.nuty.codon.core.model.PauseSnapshot;

import java.util.List;

/**
 * S2C: the debugger is paused, but its full snapshot exceeds {@link ClientboundLimits}. Carries the
 * pause identity and location with empty stack/source/flow lists plus the withheld counts, so the
 * client keeps a truthful paused state and correlated controls without truncated source indices.
 */
public record PauseSummarySyncPayload(PauseSnapshot summary, int callStackSize, int pauseSourceCount)
    implements CustomPacketPayload {
    public static final Type<PauseSummarySyncPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("codon", "pause_summary_v1"));

    public static final StreamCodec<FriendlyByteBuf, PauseSummarySyncPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            NetworkCodecs.writeSnapshot(buf, payload.summary());
            buf.writeVarInt(payload.callStackSize());
            buf.writeVarInt(payload.pauseSourceCount());
        },
        buf -> {
            PauseSnapshot summary = NetworkCodecs.readSnapshot(buf);
            int callStackSize = buf.readVarInt();
            int pauseSourceCount = buf.readVarInt();
            if (!summary.callStack().isEmpty() || !summary.pauseSources().isEmpty() || !summary.executionFlows().isEmpty()
                || callStackSize < 0 || pauseSourceCount < 0) {
                throw new DecoderException("Invalid pause summary");
            }
            return new PauseSummarySyncPayload(summary, callStackSize, pauseSourceCount);
        }
    );

    /** Keep only bounded pause identity; never a truncated prefix of the withheld lists. */
    public static PauseSummarySyncPayload of(PauseSnapshot snapshot) {
        return new PauseSummarySyncPayload(new PauseSnapshot(snapshot.location(), snapshot.command(), snapshot.depth(),
            List.of(), List.of(), List.of(), snapshot.reason(), snapshot.pauseId()),
            snapshot.callStack().size(), snapshot.pauseSources().size());
    }

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
