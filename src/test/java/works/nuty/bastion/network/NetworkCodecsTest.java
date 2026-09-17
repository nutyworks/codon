package works.nuty.bastion.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.CommandSnippet;
import works.nuty.bastion.core.model.ExecutionFlowContext;
import works.nuty.bastion.core.model.ExecutionFlowEdge;
import works.nuty.bastion.core.model.ExecutionFlowStage;
import works.nuty.bastion.core.model.ExecutionFlowTrace;
import works.nuty.bastion.core.model.PauseReason;
import works.nuty.bastion.core.model.PauseSnapshot;
import works.nuty.bastion.core.model.PauseSource;
import works.nuty.bastion.core.model.SourceLocation;
import works.nuty.bastion.core.model.Vec3d;
import works.nuty.bastion.core.service.ExecutionFlowHistory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkCodecsTest {
    @Test
    void pausePayloadRoundTripPreservesTheBoundedExecutionGraph() {
        SourceLocation location = new SourceLocation.Block(
            new BlockLocation(2, 70, -4, "minecraft:overworld"));
        PauseSource before = source(0);
        PauseSource after = source(5);
        ExecutionFlowContext input = new ExecutionFlowContext(1, before);
        ExecutionFlowContext output = new ExecutionFlowContext(2, after);
        ExecutionFlowStage modifier = new ExecutionFlowStage(0, new CommandSnippet(
            "execute at @s run say ok", 8, 13), List.of(input), List.of(output),
            List.of(new ExecutionFlowEdge(1, 2)), List.of(), 1, 1, 0, false,
            0, 0, true, true, false);
        ExecutionFlowStage terminal = new ExecutionFlowStage(1, new CommandSnippet(
            "execute at @s run say ok", 18, 24), List.of(output), List.of(output),
            List.of(), List.of(), 1, 1, 0, true, 1, 1, true, true, false);
        PauseSnapshot expected = new PauseSnapshot(location, terminal.command(), 0, List.of(), List.of(after),
            List.of(new ExecutionFlowTrace(19, location, List.of(modifier, terminal), false)),
            PauseReason.STEP);

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
    void decoderRejectsAnExecutionTraceListAboveTheProtocolLimit() {
        SourceLocation location = new SourceLocation.Block(
            new BlockLocation(0, 64, 0, "minecraft:overworld"));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            NetworkCodecs.writeSourceLocation(buffer, location);
            NetworkCodecs.writeCommandSnippet(buffer, CommandSnippet.plain("say hi"));
            buffer.writeVarInt(0);
            buffer.writeVarInt(0);
            buffer.writeVarInt(0);
            buffer.writeVarInt(ExecutionFlowHistory.MAX_TRACES + 1);

            assertThrows(RuntimeException.class, () -> NetworkCodecs.readSnapshot(buffer));
        } finally {
            buffer.release();
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
