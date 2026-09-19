package works.nuty.codon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import works.nuty.codon.core.model.WatchChange;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

import java.util.ArrayList;
import java.util.List;

/** Bounded pages of automatic changes for one stop, sent only to debugger owners. */
public record WatchChangesSyncPayload(long pauseId, int offset, boolean last, List<WatchChange> changes)
    implements CustomPacketPayload {
    public static final int PAGE_SIZE = 32;
    public static final Type<WatchChangesSyncPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("codon", "watch_changes_v1"));
    public static final StreamCodec<FriendlyByteBuf, WatchChangesSyncPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeVarLong(payload.pauseId);
            buf.writeVarInt(payload.offset);
            buf.writeBoolean(payload.last);
            buf.writeVarInt(payload.changes.size());
            for (WatchChange change : payload.changes) {
                WatchSpec spec = change.spec();
                buf.writeEnum(spec.kind());
                buf.writeUtf(spec.target(), WatchSpec.MAX_INPUT_LENGTH);
                buf.writeUtf(spec.path(), WatchSpec.MAX_INPUT_LENGTH);
                buf.writeBoolean(spec.executor() != null);
                if (spec.executor() != null) buf.writeUUID(spec.executor());
                writeResult(buf, change.before());
                writeResult(buf, change.after());
            }
        },
        buf -> {
            long pause = buf.readVarLong();
            int offset = buf.readVarInt();
            boolean last = buf.readBoolean();
            int size = buf.readVarInt();
            if (size < 0 || size > PAGE_SIZE) throw new IllegalArgumentException("Invalid watch change page size");
            List<WatchChange> changes = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                WatchSpec spec = new WatchSpec(buf.readEnum(WatchSpec.Kind.class),
                    buf.readUtf(WatchSpec.MAX_INPUT_LENGTH), buf.readUtf(WatchSpec.MAX_INPUT_LENGTH),
                    buf.readBoolean() ? buf.readUUID() : null);
                changes.add(new WatchChange(spec, readResult(buf), readResult(buf)));
            }
            return new WatchChangesSyncPayload(pause, offset, last, changes);
        }
    );

    public WatchChangesSyncPayload {
        changes = List.copyOf(changes);
        if (pauseId <= 0 || offset < 0 || changes.size() > PAGE_SIZE || (!last && changes.isEmpty())) {
            throw new IllegalArgumentException("Invalid watch change page");
        }
    }

    private static void writeResult(FriendlyByteBuf buf, WatchResult result) {
        buf.writeEnum(result.status());
        buf.writeUtf(result.value(), WatchResult.MAX_VALUE_LENGTH);
        buf.writeUtf(result.targetKey(), WatchResult.MAX_TARGET_LENGTH);
        buf.writeUtf(result.targetName(), WatchResult.MAX_TARGET_NAME_LENGTH);
    }

    private static WatchResult readResult(FriendlyByteBuf buf) {
        return new WatchResult(buf.readEnum(WatchResult.Status.class), buf.readUtf(WatchResult.MAX_VALUE_LENGTH),
            buf.readUtf(WatchResult.MAX_TARGET_LENGTH), buf.readUtf(WatchResult.MAX_TARGET_NAME_LENGTH));
    }

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
