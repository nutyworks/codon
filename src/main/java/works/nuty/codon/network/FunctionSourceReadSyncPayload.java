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

/** One bounded page of source text for a requested function revision. */
public record FunctionSourceReadSyncPayload(long requestId, Status status, FunctionId function, String provider,
                                            String revision, boolean truncated, int offset, boolean last,
                                            List<String> lines) implements CustomPacketPayload {
    public static final int PAGE_SIZE = 64;
    public static final int MAX_PAGE_CHARS = 300_000;
    public enum Status { OK, NOT_FOUND, UNAUTHORIZED, ERROR }
    public static final Type<FunctionSourceReadSyncPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("codon", "function_source_read_sync"));
    public static final StreamCodec<FriendlyByteBuf, FunctionSourceReadSyncPayload> CODEC = StreamCodec.of((buf, p) -> {
        buf.writeVarLong(p.requestId); buf.writeEnum(p.status); FunctionSourceWire.writeId(buf, p.function);
        buf.writeUtf(p.provider, FunctionSourceWire.MAX_PROVIDER_LENGTH);
        buf.writeUtf(p.revision, FunctionSourceWire.MAX_REVISION_LENGTH);
        buf.writeBoolean(p.truncated); buf.writeVarInt(p.offset); buf.writeBoolean(p.last); buf.writeVarInt(p.lines.size());
        for (String line : p.lines) buf.writeUtf(line, FunctionSourceWire.MAX_LINE_LENGTH);
    }, buf -> {
        long requestId = buf.readVarLong(); Status status = buf.readEnum(Status.class); FunctionId function = FunctionSourceWire.readId(buf);
        String provider = buf.readUtf(FunctionSourceWire.MAX_PROVIDER_LENGTH);
        String revision = buf.readUtf(FunctionSourceWire.MAX_REVISION_LENGTH);
        boolean truncated = buf.readBoolean(); int offset = buf.readVarInt(); boolean last = buf.readBoolean(); int count = buf.readVarInt();
        if (count < 0 || count > PAGE_SIZE) throw new IllegalArgumentException("invalid function source page");
        List<String> lines = new ArrayList<>(count);
        for (int index = 0; index < count; index++) lines.add(buf.readUtf(FunctionSourceWire.MAX_LINE_LENGTH));
        return new FunctionSourceReadSyncPayload(requestId, status, function, provider, revision, truncated, offset, last, lines);
    });

    public FunctionSourceReadSyncPayload {
        if (requestId <= 0 || offset < 0 || lines.size() > PAGE_SIZE) throw new IllegalArgumentException("invalid function source page");
        status = Objects.requireNonNull(status, "status"); function = Objects.requireNonNull(function, "function");
        provider = FunctionSourceWire.bounded(provider, FunctionSourceWire.MAX_PROVIDER_LENGTH, "provider");
        revision = FunctionSourceWire.bounded(revision, FunctionSourceWire.MAX_REVISION_LENGTH, "revision");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        int pageChars = 0;
        for (String line : lines) {
            FunctionSourceWire.bounded(line, FunctionSourceWire.MAX_LINE_LENGTH, "line");
            pageChars += line.length();
        }
        if (pageChars > MAX_PAGE_CHARS) throw new IllegalArgumentException("function source page is too large");
        if (status != Status.OK && (!provider.isEmpty() || !revision.isEmpty() || truncated || !lines.isEmpty() || offset != 0 || !last))
            throw new IllegalArgumentException("non-success source page must be empty and final");
    }

    @Override public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
