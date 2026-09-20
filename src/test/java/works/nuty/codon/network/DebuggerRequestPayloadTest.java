package works.nuty.codon.network;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.NbtPage;
import works.nuty.codon.core.model.WatchEditorQuery;
import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.persistence.WatchDefinitions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DebuggerRequestPayloadTest {
    @Test
    void roundTripsWatchQueriesForCapturedStorageAndLiteralScoreHolders() {
        UUID captured = UUID.randomUUID();
        assertRoundTrip(WatchQueryPayload.CODEC,
            new WatchQueryPayload(1, 2, 3, new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Inventory[0]\\\\tag", captured)));
        assertRoundTrip(WatchQueryPayload.CODEC,
            new WatchQueryPayload(1, 2, -1, new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "example:data", "한글.path")));
        assertRoundTrip(WatchQueryPayload.CODEC,
            new WatchQueryPayload(1, 2, -1, WatchSpec.scoreHolder("points", " \"quoted\\\\holder\" ")));
    }

    @Test
    void roundTripsEditorAtGlobalPauseAndMultiPageSaves() {
        WatchEditorQuery editor = new WatchEditorQuery(WatchEditorQuery.Mode.PREVIEW, WatchSpec.Kind.SCORE,
            "points", "", null, "유니코드", 0, " \"quoted\\\\holder\" ");
        assertRoundTrip(WatchEditorQueryPayload.CODEC, new WatchEditorQueryPayload(0, 5, -1, editor));

        List<WatchSpec> definitions = List.of(new WatchSpec(WatchSpec.Kind.SCORE, "first", ""),
            new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "example:data", "values[0]"));
        assertRoundTrip(WatchSavePayload.CODEC, new WatchSavePayload(8, 0, false, definitions));
        assertRoundTrip(WatchSavePayload.CODEC, new WatchSavePayload(8, definitions.size(), true,
            List.of(WatchSpec.scoreHolder("last", " holder "))));
    }

    @Test
    void roundTripsNbtPathsWithoutTrimmingOrEscapingThem() {
        String path = "  unicode.한글[0]\\\\key  ";
        assertRoundTrip(NbtTreeQueryPayload.CODEC, new NbtTreeQueryPayload(1, 4, -1, 0, path));
    }

    @Test
    void rejectsInvalidIdentifiersAndOversizedDecodedInputs() {
        WatchSpec spec = new WatchSpec(WatchSpec.Kind.SCORE, "points", "");
        assertThrows(IllegalArgumentException.class, () -> new WatchQueryPayload(0, 1, -1, spec));
        assertThrows(IllegalArgumentException.class, () -> new WatchQueryPayload(1, 0, -1, spec));
        assertThrows(IllegalArgumentException.class, () -> new WatchEditorQueryPayload(-1, 1, -1,
            new WatchEditorQuery(WatchEditorQuery.Mode.OBJECTIVES, WatchSpec.Kind.SCORE, "", "", null, "", 0)));
        assertThrows(IllegalArgumentException.class, () -> new WatchEditorQueryPayload(0, 0, -1,
            new WatchEditorQuery(WatchEditorQuery.Mode.OBJECTIVES, WatchSpec.Kind.SCORE, "", "", null, "", 0)));
        assertThrows(IllegalArgumentException.class, () -> new WatchSavePayload(0, 0, true, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new WatchSavePayload(1, -1, true, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new NbtTreeQueryPayload(1, 1, -2, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new NbtTreeQueryPayload(0, 1, -1, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new NbtTreeQueryPayload(1, 0, -1, 0, ""));

        assertDecodeFails(WatchQueryPayload.CODEC, buf -> {
            buf.writeVarLong(0); buf.writeVarLong(1); buf.writeVarInt(-1); WatchSpecCodec.encode(buf, spec);
        });
        assertDecodeFails(WatchEditorQueryPayload.CODEC, buf -> {
            buf.writeVarLong(0); buf.writeVarLong(1); buf.writeVarInt(-1);
            buf.writeUtf("x".repeat(WatchEditorQueryCodec.MAX_JSON_LENGTH + 1));
        });
        assertDecodeFails(WatchSavePayload.CODEC, buf -> {
            buf.writeVarLong(1); buf.writeVarInt(0); buf.writeBoolean(true);
            buf.writeUtf("x".repeat(WatchDefinitions.MAX_JSON_LENGTH + 1));
        });
        assertDecodeFails(NbtTreeQueryPayload.CODEC, buf -> {
            buf.writeVarLong(1); buf.writeVarLong(1); buf.writeVarInt(-1); buf.writeVarInt(0);
            buf.writeUtf("x".repeat(NbtPage.MAX_PATH_LENGTH + 1));
        });
    }

    private static <T> void assertRoundTrip(net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, T> codec, T payload) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(buf, payload);
            assertEquals(payload, codec.decode(buf));
            assertEquals(0, buf.readableBytes());
        } finally { buf.release(); }
    }

    private static <T> void assertDecodeFails(net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, T> codec,
                                              java.util.function.Consumer<FriendlyByteBuf> write) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            write.accept(buf);
            assertThrows(RuntimeException.class, () -> codec.decode(buf));
        } finally { buf.release(); }
    }
}
