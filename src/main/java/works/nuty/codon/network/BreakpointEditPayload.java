package works.nuty.codon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import works.nuty.codon.core.model.BreakpointDefinition;

/** An authenticated player's request to toggle, save, or delete one breakpoint. */
public record BreakpointEditPayload(long requestId, Action action, BreakpointDefinition definition)
    implements CustomPacketPayload {
    public enum Action { TOGGLE, SAVE, DELETE }
    public static final Type<BreakpointEditPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("codon", "breakpoint_edit"));
    public static final StreamCodec<FriendlyByteBuf, BreakpointEditPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeVarLong(payload.requestId());
            buf.writeEnum(payload.action());
            BreakpointWire.write(buf, payload.definition());
        },
        buf -> new BreakpointEditPayload(buf.readVarLong(), buf.readEnum(Action.class), BreakpointWire.read(buf)));

    public BreakpointEditPayload {
        if (requestId <= 0) throw new IllegalArgumentException("Invalid breakpoint request");
    }

    @Override public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
