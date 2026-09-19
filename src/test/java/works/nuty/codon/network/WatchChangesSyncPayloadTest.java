package works.nuty.codon.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class WatchChangesSyncPayloadTest {
    @Test
    void roundTripsAFullPageOfMaximumLengthValuesWithinThePacketBudget() {
        UUID executor = UUID.randomUUID();
        var changes = IntStream.range(0, WatchChangesSyncPayload.PAGE_SIZE).mapToObj(i -> new WatchChange(
            new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "field" + i, executor),
            new WatchResult(WatchResult.Status.VALUE, "한".repeat(WatchResult.MAX_VALUE_LENGTH), "entity:" + executor, "Pig"),
            new WatchResult(WatchResult.Status.VALUE, "글".repeat(WatchResult.MAX_VALUE_LENGTH), "entity:" + executor, "Pig"))).toList();
        var payload = new WatchChangesSyncPayload(3, 64, false, changes);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            WatchChangesSyncPayload.CODEC.encode(buf, payload);
            assertTrue(buf.readableBytes() < 1_048_576);
            assertEquals(payload, WatchChangesSyncPayload.CODEC.decode(buf));
            assertEquals(0, buf.readableBytes());
        } finally { buf.release(); }
    }

    @Test
    void rejectsUnboundedAndEmptyIntermediatePages() {
        assertThrows(IllegalArgumentException.class, () -> new WatchChangesSyncPayload(1, 0, false, List.of()));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarLong(1);
            buf.writeVarInt(0);
            buf.writeBoolean(true);
            buf.writeVarInt(WatchChangesSyncPayload.PAGE_SIZE + 1);
            assertThrows(IllegalArgumentException.class, () -> WatchChangesSyncPayload.CODEC.decode(buf));
        } finally { buf.release(); }
    }
}
