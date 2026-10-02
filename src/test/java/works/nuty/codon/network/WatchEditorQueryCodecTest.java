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
    void preservesNamedScoreHolderAndRejectsInvalidBindings() {
        var holder = new WatchEditorQuery(WatchEditorQuery.Mode.PREVIEW, WatchSpec.Kind.SCORE, "points", "",
            null, "", 0, " fake player ");
        assertEquals(" fake player ", holder.scoreHolder());
        assertEquals(holder, WatchEditorQueryCodec.fromJson(WatchEditorQueryCodec.toJson(holder)));
        assertEquals(new WatchEditorQuery(WatchEditorQuery.Mode.PREVIEW, WatchSpec.Kind.SCORE, "points", "",
                null, "", 0),
            WatchEditorQueryCodec.fromJson("{\"mode\":\"PREVIEW\",\"kind\":\"SCORE\",\"target\":\"points\",\"path\":\"\",\"search\":\"\",\"offset\":0}"));
        assertThrows(IllegalArgumentException.class,
            () -> WatchEditorQueryCodec.fromJson("{\"mode\":\"PREVIEW\",\"kind\":\"SCORE\",\"target\":\"points\",\"path\":\"\",\"scoreHolder\":1,\"search\":\"\",\"offset\":0}"));
        assertThrows(IllegalArgumentException.class,
            () -> new WatchEditorQuery(WatchEditorQuery.Mode.PREVIEW, WatchSpec.Kind.SCORE, "points", "",
                UUID.randomUUID(), "", 0, "fake player"));
        assertThrows(IllegalArgumentException.class,
            () -> new WatchEditorQuery(WatchEditorQuery.Mode.PREVIEW, WatchSpec.Kind.ENTITY_NBT, "", "Health",
                null, "", 0, "fake player"));
    }

    @Test
    void rejectsMissingFields() {
        assertThrows(IllegalArgumentException.class, () -> WatchEditorQueryCodec.fromJson("{}"));
    }

    @Test
    void acceptsTheJsonLengthLimitAndRejectsOneExtraCharacter() {
        var query = new WatchEditorQuery(WatchEditorQuery.Mode.PREVIEW, WatchSpec.Kind.SCORE, "points", "",
            null, "", 0);
        String json = WatchEditorQueryCodec.toJson(query);
        // JSON whitespace reaches the wire limit without violating any query field's limit.
        String atLimit = json + " ".repeat(WatchEditorQueryCodec.MAX_JSON_LENGTH - json.length());
        assertEquals(query, WatchEditorQueryCodec.fromJson(atLimit));
        assertThrows(IllegalArgumentException.class, () -> WatchEditorQueryCodec.fromJson(atLimit + " "));
    }
}
