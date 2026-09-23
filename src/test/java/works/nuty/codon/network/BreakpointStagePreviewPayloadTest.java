package works.nuty.codon.network;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.SourceLocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BreakpointStagePreviewPayloadTest {
    private static final SourceLocation.Block LOCATION = new SourceLocation.Block(
        new BlockLocation(3, 70, -5, "minecraft:overworld"));

    @Test
    void codecsRoundTripAReadyPreviewWithBrigadierRanges() {
        BreakpointStagePreviewRequestPayload request = new BreakpointStagePreviewRequestPayload(7, LOCATION);
        BreakpointStagePreviewSyncPayload response = new BreakpointStagePreviewSyncPayload(7,
            BreakpointStagePreviewSyncPayload.Status.READY, LOCATION, "execute as @s run say ready", List.of(
                new BreakpointStagePreviewSyncPayload.StageSpan(0, 0, 13, false),
                new BreakpointStagePreviewSyncPayload.StageSpan(1, 14, 27, true)));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            BreakpointStagePreviewRequestPayload.CODEC.encode(buffer, request);
            assertEquals(request, BreakpointStagePreviewRequestPayload.CODEC.decode(buffer));
            buffer.clear();
            BreakpointStagePreviewSyncPayload.CODEC.encode(buffer, response);
            assertEquals(response, BreakpointStagePreviewSyncPayload.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsOverLimitAndIncoherentPreviewResponses() {
        assertThrows(IllegalArgumentException.class, () -> new BreakpointStagePreviewSyncPayload(1,
            BreakpointStagePreviewSyncPayload.Status.READY, LOCATION,
            "x".repeat(BreakpointStagePreviewWire.MAX_COMMAND_LENGTH + 1), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BreakpointStagePreviewSyncPayload(1,
            BreakpointStagePreviewSyncPayload.Status.NOT_FOUND, LOCATION, "say hidden", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BreakpointStagePreviewSyncPayload(1,
            BreakpointStagePreviewSyncPayload.Status.READY, LOCATION, "say ready", List.of(
                new BreakpointStagePreviewSyncPayload.StageSpan(1, 0, 9, true))));
    }
}
