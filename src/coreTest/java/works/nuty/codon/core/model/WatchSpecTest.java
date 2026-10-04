package works.nuty.codon.core.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchSpecTest {
    @Test void identityOrderingMatchesEqualityWithNullBindingsUnicodeAndQuotedAliases() {
        var values = java.util.List.of(new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Aa"),
            new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "BB"),
            new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "\"Aa\""),
            new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "한😀"),
            new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Aa", new UUID(0, 1)),
            WatchSpec.scoreHolder("score", " player "), WatchSpec.scoreHolder("score", "player"));
        for (var first : values) for (var second : values) {
            var a = WatchIdentity.key(first);
            var b = WatchIdentity.key(second);
            assertEquals(a.equals(b), a.compareTo(b) == 0);
            assertEquals(Integer.signum(a.compareTo(b)), -Integer.signum(b.compareTo(a)));
            assertEquals(first.equals(second), WatchIdentity.rawKey(first).compareTo(WatchIdentity.rawKey(second)) == 0);
        }
    }
    @Test
    void acceptsOnlyTheTargetAndPathShapeForEachWatchKind() {
        WatchSpec score = new WatchSpec(WatchSpec.Kind.SCORE, "  kills  ", " ");
        WatchSpec entity = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, " ", " Inventory[0].tag ");
        WatchSpec storage = new WatchSpec(WatchSpec.Kind.STORAGE_NBT, " example:data ", " value ");

        assertEquals("kills", score.target());
        assertEquals("", score.path());
        assertEquals("", entity.target());
        assertEquals("Inventory[0].tag", entity.path());
        assertEquals("example:data", storage.target());
        assertEquals("value", storage.path());
        assertEquals("minecraft:state", new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "state", "value").target());
        assertInvalid(WatchSpec.Kind.STORAGE_NBT, "demo:bad id", "value");
        assertInvalid(WatchSpec.Kind.STORAGE_NBT, "Demo:State", "value");

        assertInvalid(WatchSpec.Kind.SCORE, "", "");
        assertInvalid(WatchSpec.Kind.SCORE, "kills", "data");
        assertInvalid(WatchSpec.Kind.ENTITY_NBT, "selector", "data");
        assertInvalid(WatchSpec.Kind.ENTITY_NBT, "", "");
        assertInvalid(WatchSpec.Kind.STORAGE_NBT, "", "data");
        assertInvalid(WatchSpec.Kind.STORAGE_NBT, "example:data", "");
    }

    @Test
    void rejectsControlCharactersAndInputsBeyondWireBound() {
        assertInvalid(WatchSpec.Kind.SCORE, "kills\nnow", "");
        assertInvalid(WatchSpec.Kind.ENTITY_NBT, "", "data\u0000value");
        assertInvalid(WatchSpec.Kind.STORAGE_NBT, "example:data\u007f", "value");
        assertInvalid(WatchSpec.Kind.SCORE, "kills\u00a7c", "");

        String atLimit = "x".repeat(WatchSpec.MAX_INPUT_LENGTH);
        assertDoesNotThrow(() -> new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "a:" + "x".repeat(126), atLimit));
        assertInvalid(WatchSpec.Kind.STORAGE_NBT, "a:" + "x".repeat(127), "value");
        assertInvalid(WatchSpec.Kind.SCORE, atLimit + "x", "");
        assertInvalid(WatchSpec.Kind.ENTITY_NBT, "", atLimit + "x");
    }

    @Test
    void bindsEntityFollowingWatchesToAnOptionalExecutorButNeverStorage() {
        UUID executor = UUID.randomUUID();
        WatchSpec floating = new WatchSpec(WatchSpec.Kind.SCORE, "kills", "");
        WatchSpec pinned = floating.withExecutor(executor);

        assertTrue(pinned.isPinned());
        assertEquals(executor, pinned.executor());
        assertEquals(floating, pinned.withExecutor(null));
        assertFalse(floating.isPinned());
        assertNull(floating.executor());
        assertEquals(executor, new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health", executor).executor());

        WatchSpec storage = new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "example:data", "value");
        assertThrows(IllegalArgumentException.class,
            () -> new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "example:data", "value", executor));
        assertThrows(IllegalArgumentException.class, () -> storage.withExecutor(executor));
    }

    @Test
    void bindsScoreWatchesToNamedScoreHoldersWithoutMergingTheirIdentities() {
        WatchSpec first = WatchSpec.scoreHolder("  kills  ", "  fake player  ");
        WatchSpec second = WatchSpec.scoreHolder("kills", "other fake player");

        assertEquals("kills", first.target());
        assertEquals("  fake player  ", first.scoreHolder());
        assertFalse(WatchIdentity.same(first, WatchSpec.scoreHolder("kills", "fake player")));
        assertTrue(first.isPinned());
        assertFalse(WatchIdentity.same(first, second));
        assertFalse(WatchIdentity.sameField(first, second));
        assertEquals(new WatchSpec(WatchSpec.Kind.SCORE, "kills", ""), first.withExecutor(null));

        UUID executor = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
            () -> new WatchSpec(WatchSpec.Kind.SCORE, "kills", "", executor, "fake player"));
        assertThrows(IllegalArgumentException.class,
            () -> new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health", null, "fake player"));
        assertThrows(IllegalArgumentException.class, () -> WatchSpec.scoreHolder("kills", ""));
        assertThrows(IllegalArgumentException.class, () -> WatchSpec.scoreHolder("kills", "fake\nplayer"));
        assertThrows(IllegalArgumentException.class,
            () -> WatchSpec.scoreHolder("kills", "x".repeat(WatchSpec.MAX_INPUT_LENGTH + 1)));
    }

    private static void assertInvalid(WatchSpec.Kind kind, String target, String path) {
        assertThrows(IllegalArgumentException.class, () -> new WatchSpec(kind, target, path));
    }
}
