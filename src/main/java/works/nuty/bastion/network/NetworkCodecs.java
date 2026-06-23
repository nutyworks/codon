package works.nuty.bastion.network;

import net.minecraft.network.FriendlyByteBuf;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.CallFrame;
import works.nuty.bastion.core.model.CommandSnippet;
import works.nuty.bastion.core.model.EntityRef;
import works.nuty.bastion.core.model.FunctionId;
import works.nuty.bastion.core.model.FunctionLocation;
import works.nuty.bastion.core.model.PauseSnapshot;
import works.nuty.bastion.core.model.PauseSource;
import works.nuty.bastion.core.model.SourceLocation;
import works.nuty.bastion.core.model.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written {@link FriendlyByteBuf} (de)serialization for the core value types, so the
 * Minecraft-free core never has to depend on Minecraft's networking. This is the wire format that
 * lets the in-game UI work on a dedicated server.
 */
final class NetworkCodecs {
    private static final byte SOURCE_BLOCK = 0;
    private static final byte SOURCE_FUNCTION = 1;
    private static final byte SOURCE_PLAYER = 2;

    private NetworkCodecs() {
    }

    static void writeBlockLocation(FriendlyByteBuf buf, BlockLocation b) {
        buf.writeInt(b.x());
        buf.writeInt(b.y());
        buf.writeInt(b.z());
        buf.writeUtf(b.dimension());
    }

    static BlockLocation readBlockLocation(FriendlyByteBuf buf) {
        return new BlockLocation(buf.readInt(), buf.readInt(), buf.readInt(), buf.readUtf());
    }

    static void writeFunctionLocation(FriendlyByteBuf buf, FunctionLocation f) {
        buf.writeUtf(f.function().namespace());
        buf.writeUtf(f.function().path());
        buf.writeVarInt(f.line());
    }

    static FunctionLocation readFunctionLocation(FriendlyByteBuf buf) {
        FunctionId id = new FunctionId(buf.readUtf(), buf.readUtf());
        return new FunctionLocation(id, buf.readVarInt());
    }

    static void writeSourceLocation(FriendlyByteBuf buf, SourceLocation location) {
        switch (location) {
            case SourceLocation.Block b -> {
                buf.writeByte(SOURCE_BLOCK);
                writeBlockLocation(buf, b.block());
            }
            case SourceLocation.Function f -> {
                buf.writeByte(SOURCE_FUNCTION);
                writeFunctionLocation(buf, f.location());
            }
            case SourceLocation.Player p -> {
                buf.writeByte(SOURCE_PLAYER);
                buf.writeUUID(p.uuid());
                buf.writeUtf(p.name());
            }
        }
    }

    static SourceLocation readSourceLocation(FriendlyByteBuf buf) {
        byte tag = buf.readByte();
        return switch (tag) {
            case SOURCE_BLOCK -> new SourceLocation.Block(readBlockLocation(buf));
            case SOURCE_FUNCTION -> new SourceLocation.Function(readFunctionLocation(buf));
            case SOURCE_PLAYER -> new SourceLocation.Player(buf.readUUID(), buf.readUtf());
            default -> throw new IllegalStateException("Unknown source location tag: " + tag);
        };
    }

    static void writeCommandSnippet(FriendlyByteBuf buf, CommandSnippet snippet) {
        buf.writeUtf(snippet.text());
        buf.writeVarInt(snippet.highlightStart());
        buf.writeVarInt(snippet.highlightEnd());
    }

    static CommandSnippet readCommandSnippet(FriendlyByteBuf buf) {
        return new CommandSnippet(buf.readUtf(), buf.readVarInt(), buf.readVarInt());
    }

    static void writeCallFrame(FriendlyByteBuf buf, CallFrame frame) {
        buf.writeVarInt(frame.depth());
        writeSourceLocation(buf, frame.location());
        writeCommandSnippet(buf, frame.command());
    }

    static CallFrame readCallFrame(FriendlyByteBuf buf) {
        return new CallFrame(buf.readVarInt(), readSourceLocation(buf), readCommandSnippet(buf));
    }

    static void writePauseSource(FriendlyByteBuf buf, PauseSource source) {
        buf.writeDouble(source.anchor().x());
        buf.writeDouble(source.anchor().y());
        buf.writeDouble(source.anchor().z());
        buf.writeFloat(source.pitch());
        buf.writeFloat(source.yaw());
        EntityRef entity = source.entity();
        buf.writeBoolean(entity != null);
        if (entity != null) {
            buf.writeUUID(entity.uuid());
            buf.writeUtf(entity.name());
        }
    }

    static PauseSource readPauseSource(FriendlyByteBuf buf) {
        Vec3d anchor = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
        float pitch = buf.readFloat();
        float yaw = buf.readFloat();
        EntityRef entity = buf.readBoolean() ? new EntityRef(buf.readUUID(), buf.readUtf()) : null;
        return new PauseSource(anchor, pitch, yaw, entity);
    }

    static void writeSnapshot(FriendlyByteBuf buf, PauseSnapshot snapshot) {
        writeSourceLocation(buf, snapshot.location());
        writeCommandSnippet(buf, snapshot.command());
        buf.writeVarInt(snapshot.depth());
        buf.writeCollection(snapshot.callStack(), NetworkCodecs::writeCallFrame);
        buf.writeCollection(snapshot.pauseSources(), NetworkCodecs::writePauseSource);
    }

    static PauseSnapshot readSnapshot(FriendlyByteBuf buf) {
        SourceLocation location = readSourceLocation(buf);
        CommandSnippet command = readCommandSnippet(buf);
        int depth = buf.readVarInt();
        List<CallFrame> callStack = buf.readCollection(ArrayList::new, NetworkCodecs::readCallFrame);
        List<PauseSource> pauseSources = buf.readCollection(ArrayList::new, NetworkCodecs::readPauseSource);
        return new PauseSnapshot(location, command, depth, callStack, pauseSources);
    }

    static void writeBlockLocations(FriendlyByteBuf buf, List<BlockLocation> blocks) {
        buf.writeCollection(blocks, NetworkCodecs::writeBlockLocation);
    }

    static List<BlockLocation> readBlockLocations(FriendlyByteBuf buf) {
        return buf.readCollection(ArrayList::new, NetworkCodecs::readBlockLocation);
    }
}
