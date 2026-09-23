package works.nuty.codon.network;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.FunctionId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FunctionSourcePayloadTest {
    @Test
    void codecsRoundTripBoundedListAndSourcePages() {
        FunctionId id = new FunctionId("demo", "sub/tick");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            FunctionSourceListSyncPayload list = new FunctionSourceListSyncPayload(4,
                FunctionSourceListSyncPayload.Status.OK, 0, true, List.of(id));
            FunctionSourceListSyncPayload.CODEC.encode(buffer, list);
            assertEquals(list, FunctionSourceListSyncPayload.CODEC.decode(buffer));
            buffer.clear();
            FunctionSourceReadSyncPayload source = new FunctionSourceReadSyncPayload(5,
                FunctionSourceReadSyncPayload.Status.OK, id, "file/demo", "a".repeat(64), false,
                0, true, List.of("say one", "say two"));
            FunctionSourceReadSyncPayload.CODEC.encode(buffer, source);
            assertEquals(source, FunctionSourceReadSyncPayload.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsOversizedAndMalformedPagesBeforeTheyReachTheClientState() {
        FunctionId id = new FunctionId("demo", "tick");
        assertThrows(IllegalArgumentException.class, () -> new FunctionSourceReadSyncPayload(1,
            FunctionSourceReadSyncPayload.Status.OK, id, "", "a".repeat(64), false, 0, true,
            List.of("x".repeat(FunctionSourceWire.MAX_LINE_LENGTH + 1))));
        assertThrows(IllegalArgumentException.class, () -> new FunctionSourceListSyncPayload(1,
            FunctionSourceListSyncPayload.Status.UNAUTHORIZED, 1, true, List.of()));
    }
}
