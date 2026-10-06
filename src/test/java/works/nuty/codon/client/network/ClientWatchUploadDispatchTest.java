package works.nuty.codon.client.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.Test;
import works.nuty.codon.client.state.ClientWatchState;
import works.nuty.codon.client.state.ClientWatchUploadState;
import works.nuty.codon.core.model.TransferBudget;
import works.nuty.codon.core.model.WatchSpec;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClientWatchUploadDispatchTest {
    @Test
    void anInitialPagePreparedAfterTheDeadlineNeverReachesTheNetwork() throws Exception {
        Field uploadField = field(ClientNetworking.class, "watchUpload");
        ClientWatchUploadState upload = (ClientWatchUploadState) uploadField.get(null);
        Field lastIdField = field(ClientWatchUploadState.class, "lastTransferId");
        long previousId = lastIdField.getLong(upload);
        Field saveField = field(ClientNetworking.class, "saveState");
        Object previousSave = saveField.get(null);
        ClientWatchState watches = new ClientWatchState(System::nanoTime);
        long startedAt = System.nanoTime() - TransferBudget.TIMEOUT_NANOS;
        long id = previousId + 1;
        watches.saveStarted(id, TransferBudget.TIMEOUT_NANOS, startedAt);
        saveField.set(null, watches);
        try (var network = mockStatic(ClientPlayNetworking.class)) {
            var page = upload.begin(id, List.of(List.of(new WatchSpec(WatchSpec.Kind.SCORE, "first", ""))), startedAt);
            var send = ClientNetworking.class.getDeclaredMethod("sendWatchPage", ClientWatchUploadState.Page.class);
            send.setAccessible(true);
            send.invoke(null, page);
            network.verify(() -> ClientPlayNetworking.send(any(CustomPacketPayload.class)), never());
            assertEquals(ClientWatchState.SaveStatus.FAILED, watches.saveStatus());
            assertFalse(upload.active());
            assertEquals(0, upload.expire(), "dispatch propagates and consumes the timeout notification");
        } finally {
            upload.reset();
            lastIdField.setLong(upload, previousId);
            saveField.set(null, previousSave);
        }
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
