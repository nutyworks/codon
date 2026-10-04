package works.nuty.codon.client.network;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import works.nuty.codon.client.state.ClientWatchState;
import works.nuty.codon.core.model.WatchSpec;
import static org.junit.jupiter.api.Assertions.*;

class ClientWatchInitializationBudgetTest {
    @Test void countOverflowNeverReplacesLocalDefinitionsOrUploadsAPartialMerge() {
        var state = new ClientWatchState(() -> 0);
        var local = IntStream.range(0, 8192).mapToObj(i -> new WatchSpec(WatchSpec.Kind.SCORE, "local" + i, "")).toList();
        state.addAll(local);
        var remote = List.of(new WatchSpec(WatchSpec.Kind.SCORE, "remote", ""));
        List<List<WatchSpec>> saves = new ArrayList<>();
        assertFalse(state.initializeDefinitions(remote, ClientNetworking::canUploadWatchDefinitions, saves::add));
        assertEquals(local, state.definitions());
        assertEquals(ClientWatchState.SaveStatus.FAILED, state.saveStatus());
        assertTrue(saves.isEmpty());
    }

    @Test void serializedCharacterOverflowUsesTheSameBudgetAsTheActualUploader() {
        var state = new ClientWatchState(() -> 0);
        var local = definitions(0, 3000);
        var remote = definitions(3000, 6000);
        assertTrue(ClientNetworking.canUploadWatchDefinitions(local));
        assertTrue(ClientNetworking.canUploadWatchDefinitions(remote));
        state.addAll(local);
        List<List<WatchSpec>> saves = new ArrayList<>();
        assertFalse(state.initializeDefinitions(remote, ClientNetworking::canUploadWatchDefinitions, saves::add));
        assertEquals(local, state.definitions());
        assertEquals(ClientWatchState.SaveStatus.FAILED, state.saveStatus());
        assertTrue(saves.isEmpty());
    }

    private static List<WatchSpec> definitions(int start, int end) {
        return IntStream.range(start, end).mapToObj(i -> new WatchSpec(WatchSpec.Kind.STORAGE_NBT,
            "demo:" + "x".repeat(120), "\\".repeat(120) + i)).toList();
    }
}
