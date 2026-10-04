package works.nuty.codon.network;

import java.util.Objects;
import works.nuty.codon.core.model.StagePreviewLocation;
import net.minecraft.network.FriendlyByteBuf;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.FunctionId;
import works.nuty.codon.core.model.FunctionLocation;
import works.nuty.codon.core.model.SourceLocation;

/** Bounds and serializes the small, read-only stage-preview protocol. */
final class BreakpointStagePreviewWire {
    static final int MAX_DIMENSION_LENGTH = StagePreviewLocation.MAX_DIMENSION_LENGTH;
    static final int MAX_NAMESPACE_LENGTH = StagePreviewLocation.MAX_NAMESPACE_LENGTH;
    static final int MAX_PATH_LENGTH = StagePreviewLocation.MAX_PATH_LENGTH;
    static final int MAX_COMMAND_LENGTH = 16_384;

    private BreakpointStagePreviewWire() { }

    static void writeLocation(FriendlyByteBuf buf, SourceLocation location) {
        switch (validateLocation(location)) {
            case SourceLocation.Block block -> {
                buf.writeByte(0);
                buf.writeUtf(block.block().dimension(), MAX_DIMENSION_LENGTH);
                buf.writeInt(block.block().x());
                buf.writeInt(block.block().y());
                buf.writeInt(block.block().z());
            }
            case SourceLocation.Function function -> {
                buf.writeByte(1);
                buf.writeUtf(function.location().function().namespace(), MAX_NAMESPACE_LENGTH);
                buf.writeUtf(function.location().function().path(), MAX_PATH_LENGTH);
                buf.writeVarInt(function.location().line());
            }
            case SourceLocation.Player ignored -> throw new IllegalArgumentException("stage preview requires a saved source");
        }
    }

    static SourceLocation readLocation(FriendlyByteBuf buf) {
        return switch (buf.readUnsignedByte()) {
            case 0 -> {
                String dimension = buf.readUtf(MAX_DIMENSION_LENGTH);
                yield validateLocation(new SourceLocation.Block(new BlockLocation(
                    buf.readInt(), buf.readInt(), buf.readInt(), dimension)));
            }
            case 1 -> validateLocation(new SourceLocation.Function(new FunctionLocation(
                new FunctionId(buf.readUtf(MAX_NAMESPACE_LENGTH), buf.readUtf(MAX_PATH_LENGTH)), buf.readVarInt())));
            default -> throw new IllegalArgumentException("unknown stage preview source kind");
        };
    }

    static SourceLocation validateLocation(SourceLocation location) {
        if (!StagePreviewLocation.supported(location)) throw new IllegalArgumentException("unsupported stage preview location");
        return location;
    }

    static String command(String command) {
        return bounded(command, MAX_COMMAND_LENGTH, "command");
    }

    private static String bounded(String value, int maximum, String field) {
        value = Objects.requireNonNull(value, field);
        if (value.length() > maximum || value.chars().anyMatch(c -> c < 32 || c == 127))
            throw new IllegalArgumentException("invalid stage preview " + field);
        return value;
    }
}
