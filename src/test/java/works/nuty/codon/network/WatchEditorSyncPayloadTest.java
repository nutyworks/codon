package works.nuty.codon.network;

import io.netty.buffer.Unpooled;
import java.util.List;
import org.junit.jupiter.api.Test;
import net.minecraft.network.FriendlyByteBuf;
import works.nuty.codon.core.model.WatchEditorPage;
import works.nuty.codon.core.model.WatchResult;

import static org.junit.jupiter.api.Assertions.*;

class WatchEditorSyncPayloadTest {
    @Test
    void roundTripsOptionsAndPreview() {
        var page = new WatchEditorPage(WatchResult.Status.VALUE, List.of(
            new WatchEditorPage.Option("Health", "Health", "20.0f", false)), 0, false,
            new WatchResult(WatchResult.Status.VALUE, "20.0f", "entity:x", "Pig"));
        var payload = new WatchEditorSyncPayload(3, 8, page);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            WatchEditorSyncPayload.CODEC.encode(buf, payload);
            assertEquals(payload, WatchEditorSyncPayload.CODEC.decode(buf));
            assertEquals(0, buf.readableBytes());
        } finally { buf.release(); }
    }

    @Test
    void rejectsPagesLargerThanTheUiBound() {
        assertThrows(IllegalArgumentException.class, () -> new WatchEditorPage(WatchResult.Status.VALUE,
            java.util.stream.IntStream.range(0, WatchEditorPage.PAGE_SIZE + 1).mapToObj(i ->
                new WatchEditorPage.Option("v" + i, "l", "", false)).toList(), 0, false, null));
    }
}
