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

    private static void assertInvalid(WatchSpec.Kind kind, String target, String path) {
        assertThrows(IllegalArgumentException.class, () -> new WatchSpec(kind, target, path));
    }
}
