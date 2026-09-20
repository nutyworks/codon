package works.nuty.codon.persistence;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.WatchSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorldWatchPersistenceTest {
    private static WatchSpec score = new WatchSpec(WatchSpec.Kind.SCORE, "points", "");
    private static WatchSpec entity = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health");

    @Test void restartAndIsolation() throws Exception {
        Path dir = Files.createTempDirectory("watch"); UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var p = new WorldWatchPersistence(e -> fail(e)); p.openWorld(dir); p.save(a, List.of(score, entity)); p.save(b, List.of(entity)); p.closeWorld();
        var q = new WorldWatchPersistence(e -> fail(e)); q.openWorld(dir);
        assertEquals(List.of(score, entity), q.get(a)); assertEquals(List.of(entity), q.get(b)); q.closeWorld();
    }

    @Test void worldIsolationAcrossReopen() throws Exception {
        Path a = Files.createTempDirectory("watch-a"); Path b = Files.createTempDirectory("watch-b");
        UUID player = UUID.randomUUID();
        var p = new WorldWatchPersistence(e -> fail(e));
        p.openWorld(a); p.save(player, List.of(score)); p.closeWorld();
        p.openWorld(b); assertEquals(List.of(), p.get(player)); p.save(player, List.of(entity)); p.closeWorld();
        p.openWorld(a); assertEquals(List.of(score), p.get(player)); p.closeWorld();
    }

    @Test void lastRemovalSurvivesReopen() throws Exception {
        Path dir = Files.createTempDirectory("watch-remove"); UUID player = UUID.randomUUID();
        var p = new WorldWatchPersistence(e -> fail(e)); p.openWorld(dir); p.save(player, List.of(score)); p.save(player, List.of()); p.closeWorld();
        var q = new WorldWatchPersistence(e -> fail(e)); q.openWorld(dir); assertEquals(List.of(), q.get(player)); q.closeWorld();
    }

    @Test void corruptAndFutureFilesArePreservedAndWritesDisabled() throws Exception {
        Path dir = Files.createTempDirectory("watch-invalid"); UUID corrupt = UUID.randomUUID(), future = UUID.randomUUID(), fractional = UUID.randomUUID();
        Path folder = dir.resolve("data/codon-watches"); Files.createDirectories(folder);
        Path corruptFile = folder.resolve(corrupt + ".json"); Path futureFile = folder.resolve(future + ".json"); Path fractionalFile = folder.resolve(fractional + ".json");
        String corruptBytes = "not json"; String futureBytes = "{\"version\":99,\"watches\":[]}"; String fractionalBytes = "{\"version\":1.5,\"watches\":[]}";
        Files.writeString(corruptFile, corruptBytes); Files.writeString(futureFile, futureBytes); Files.writeString(fractionalFile, fractionalBytes);
        List<Exception> errors = new java.util.ArrayList<>(); var p = new WorldWatchPersistence(errors::add); p.openWorld(dir);
        assertEquals(List.of(), p.get(corrupt)); assertEquals(List.of(), p.get(future)); assertEquals(List.of(), p.get(fractional));
        assertFalse(p.save(corrupt, List.of(score)));
        assertFalse(p.save(future, List.of(entity)));
        assertFalse(p.save(fractional, List.of(score)));
        p.closeWorld();
        assertEquals(corruptBytes, Files.readString(corruptFile)); assertEquals(futureBytes, Files.readString(futureFile)); assertEquals(fractionalBytes, Files.readString(fractionalFile));
        assertEquals(3, errors.size());
    }

    @Test void failedWriteRemainsDirtyAndRetriesAfterRepair() throws Exception {
        Path dir = Files.createTempDirectory("watch-retry"); UUID player = UUID.randomUUID();
        Path file = dir.resolve("data/codon-watches").resolve(player + ".json");
        List<Exception> errors = new java.util.ArrayList<>(); var p = new WorldWatchPersistence(errors::add); p.openWorld(dir);
        assertEquals(List.of(), p.get(player));
        // The original file was readable/missing at load; only the subsequent write must fail.
        Files.createDirectories(file);
        assertFalse(p.save(player, List.of(score))); assertFalse(errors.isEmpty());
        Files.delete(file); p.flush(); p.closeWorld();
        var q = new WorldWatchPersistence(e -> fail(e)); q.openWorld(dir); assertEquals(List.of(score), q.get(player)); q.closeWorld();
    }

    @Test void validationRejectsDuplicatesButNotLargeDefinitionSets() {
        assertThrows(IllegalArgumentException.class, () -> WatchDefinitions.decode(WatchDefinitions.encode(List.of(score, score))));
        assertThrows(IllegalArgumentException.class, () -> WatchDefinitions.fromJson("[{\"kind\":\"SCORE\"}]"));
        List<WatchSpec> many = watches(9);
        assertEquals(many, WatchDefinitions.fromJson(WatchDefinitions.toJson(many)));
        assertThrows(IllegalArgumentException.class, () -> WatchDefinitions.fromJson("[{\"kind\":\"UNKNOWN\",\"target\":\"x\",\"path\":\"\"}]"));
        assertThrows(IllegalArgumentException.class, () -> WatchDefinitions.fromJson("[{\"kind\":\"SCORE\",\"target\":1,\"path\":\"\"}]"));
        assertThrows(IllegalArgumentException.class, () -> WatchDefinitions.fromJson("[{\"kind\":\"SCORE\",\"target\":\"x\",\"path\":\"\",\"executor\":1}]"));
        assertThrows(IllegalArgumentException.class, () -> WatchDefinitions.fromJson("[{\"kind\":\"SCORE\",\"target\":\"x\",\"path\":\"\",\"executor\":\"1-1-1-1-1\"}]"));
        assertThrows(IllegalArgumentException.class, () -> WatchDefinitions.fromJson("[{\"kind\":\"SCORE\",\"target\":\"x\",\"path\":\"\",\"scoreHolder\":1}]"));
        assertThrows(IllegalArgumentException.class, () -> new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "minecraft:data", "x", UUID.randomUUID()));
    }

    @Test void definitionsRoundTripNamedScoreHoldersAndAcceptLegacyDefinitions() {
        WatchSpec holder = WatchSpec.scoreHolder("points", " fake player ");
        assertEquals(List.of(holder), WatchDefinitions.fromJson(WatchDefinitions.toJson(List.of(holder))));
        assertEquals(List.of(score), WatchDefinitions.fromJson("[{\"kind\":\"SCORE\",\"target\":\"points\",\"path\":\"\"}]"));
        assertThrows(IllegalArgumentException.class,
            () -> WatchDefinitions.fromJson("[{\"kind\":\"ENTITY_NBT\",\"target\":\"\",\"path\":\"Health\",\"scoreHolder\":\"fake player\"}]"));
    }

    @Test void largeDefinitionSetOverTransportLimitSurvivesDiskRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("watch-large"); UUID player = UUID.randomUUID();
        List<WatchSpec> many = watches(320);
        assertTrue(WatchDefinitions.toJson(many).length() > 32767);
        var first = new WorldWatchPersistence(e -> fail(e)); first.openWorld(dir);
        assertTrue(first.save(player, many)); first.closeWorld();
        var reopened = new WorldWatchPersistence(e -> fail(e)); reopened.openWorld(dir);
        assertEquals(many, reopened.get(player)); reopened.closeWorld();
    }

    @Test void incompleteChunkTransferDoesNotReplaceSavedDefinitions() throws Exception {
        Path dir = Files.createTempDirectory("watch-incomplete"); UUID player = UUID.randomUUID();
        var persistence = new WorldWatchPersistence(e -> fail(e)); persistence.openWorld(dir);
        assertTrue(persistence.save(player, List.of(score)));
        assertEquals(WorldWatchPersistence.ChunkSaveResult.ACCEPTED,
            persistence.saveChunk(player, 1, 0, false, List.of(entity)));
        assertEquals(List.of(score), persistence.get(player));
        persistence.closeWorld();
        var reopened = new WorldWatchPersistence(e -> fail(e)); reopened.openWorld(dir);
        assertEquals(List.of(score), reopened.get(player)); reopened.closeWorld();
    }

    @Test void jsonRoundTripPreservesFloatingAndPinnedDistinctDefinitions() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        WatchSpec floating = new WatchSpec(WatchSpec.Kind.SCORE, "points", "");
        WatchSpec pinnedA = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health", a);
        WatchSpec pinnedB = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health", b);
        String json = WatchDefinitions.toJson(List.of(floating, pinnedA, pinnedB));
        assertEquals(List.of(floating, pinnedA, pinnedB), WatchDefinitions.fromJson(json));
    }

    @Test void legacyVersionOneLoadsWithoutExecutorAndWritesVersionTwo() throws Exception {
        Path dir = Files.createTempDirectory("watch-v1"); UUID owner = UUID.randomUUID();
        Path file = dir.resolve("data/codon-watches").resolve(owner + ".json"); Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"version\":1,\"watches\":[{\"kind\":\"SCORE\",\"target\":\"points\",\"path\":\"\"}]}");
        var persistence = new WorldWatchPersistence(e -> fail(e)); persistence.openWorld(dir);
        assertEquals(List.of(score), persistence.get(owner)); assertTrue(persistence.save(owner, List.of(score))); persistence.closeWorld();
        assertTrue(Files.readString(file).contains("\"version\": 2"));
    }

    @Test void pinnedBindingsSurviveReopenAndSingleplayerOwnerChange() throws Exception {
        Path dir = Files.createTempDirectory("watch-pinned-owner"); UUID ownerA = UUID.randomUUID(), ownerB = UUID.randomUUID(), bound = UUID.randomUUID();
        WatchSpec pinned = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health", bound);
        var first = new WorldWatchPersistence(e -> fail(e)); first.openWorld(dir, ownerA, null); assertTrue(first.save(ownerA, List.of(pinned))); first.closeWorld();
        var second = new WorldWatchPersistence(e -> fail(e)); second.openWorld(dir, ownerB, ownerA);
        assertEquals(List.of(pinned), second.get(ownerB)); second.closeWorld();
    }

    @Test void canonicalSingleplayerDefinitionsSurviveAnOwnerIdentityChangeAndGuestsStaySeparate() throws Exception {
        Path dir = Files.createTempDirectory("watch-singleplayer-owner");
        UUID ownerA = UUID.randomUUID(), ownerB = UUID.randomUUID(), guest = UUID.randomUUID();
        var first = new WorldWatchPersistence(e -> fail(e));
        first.openWorld(dir, ownerA, null);
        assertTrue(first.save(ownerA, List.of(score)));
        assertTrue(first.save(guest, List.of(entity)));
        first.closeWorld();

        Path folder = watchesDirectory(dir);
        assertTrue(Files.exists(folder.resolve("singleplayer.json")));
        assertTrue(Files.exists(folder.resolve(guest + ".json")));
        assertFalse(Files.exists(folder.resolve(ownerA + ".json")));

        var reopened = new WorldWatchPersistence(e -> fail(e));
        reopened.openWorld(dir, ownerB, ownerA);
        assertEquals(List.of(score), reopened.get(ownerB));
        assertEquals(List.of(entity), reopened.get(guest));
        reopened.closeWorld();
    }

    @Test void migratesOnlyTheKnownPreviousOwnerLegacyFileAndPreservesIt() throws Exception {
        Path dir = Files.createTempDirectory("watch-owner-migration");
        UUID oldOwner = UUID.randomUUID(), newOwner = UUID.randomUUID(), guest = UUID.randomUUID();
        writeLegacy(dir, oldOwner, List.of(score));
        writeLegacy(dir, guest, List.of(entity));

        var persistence = new WorldWatchPersistence(e -> fail(e));
        persistence.openWorld(dir, newOwner, oldOwner);
        assertEquals(List.of(score), persistence.get(newOwner));
        assertEquals(List.of(entity), persistence.get(guest));
        persistence.closeWorld();

        assertEquals(List.of(score), definitionsAt(watchesDirectory(dir).resolve("singleplayer.json")));
        assertEquals(List.of(score), definitionsAt(watchesDirectory(dir).resolve(oldOwner + ".json")),
            "migration keeps the legacy owner file for recovery");
        assertEquals(List.of(entity), definitionsAt(watchesDirectory(dir).resolve(guest + ".json")));
    }

    @Test void prefersCurrentOwnerLegacyFileOverPreviousOwnerLegacyFile() throws Exception {
        Path dir = Files.createTempDirectory("watch-current-owner-legacy");
        UUID oldOwner = UUID.randomUUID(), currentOwner = UUID.randomUUID();
        writeLegacy(dir, oldOwner, List.of(entity));
        writeLegacy(dir, currentOwner, List.of(score));

        var persistence = new WorldWatchPersistence(e -> fail(e));
        persistence.openWorld(dir, currentOwner, oldOwner);
        assertEquals(List.of(score), persistence.get(currentOwner));
        persistence.closeWorld();

        assertEquals(List.of(score), definitionsAt(watchesDirectory(dir).resolve("singleplayer.json")));
        assertEquals(List.of(entity), definitionsAt(watchesDirectory(dir).resolve(oldOwner + ".json")));
        assertEquals(List.of(score), definitionsAt(watchesDirectory(dir).resolve(currentOwner + ".json")));
    }

    @Test void canonicalEmptyListPreventsLegacyResurrectionAcrossIdentityChange() throws Exception {
        Path dir = Files.createTempDirectory("watch-canonical-empty");
        UUID oldOwner = UUID.randomUUID(), currentOwner = UUID.randomUUID();
        writeLegacy(dir, oldOwner, List.of(score));

        var owner = new WorldWatchPersistence(e -> fail(e));
        owner.openWorld(dir, oldOwner, null);
        assertTrue(owner.save(oldOwner, List.of()));
        owner.closeWorld();

        var changedIdentity = new WorldWatchPersistence(e -> fail(e));
        changedIdentity.openWorld(dir, currentOwner, oldOwner);
        assertEquals(List.of(), changedIdentity.get(currentOwner));
        changedIdentity.closeWorld();
        assertEquals(List.of(), definitionsAt(watchesDirectory(dir).resolve("singleplayer.json")));
        assertEquals(List.of(score), definitionsAt(watchesDirectory(dir).resolve(oldOwner + ".json")));
    }

    @Test void unknownOwnerDoesNotAdoptAnArbitraryGuestLegacyFile() throws Exception {
        Path dir = Files.createTempDirectory("watch-no-guest-fallback");
        UUID guest = UUID.randomUUID(), currentOwner = UUID.randomUUID();
        writeLegacy(dir, guest, List.of(entity));

        var persistence = new WorldWatchPersistence(e -> fail(e));
        persistence.openWorld(dir, currentOwner, null);
        assertEquals(List.of(), persistence.get(currentOwner));
        persistence.closeWorld();

        assertFalse(Files.exists(watchesDirectory(dir).resolve("singleplayer.json")));
        assertEquals(List.of(entity), definitionsAt(watchesDirectory(dir).resolve(guest + ".json")));
    }

    @Test void corruptAndFutureCanonicalFilesArePreservedAndCannotBeSavedOver() throws Exception {
        Path dir = Files.createTempDirectory("watch-invalid-canonical");
        UUID owner = UUID.randomUUID();
        Path folder = watchesDirectory(dir);
        Files.createDirectories(folder);
        Path canonical = folder.resolve("singleplayer.json");
        for (String contents : List.of("not json", "{\"version\":99,\"watches\":[]}")) {
            Files.writeString(canonical, contents);
            List<Exception> errors = new java.util.ArrayList<>();
            var persistence = new WorldWatchPersistence(errors::add);
            persistence.openWorld(dir, owner, null);
            assertEquals(List.of(), persistence.get(owner));
            assertFalse(persistence.save(owner, List.of(score)));
            persistence.closeWorld();
            assertEquals(contents, Files.readString(canonical));
            assertEquals(1, errors.size());
        }
    }

    @Test void canonicalDefinitionsRemainWorldScoped() throws Exception {
        Path firstWorld = Files.createTempDirectory("watch-canonical-a");
        Path secondWorld = Files.createTempDirectory("watch-canonical-b");
        UUID owner = UUID.randomUUID();
        var persistence = new WorldWatchPersistence(e -> fail(e));
        persistence.openWorld(firstWorld, owner, null);
        assertTrue(persistence.save(owner, List.of(score)));
        persistence.closeWorld();
        persistence.openWorld(secondWorld, owner, null);
        assertEquals(List.of(), persistence.get(owner));
        assertTrue(persistence.save(owner, List.of(entity)));
        persistence.closeWorld();
        persistence.openWorld(firstWorld, owner, null);
        assertEquals(List.of(score), persistence.get(owner));
        persistence.closeWorld();
    }

    private static void writeLegacy(Path dir, UUID player, List<WatchSpec> definitions) {
        var persistence = new WorldWatchPersistence(e -> fail(e));
        persistence.openWorld(dir);
        assertTrue(persistence.save(player, definitions));
        persistence.closeWorld();
    }

    private static Path watchesDirectory(Path world) {
        return world.resolve("data/codon-watches");
    }

    private static List<WatchSpec> definitionsAt(Path file) throws Exception {
        var document = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        return WatchDefinitions.decode(document.get("watches"));
    }

    private static List<WatchSpec> watches(int count) {
        return java.util.stream.IntStream.range(0, count)
            .mapToObj(index -> new WatchSpec(WatchSpec.Kind.SCORE,
                "score-" + index + "-" + "x".repeat(110), ""))
            .toList();
    }
}
