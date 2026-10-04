package works.nuty.codon.network;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.CallFrame;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.ExecutionFlowContext;
import works.nuty.codon.core.model.ExecutionFlowEdge;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.ExecutionFlowWarning;
import works.nuty.codon.core.model.PauseReason;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.Vec3d;
import works.nuty.codon.core.service.ExecutionFlowHistory;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkCodecsTest {
    @Test
    void boundsLiveSnapshotCountsBeforeReadingElementsAndPreservesExactLimits() {
        SourceLocation location = new SourceLocation.Block(new BlockLocation(0, 64, 0, "minecraft:overworld"));
        CommandSnippet command = CommandSnippet.plain("say boundary");
        for (boolean stack : new boolean[]{true, false}) {
            int limit = stack ? ClientboundLimits.MAX_CALL_STACK : ClientboundLimits.MAX_PAUSE_SOURCES;
            for (int count : new int[]{limit, limit + 1, Integer.MAX_VALUE, -1}) {
                FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    NetworkCodecs.writeSourceLocation(buffer, location);
                    NetworkCodecs.writeCommandSnippet(buffer, command);
                    buffer.writeVarInt(0);
                    if (!stack) buffer.writeVarInt(0);
                    buffer.writeVarInt(count);
                    int countEnd = buffer.writerIndex();
                    if (count != limit) {
                        if (count < 0) assertThrows(IllegalArgumentException.class, () -> PauseSyncPayload.CODEC.decode(buffer));
                        else assertThrows(DecoderException.class, () -> PauseSyncPayload.CODEC.decode(buffer));
                        assertEquals(countEnd, buffer.readerIndex(), "reject before reading any collection element");
                        continue;
                    }
                    for (int i = 0; i < count; i++) {
                        if (stack) NetworkCodecs.writeCallFrame(buffer, new CallFrame(i, location, command));
                        else NetworkCodecs.writePauseSource(buffer, source(i));
                    }
                    if (stack) buffer.writeVarInt(0);
                    buffer.writeVarInt(0);
                    buffer.writeEnum(PauseReason.STEP);
                    buffer.writeVarLong(1);
                    var decoded = PauseSyncPayload.CODEC.decode(buffer).snapshot();
                    assertTrue(ClientboundLimits.supports(decoded));
                    assertEquals(count, stack ? decoded.callStack().size() : decoded.pauseSources().size());
                    if (!stack) assertEquals(source(count - 1), decoded.pauseSources().getLast());
                    assertEquals(0, buffer.readableBytes());
                } finally { buffer.release(); }
            }
        }
    }

    @Test
    void boundsLegacyBreakpointsAtTheRegistryLimit() {
        int limit = works.nuty.codon.core.service.BreakpointRegistry.MAX_DEFINITIONS;
        for (int count : new int[]{limit, limit + 1, Integer.MAX_VALUE}) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeVarInt(count);
                if (count > limit) {
                    assertThrows(DecoderException.class, () -> NetworkCodecs.readBlockLocations(buffer));
                } else {
                    var blocks = IntStream.range(0, count).mapToObj(i -> new BlockLocation(i, 64, 0, "한😀")).toList();
                    blocks.forEach(block -> NetworkCodecs.writeBlockLocation(buffer, block));
                    assertEquals(blocks, NetworkCodecs.readBlockLocations(buffer));
                }
            } finally { buffer.release(); }
        }
    }

    @Test
    void unsupportedAutomaticPreviewReturnsBeforeTouchingMinecraftOrPendingState() {
        var state = new works.nuty.codon.client.state.ClientDebuggerState(() -> 0);
        for (SourceLocation location : List.of(
            new SourceLocation.Function(new works.nuty.codon.core.model.FunctionLocation(
                new works.nuty.codon.core.model.FunctionId("demo", "test"), 0)),
            new SourceLocation.Function(new works.nuty.codon.core.model.FunctionLocation(
                new works.nuty.codon.core.model.FunctionId("demo", "x".repeat(257)), 1)),
            new SourceLocation.Block(new BlockLocation(0, 0, 0, "x".repeat(129))))) {
            org.junit.jupiter.api.Assertions.assertFalse(
                works.nuty.codon.client.network.ClientNetworking.requestStagePreview(state, location));
            org.junit.jupiter.api.Assertions.assertNull(state.stagePreviews().get(location));
            assertThrows(IllegalArgumentException.class, () -> new BreakpointStagePreviewRequestPayload(1, location));
        }
    }

    @Test
    void bothPayloadsPreserveWarningCausesAndRangesWithoutRepeatingLongCommandText() {
        SourceLocation location = new SourceLocation.Block(new BlockLocation(0, 64, 0, "minecraft:overworld"));
        String command = "execute " + "positioned ~ ~ ~ ".repeat(1000) + "run say ok";
        CommandSnippet snippet = new CommandSnippet(command, 8, 24);
        ExecutionFlowStage stage = new ExecutionFlowStage(0, snippet, List.of(), List.of(), List.of(), List.of(),
            1, 1, 0, false, -1, -1, true, true, false);
        var warnings = List.of(
            new ExecutionFlowWarning(ExecutionFlowWarning.Reason.CONTEXT_LIMIT, 0, snippet, 128, "Some contexts omitted"),
            new ExecutionFlowWarning(ExecutionFlowWarning.Reason.STAGE_LIMIT, 24,
                new CommandSnippet(command, 392, 408), 24, "First omitted clause"));
        var flow = new ExecutionFlowTrace(19, location, List.of(stage), true, warnings);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            NetworkCodecs.writeFlowTrace(buffer, new ExecutionFlowTrace(19, location, List.of(stage), false));
            int withoutWarnings = buffer.writerIndex();
            buffer.clear();
            NetworkCodecs.writeFlowTrace(buffer, flow);
            assertTrue(buffer.writerIndex() - withoutWarnings < 300, "warning metadata must not repeat the full command");
            assertEquals(flow, NetworkCodecs.readFlowTrace(buffer));
            buffer.clear();
            PauseSnapshot snapshot = new PauseSnapshot(location, snippet, 0, List.of(), List.of(), List.of(flow), PauseReason.STEP, 44);
            PauseSyncPayload.CODEC.encode(buffer, new PauseSyncPayload(snapshot));
            assertEquals(snapshot, PauseSyncPayload.CODEC.decode(buffer).snapshot());
            buffer.clear();
            ExecutionFlowSyncPayload completed = new ExecutionFlowSyncPayload(List.of(flow));
            ExecutionFlowSyncPayload.CODEC.encode(buffer, completed);
            assertEquals(completed, ExecutionFlowSyncPayload.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void warningWithoutRetainedStagePreservesItsOwnCommand() {
        SourceLocation location = new SourceLocation.Block(new BlockLocation(0, 64, 0, "minecraft:overworld"));
        var flow = new ExecutionFlowTrace(20, location, List.of(), true, List.of(new ExecutionFlowWarning(
            ExecutionFlowWarning.Reason.EXECUTION_ERROR, -1, CommandSnippet.plain("return run say ok"), -1, "Queue failed")));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            NetworkCodecs.writeFlowTrace(buffer, flow);
            assertEquals(flow, NetworkCodecs.readFlowTrace(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void pausePayloadRoundTripPreservesTheBoundedExecutionGraph() {
        SourceLocation location = new SourceLocation.Block(
            new BlockLocation(2, 70, -4, "minecraft:overworld"));
        PauseSource before = source(0);
        PauseSource after = source(5);
        CommandSnippet modifierCommand = new CommandSnippet("execute at @s run say ok", 8, 13);
        ExecutionFlowContext input = new ExecutionFlowContext(1, before);
        ExecutionFlowContext output = new ExecutionFlowContext(2, after);
        ExecutionFlowStage modifier = new ExecutionFlowStage(0, modifierCommand, List.of(input), List.of(output),
            List.of(new ExecutionFlowEdge(1, 2)), List.of(), 1, 1, 0, false,
            0, 0, true, true, false, 0,
            List.of(new CallFrame(0, location, modifierCommand, 19, 0)));
        ExecutionFlowStage terminal = new ExecutionFlowStage(1, new CommandSnippet(
            "execute at @s run say ok", 18, 24), List.of(output), List.of(output),
            List.of(), List.of(), 1, 1, 0, true, 1, 1, true, true, false);
        CallFrame frame = new CallFrame(0, location, terminal.command(), 0, 1);
        PauseSnapshot expected = new PauseSnapshot(location, terminal.command(), 0, List.of(frame), List.of(after),
            List.of(new ExecutionFlowTrace(19, location, List.of(modifier, terminal), false)),
            PauseReason.STEP, 43);

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            PauseSyncPayload.CODEC.encode(buffer, new PauseSyncPayload(expected));
            assertEquals(expected, PauseSyncPayload.CODEC.decode(buffer).snapshot());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void retainsAndSynchronizesSixtyFourInvocations() {
        SourceLocation location = new SourceLocation.Block(
            new BlockLocation(0, 64, 0, "minecraft:overworld"));
        CommandSnippet command = CommandSnippet.plain("say history");
        ExecutionFlowHistory history = new ExecutionFlowHistory();
        for (int invocation = 0; invocation < 64; invocation++) {
            var recorder = history.start(invocation, location);
            long context = recorder.createContext(source(invocation));
            recorder.beginStage(command, List.of(context), 1, true);
            recorder.executionStarted();
            recorder.executionResult(true);
            recorder.finishStage(1, 0);
            history.recordCallStack(invocation, 0,
                List.of(new CallFrame(0, location, command, invocation, 0)));
        }
        List<ExecutionFlowTrace> flows = history.snapshot();
        assertEquals(64, flows.size());
        assertEquals(0, flows.getFirst().invocationId());
        assertEquals(63, flows.getLast().invocationId());
        PauseSnapshot snapshot = new PauseSnapshot(location, command, 0,
            flows.getLast().stages().getFirst().callStack(), List.of(source(63)), flows, PauseReason.STEP, 43);
        ExecutionFlowSyncPayload completed = new ExecutionFlowSyncPayload(flows);

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            PauseSyncPayload.CODEC.encode(buffer, new PauseSyncPayload(snapshot));
            assertEquals(snapshot, PauseSyncPayload.CODEC.decode(buffer).snapshot());
            assertEquals(0, buffer.readableBytes());
            buffer.clear();
            ExecutionFlowSyncPayload.CODEC.encode(buffer, completed);
            assertEquals(completed, ExecutionFlowSyncPayload.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void pauseDecoderAcceptsTheTraceLimitAndRejectsOneExtraCompleteTrace() {
        SourceLocation location = new SourceLocation.Block(
            new BlockLocation(0, 64, 0, "minecraft:overworld"));
        CommandSnippet command = CommandSnippet.plain("say hi");
        for (int count : new int[]{ExecutionFlowHistory.MAX_TRACES, ExecutionFlowHistory.MAX_TRACES + 1}) {
            List<ExecutionFlowTrace> flows = IntStream.range(0, count)
                .mapToObj(index -> new ExecutionFlowTrace(index, location, List.of(), false)).toList();
            PauseSnapshot expected = new PauseSnapshot(location, command, 0, List.of(), List.of(), flows,
                PauseReason.STEP, 43);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                NetworkCodecs.writeSourceLocation(buffer, location);
                NetworkCodecs.writeCommandSnippet(buffer, command);
                buffer.writeVarInt(0); // depth
                buffer.writeVarInt(0); // call stack
                buffer.writeVarInt(0); // pause sources
                // Bypass the encoder's list bound while retaining every trace and trailing snapshot field.
                buffer.writeVarInt(flows.size());
                for (ExecutionFlowTrace flow : flows) NetworkCodecs.writeFlowTrace(buffer, flow);
                buffer.writeEnum(expected.reason());
                buffer.writeVarLong(expected.pauseId());

                if (count == ExecutionFlowHistory.MAX_TRACES) {
                    assertEquals(expected, PauseSyncPayload.CODEC.decode(buffer).snapshot());
                    assertEquals(0, buffer.readableBytes());
                } else {
                    assertThrows(DecoderException.class, () -> PauseSyncPayload.CODEC.decode(buffer));
                }
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void completedFlowPayloadRoundTripPreservesTerminalResults() {
        SourceLocation location = new SourceLocation.Block(
            new BlockLocation(2, 70, -4, "minecraft:overworld"));
        ExecutionFlowContext context = new ExecutionFlowContext(4, source(2));
        ExecutionFlowStage terminal = new ExecutionFlowStage(0, CommandSnippet.plain("say complete"),
            List.of(context), List.of(context), List.of(), List.of(), 1, 1, 0, true,
            3, 2, true, true, false);
        ExecutionFlowSyncPayload expected = new ExecutionFlowSyncPayload(List.of(
            new ExecutionFlowTrace(33, location, List.of(terminal), false)));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ExecutionFlowSyncPayload.CODEC.encode(buffer, expected);
            assertEquals(expected, ExecutionFlowSyncPayload.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    private static PauseSource source(double x) {
        return new PauseSource(new Vec3d(x, 64, 0), 0, 90, null, "minecraft:overworld");
    }
}
