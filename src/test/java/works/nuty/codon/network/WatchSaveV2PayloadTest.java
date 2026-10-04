package works.nuty.codon.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.persistence.WatchDefinitions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class WatchSaveV2PayloadTest {
    @Test
    void roundTripsVersionedPagesAndPositiveProgressAcknowledgements() {
        List<WatchSpec> definitions = List.of(new WatchSpec(WatchSpec.Kind.SCORE, "first", ""),
            new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "example:data", "한글[0]"));
        assertRoundTrip(WatchSaveV2Payload.CODEC, new WatchSaveV2Payload(8, 0, false, definitions));
        assertRoundTrip(WatchSaveV2Payload.CODEC, new WatchSaveV2Payload(8, 2, true, List.of()));
        assertRoundTrip(WatchSaveV2Payload.CODEC, new WatchSaveV2Payload(9, 0, true, List.of()));
        assertRoundTrip(WatchSavePageAckPayload.CODEC, new WatchSavePageAckPayload(8, 2));
        assertNotEquals(WatchSavePayload.TYPE.id(), WatchSaveV2Payload.TYPE.id());
        assertEquals("codon:watch_save_v2", WatchSaveV2Payload.TYPE.id().toString());
        assertEquals("codon:watch_save_page_ack", WatchSavePageAckPayload.TYPE.id().toString());
    }

    @Test
    void versionedPageOwnsAnImmutableSnapshot() {
        WatchSpec definition = new WatchSpec(WatchSpec.Kind.SCORE, "first", "");
        List<WatchSpec> definitions = new ArrayList<>(List.of(definition));
        WatchSaveV2Payload page = new WatchSaveV2Payload(1, 0, true, definitions);
        definitions.clear();
        assertEquals(List.of(definition), page.definitions());
        assertThrows(UnsupportedOperationException.class, () -> page.definitions().clear());
    }

    @Test
    void rejectsNonpositiveAcknowledgementsAndInvalidOrOversizedWirePages() {
        assertThrows(IllegalArgumentException.class, () -> new WatchSavePageAckPayload(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new WatchSavePageAckPayload(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new WatchSavePageAckPayload(1, -1));
        assertThrows(IllegalArgumentException.class, () -> new WatchSaveV2Payload(0, 0, true, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new WatchSaveV2Payload(1, -1, true, List.of()));
        assertDecodeFails(WatchSavePageAckPayload.CODEC, buf -> { buf.writeVarLong(1); buf.writeVarInt(0); });
        assertDecodeFails(WatchSavePageAckPayload.CODEC, buf -> { buf.writeVarLong(0); buf.writeVarInt(1); });
        assertDecodeFails(WatchSaveV2Payload.CODEC, buf -> {
            buf.writeVarLong(1); buf.writeVarInt(0); buf.writeBoolean(true);
            buf.writeUtf("x".repeat(WatchDefinitions.MAX_JSON_LENGTH + 1));
        });
    }

    private static <T> void assertRoundTrip(StreamCodec<FriendlyByteBuf, T> codec, T payload) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(buf, payload);
            assertEquals(payload, codec.decode(buf));
            assertEquals(0, buf.readableBytes());
        } finally { buf.release(); }
    }

    private static <T> void assertDecodeFails(StreamCodec<FriendlyByteBuf, T> codec, Consumer<FriendlyByteBuf> write) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            write.accept(buf);
            assertThrows(RuntimeException.class, () -> codec.decode(buf));
        } finally { buf.release(); }
    }
}
