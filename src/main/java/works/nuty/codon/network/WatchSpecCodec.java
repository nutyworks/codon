package works.nuty.codon.network;

import net.minecraft.network.FriendlyByteBuf;
import works.nuty.codon.core.model.WatchSpec;

/** Bounded binary wire representation of a client-authored watch query. */
public final class WatchSpecCodec {
    private WatchSpecCodec() {}

    public static void encode(FriendlyByteBuf buf, WatchSpec spec) {
        buf.writeEnum(spec.kind());
        buf.writeUtf(spec.target(), WatchSpec.MAX_INPUT_LENGTH);
        buf.writeUtf(spec.path(), WatchSpec.MAX_INPUT_LENGTH);
        buf.writeBoolean(spec.executor() != null);
        if (spec.executor() != null) buf.writeUUID(spec.executor());
        buf.writeBoolean(spec.scoreHolder() != null);
        if (spec.scoreHolder() != null) buf.writeUtf(spec.scoreHolder(), WatchSpec.MAX_INPUT_LENGTH);
    }

    public static WatchSpec decode(FriendlyByteBuf buf) {
        WatchSpec.Kind kind = buf.readEnum(WatchSpec.Kind.class);
        String target = buf.readUtf(WatchSpec.MAX_INPUT_LENGTH);
        String path = buf.readUtf(WatchSpec.MAX_INPUT_LENGTH);
        java.util.UUID executor = buf.readBoolean() ? buf.readUUID() : null;
        String scoreHolder = buf.readBoolean() ? buf.readUtf(WatchSpec.MAX_INPUT_LENGTH) : null;
        return new WatchSpec(kind, target, path, executor, scoreHolder);
    }
}
