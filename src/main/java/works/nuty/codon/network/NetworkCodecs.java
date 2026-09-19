package works.nuty.codon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.CallFrame;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.EntityRef;
import works.nuty.codon.core.model.ExecutionFlowContext;
import works.nuty.codon.core.model.ExecutionFlowEdge;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.FunctionId;
import works.nuty.codon.core.model.FunctionLocation;
import works.nuty.codon.core.model.PauseReason;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.Vec3d;
import works.nuty.codon.core.service.ExecutionFlowHistory;
import works.nuty.codon.core.service.ExecutionFlowRecorder;

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

    private static final StreamCodec<FriendlyByteBuf, List<CallFrame>> CALL_STACK_CODEC =
        StreamCodec.<FriendlyByteBuf, CallFrame>of(NetworkCodecs::writeCallFrame, NetworkCodecs::readCallFrame)
            .apply(ByteBufCodecs.list());
    private static final StreamCodec<FriendlyByteBuf, List<CallFrame>> FLOW_CALL_STACK_CODEC =
        StreamCodec.<FriendlyByteBuf, CallFrame>of(NetworkCodecs::writeCallFrame, NetworkCodecs::readCallFrame)
            .apply(ByteBufCodecs.list(ExecutionFlowRecorder.MAX_STACK_FRAMES));
    private static final StreamCodec<FriendlyByteBuf, List<PauseSource>> PAUSE_SOURCES_CODEC =
        StreamCodec.<FriendlyByteBuf, PauseSource>of(NetworkCodecs::writePauseSource, NetworkCodecs::readPauseSource)
            .apply(ByteBufCodecs.list());
    private static final StreamCodec<FriendlyByteBuf, List<BlockLocation>> BLOCK_LOCATIONS_CODEC =
        StreamCodec.<FriendlyByteBuf, BlockLocation>of(NetworkCodecs::writeBlockLocation, NetworkCodecs::readBlockLocation)
            .apply(ByteBufCodecs.list());
    private static final StreamCodec<FriendlyByteBuf, List<ExecutionFlowContext>> FLOW_CONTEXTS_CODEC =
        StreamCodec.<FriendlyByteBuf, ExecutionFlowContext>of(NetworkCodecs::writeFlowContext, NetworkCodecs::readFlowContext)
            .apply(ByteBufCodecs.list(ExecutionFlowRecorder.MAX_CONTEXTS));
    private static final StreamCodec<FriendlyByteBuf, List<ExecutionFlowEdge>> FLOW_EDGES_CODEC =
        StreamCodec.<FriendlyByteBuf, ExecutionFlowEdge>of(NetworkCodecs::writeFlowEdge, NetworkCodecs::readFlowEdge)
            .apply(ByteBufCodecs.list(ExecutionFlowRecorder.MAX_EDGES));
    private static final StreamCodec<FriendlyByteBuf, List<Long>> FLOW_CONTEXT_IDS_CODEC =
        StreamCodec.<FriendlyByteBuf, Long>of(FriendlyByteBuf::writeVarLong, FriendlyByteBuf::readVarLong)
            .apply(ByteBufCodecs.list(ExecutionFlowRecorder.MAX_CONTEXTS));
    private static final StreamCodec<FriendlyByteBuf, List<ExecutionFlowStage>> FLOW_STAGES_CODEC =
        StreamCodec.<FriendlyByteBuf, ExecutionFlowStage>of(NetworkCodecs::writeFlowStage, NetworkCodecs::readFlowStage)
            .apply(ByteBufCodecs.list(ExecutionFlowRecorder.MAX_STAGES));
    private static final StreamCodec<FriendlyByteBuf, List<ExecutionFlowTrace>> FLOW_TRACES_CODEC =
        StreamCodec.<FriendlyByteBuf, ExecutionFlowTrace>of(NetworkCodecs::writeFlowTrace, NetworkCodecs::readFlowTrace)
            .apply(ByteBufCodecs.list(ExecutionFlowHistory.MAX_TRACES));

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
        buf.writeVarLong(frame.invocationId());
        buf.writeVarInt(frame.flowStageIndex());
    }

    static CallFrame readCallFrame(FriendlyByteBuf buf) {
        return new CallFrame(buf.readVarInt(), readSourceLocation(buf), readCommandSnippet(buf),
            buf.readVarLong(), buf.readVarInt());
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
        buf.writeUtf(source.dimension());
    }

    static PauseSource readPauseSource(FriendlyByteBuf buf) {
        Vec3d anchor = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
        float pitch = buf.readFloat();
        float yaw = buf.readFloat();
        EntityRef entity = buf.readBoolean() ? new EntityRef(buf.readUUID(), buf.readUtf()) : null;
        return new PauseSource(anchor, pitch, yaw, entity, buf.readUtf());
    }

    static void writeFlowContext(FriendlyByteBuf buf, ExecutionFlowContext context) {
        buf.writeVarLong(context.id());
        writePauseSource(buf, context.source());
    }

    static ExecutionFlowContext readFlowContext(FriendlyByteBuf buf) {
        return new ExecutionFlowContext(buf.readVarLong(), readPauseSource(buf));
    }

    static void writeFlowEdge(FriendlyByteBuf buf, ExecutionFlowEdge edge) {
        buf.writeVarLong(edge.inputContextId());
        buf.writeVarLong(edge.outputContextId());
    }

    static ExecutionFlowEdge readFlowEdge(FriendlyByteBuf buf) {
        return new ExecutionFlowEdge(buf.readVarLong(), buf.readVarLong());
    }

    static void writeFlowStage(FriendlyByteBuf buf, ExecutionFlowStage stage) {
        buf.writeVarInt(stage.index());
        writeCommandSnippet(buf, stage.command());
        FLOW_CONTEXTS_CODEC.encode(buf, stage.inputs());
        FLOW_CONTEXTS_CODEC.encode(buf, stage.outputs());
        FLOW_EDGES_CODEC.encode(buf, stage.edges());
        FLOW_CONTEXT_IDS_CODEC.encode(buf, stage.droppedContextIds());
        buf.writeVarInt(stage.inputCount());
        buf.writeVarInt(stage.outputCount());
        buf.writeVarInt(stage.droppedCount());
        buf.writeBoolean(stage.terminal());
        buf.writeVarInt(stage.executionCount());
        buf.writeVarInt(stage.successCount());
        buf.writeBoolean(stage.complete());
        buf.writeBoolean(stage.lineageComplete());
        buf.writeBoolean(stage.truncated());
        buf.writeVarLong(stage.observationOrder());
        FLOW_CALL_STACK_CODEC.encode(buf, stage.callStack());
    }

    static ExecutionFlowStage readFlowStage(FriendlyByteBuf buf) {
        int index = buf.readVarInt();
        CommandSnippet command = readCommandSnippet(buf);
        List<ExecutionFlowContext> inputs = FLOW_CONTEXTS_CODEC.decode(buf);
        List<ExecutionFlowContext> outputs = FLOW_CONTEXTS_CODEC.decode(buf);
        List<ExecutionFlowEdge> edges = FLOW_EDGES_CODEC.decode(buf);
        List<Long> droppedContextIds = FLOW_CONTEXT_IDS_CODEC.decode(buf);
        int inputCount = buf.readVarInt();
        int outputCount = buf.readVarInt();
        int droppedCount = buf.readVarInt();
        boolean terminal = buf.readBoolean();
        int executionCount = buf.readVarInt();
        int successCount = buf.readVarInt();
        boolean complete = buf.readBoolean();
        boolean lineageComplete = buf.readBoolean();
        boolean truncated = buf.readBoolean();
        long observationOrder = buf.readVarLong();
        List<CallFrame> callStack = FLOW_CALL_STACK_CODEC.decode(buf);
        return new ExecutionFlowStage(index, command, inputs, outputs, edges, droppedContextIds, inputCount, outputCount,
            droppedCount, terminal, executionCount, successCount, complete, lineageComplete, truncated, observationOrder,
            callStack);
    }

    static void writeFlowTrace(FriendlyByteBuf buf, ExecutionFlowTrace trace) {
        buf.writeVarLong(trace.invocationId());
        writeSourceLocation(buf, trace.location());
        FLOW_STAGES_CODEC.encode(buf, trace.stages());
        buf.writeBoolean(trace.truncated());
    }

    static ExecutionFlowTrace readFlowTrace(FriendlyByteBuf buf) {
        return new ExecutionFlowTrace(buf.readVarLong(), readSourceLocation(buf),
            FLOW_STAGES_CODEC.decode(buf), buf.readBoolean());
    }

    static void writeExecutionFlows(FriendlyByteBuf buf, List<ExecutionFlowTrace> flows) {
        FLOW_TRACES_CODEC.encode(buf, flows);
    }

    static List<ExecutionFlowTrace> readExecutionFlows(FriendlyByteBuf buf) {
        return FLOW_TRACES_CODEC.decode(buf);
    }

    static void writeSnapshot(FriendlyByteBuf buf, PauseSnapshot snapshot) {
        writeSourceLocation(buf, snapshot.location());
        writeCommandSnippet(buf, snapshot.command());
        buf.writeVarInt(snapshot.depth());
        CALL_STACK_CODEC.encode(buf, snapshot.callStack());
        PAUSE_SOURCES_CODEC.encode(buf, snapshot.pauseSources());
        FLOW_TRACES_CODEC.encode(buf, snapshot.executionFlows());
        buf.writeEnum(snapshot.reason());
        buf.writeVarLong(snapshot.pauseId());
    }

    static PauseSnapshot readSnapshot(FriendlyByteBuf buf) {
        SourceLocation location = readSourceLocation(buf);
        CommandSnippet command = readCommandSnippet(buf);
        int depth = buf.readVarInt();
        List<CallFrame> callStack = CALL_STACK_CODEC.decode(buf);
        List<PauseSource> pauseSources = PAUSE_SOURCES_CODEC.decode(buf);
        List<ExecutionFlowTrace> executionFlows = FLOW_TRACES_CODEC.decode(buf);
        PauseReason reason = buf.readEnum(PauseReason.class);
        return new PauseSnapshot(location, command, depth, callStack, pauseSources, executionFlows, reason, buf.readVarLong());
    }

    static void writeBlockLocations(FriendlyByteBuf buf, List<BlockLocation> blocks) {
        BLOCK_LOCATIONS_CODEC.encode(buf, blocks);
    }

    static List<BlockLocation> readBlockLocations(FriendlyByteBuf buf) {
        return BLOCK_LOCATIONS_CODEC.decode(buf);
    }
}
