package works.nuty.codon.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchChangesUnavailablePayloadTest {
    @Test void roundTripsThePauseIdentityAndRejectsInvalidIds() {
        for (var reason : WatchChangesUnavailablePayload.Reason.values()) {
            var payload = new WatchChangesUnavailablePayload(42, reason);
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                WatchChangesUnavailablePayload.CODEC.encode(buffer, payload);
                assertEquals(payload, WatchChangesUnavailablePayload.CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
        assertThrows(IllegalArgumentException.class,
            () -> new WatchChangesUnavailablePayload(0, WatchChangesUnavailablePayload.Reason.TOO_LARGE));
    }
}
