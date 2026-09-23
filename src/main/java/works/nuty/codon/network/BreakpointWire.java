package works.nuty.codon.network;

import net.minecraft.network.FriendlyByteBuf;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.BreakpointCondition;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.FunctionId;
import works.nuty.codon.core.model.FunctionLocation;
import works.nuty.codon.core.model.SourceLocation;

/** Strict, bounded wire representation shared by edit requests and authoritative snapshots. */
final class BreakpointWire {
    private BreakpointWire() { }

    static void write(FriendlyByteBuf buf, BreakpointDefinition definition) {
        BreakpointTarget target = definition.target();
        switch (target.location()) {
            case SourceLocation.Block block -> {
                buf.writeByte(0);
                buf.writeUtf(block.block().dimension(), 128);
                buf.writeInt(block.block().x());
                buf.writeInt(block.block().y());
                buf.writeInt(block.block().z());
            }
            case SourceLocation.Function function -> {
                buf.writeByte(1);
                buf.writeUtf(function.location().function().namespace(), 128);
                buf.writeUtf(function.location().function().path(), 256);
                buf.writeVarInt(function.location().line());
            }
            case SourceLocation.Player ignored -> throw new IllegalArgumentException("Player breakpoint cannot be sent");
        }
        buf.writeVarInt(target.stageIndex() + 1);
        buf.writeUtf(target.commandFingerprint(), 64);
        buf.writeBoolean(definition.enabled());
        buf.writeEnum(definition.condition().kind());
        buf.writeEnum(definition.condition().comparison());
        buf.writeVarInt(definition.condition().threshold());
        buf.writeBoolean(definition.staleSource());
    }

    static BreakpointDefinition read(FriendlyByteBuf buf) {
        SourceLocation location = switch (buf.readUnsignedByte()) {
            case 0 -> {
                String dimension = buf.readUtf(128);
                yield new SourceLocation.Block(new BlockLocation(buf.readInt(), buf.readInt(), buf.readInt(), dimension));
            }
            case 1 -> new SourceLocation.Function(new FunctionLocation(
                new FunctionId(buf.readUtf(128), buf.readUtf(256)), buf.readVarInt()));
            default -> throw new IllegalArgumentException("Unknown breakpoint source kind");
        };
        int stageIndex = buf.readVarInt() - 1;
        BreakpointTarget target = new BreakpointTarget(location, stageIndex, buf.readUtf(64));
        boolean enabled = buf.readBoolean();
        BreakpointCondition condition = new BreakpointCondition(
            buf.readEnum(BreakpointCondition.Kind.class), buf.readEnum(BreakpointCondition.Comparison.class),
            buf.readVarInt());
        return new BreakpointDefinition(target, enabled, condition, buf.readBoolean());
    }
}
