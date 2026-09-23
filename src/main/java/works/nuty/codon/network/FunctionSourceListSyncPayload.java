package works.nuty.codon.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import works.nuty.codon.core.model.FunctionId;

/** One page of the loaded function index, private to its requesting client. */
public record FunctionSourceListSyncPayload(long requestId, Status status, int offset, boolean last,
                                            List<FunctionId> functions) implements CustomPacketPayload {
    public static final int PAGE_SIZE = 128;
    public enum Status { OK, UNAUTHORIZED, ERROR }
    public static final Type<FunctionSourceListSyncPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("codon", "function_source_list_sync"));
    public static final StreamCodec<FriendlyByteBuf, FunctionSourceListSyncPayload> CODEC = StreamCodec.of((buf, p) -> {
        buf.writeVarLong(p.requestId); buf.writeEnum(p.status); buf.writeVarInt(p.offset); buf.writeBoolean(p.last);
        buf.writeVarInt(p.functions.size());
        for (FunctionId id : p.functions) FunctionSourceWire.writeId(buf, id);
    }, buf -> {
        long requestId = buf.readVarLong(); Status status = buf.readEnum(Status.class);
        int offset = buf.readVarInt(); boolean last = buf.readBoolean(); int count = buf.readVarInt();
        if (count < 0 || count > PAGE_SIZE) throw new IllegalArgumentException("invalid function source list page");
        List<FunctionId> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) ids.add(FunctionSourceWire.readId(buf));
        return new FunctionSourceListSyncPayload(requestId, status, offset, last, ids);
    });

    public FunctionSourceListSyncPayload {
        if (requestId <= 0 || offset < 0 || functions.size() > PAGE_SIZE) throw new IllegalArgumentException("invalid function source list page");
        status = Objects.requireNonNull(status, "status");
        functions = List.copyOf(Objects.requireNonNull(functions, "functions"));
        if (status != Status.OK && (!functions.isEmpty() || offset != 0 || !last))
            throw new IllegalArgumentException("non-success list page must be empty and final");
    }

    @Override public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
