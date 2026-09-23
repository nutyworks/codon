package works.nuty.codon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.service.BreakpointRegistry;

import java.util.ArrayList;
import java.util.List;

/** A bounded page of the server's complete breakpoint definitions. */
public record BreakpointDefinitionsSyncPayload(long transferId, int offset, boolean last,
                                                List<BreakpointDefinition> definitions)
    implements CustomPacketPayload {
    public static final int PAGE_SIZE = 48;
    public static final Type<BreakpointDefinitionsSyncPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("codon", "breakpoint_definitions"));
    public static final StreamCodec<FriendlyByteBuf, BreakpointDefinitionsSyncPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeVarLong(payload.transferId());
            buf.writeVarInt(payload.offset());
            buf.writeBoolean(payload.last());
            buf.writeVarInt(payload.definitions().size());
            payload.definitions().forEach(definition -> BreakpointWire.write(buf, definition));
        },
        buf -> {
            long transferId = buf.readVarLong();
            int offset = buf.readVarInt();
            boolean last = buf.readBoolean();
            int size = buf.readVarInt();
            if (size < 0 || size > PAGE_SIZE) throw new IllegalArgumentException("Invalid breakpoint page size");
            List<BreakpointDefinition> definitions = new ArrayList<>(size);
            for (int i = 0; i < size; i++) definitions.add(BreakpointWire.read(buf));
            return new BreakpointDefinitionsSyncPayload(transferId, offset, last, definitions);
        });

    public BreakpointDefinitionsSyncPayload {
        if (transferId <= 0 || offset < 0 || definitions.size() > PAGE_SIZE
            || offset > BreakpointRegistry.MAX_DEFINITIONS - definitions.size())
            throw new IllegalArgumentException("Invalid breakpoint snapshot page");
        definitions = List.copyOf(definitions);
    }

    @Override public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
