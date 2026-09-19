package works.nuty.codon.network;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.WatchEditorQuery;
import works.nuty.codon.core.model.WatchSpec;

import static org.junit.jupiter.api.Assertions.*;

class WatchEditorQueryCodecTest {
    @Test
    void preservesTheOptionalExecutorAndDraftFields() {
        var query = new WatchEditorQuery(WatchEditorQuery.Mode.NBT, WatchSpec.Kind.ENTITY_NBT, "", "Inventory[0]",
            UUID.randomUUID(), "apple", 32);
        assertEquals(query, WatchEditorQueryCodec.fromJson(WatchEditorQueryCodec.toJson(query)));
    }

    @Test
    void rejectsMissingFieldsAndOverlongJson() {
        assertThrows(IllegalArgumentException.class, () -> WatchEditorQueryCodec.fromJson("{}"));
        assertThrows(IllegalArgumentException.class, () -> WatchEditorQueryCodec.fromJson("x".repeat(WatchEditorQueryCodec.MAX_JSON_LENGTH + 1)));
    }
}
