package works.nuty.bastion.client;

import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import works.nuty.bastion.client.state.ClientWatchState;
import works.nuty.bastion.core.model.WatchSpec;
import works.nuty.bastion.persistence.WatchDefinitions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Verifies that editable watch definitions are isolated per saved world and restored on join. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerWatchPersistenceGameTest implements FabricClientGameTest {
    private static final WatchSpec SCORE = new WatchSpec(WatchSpec.Kind.SCORE, "persistence_points", "");
    private static final WatchSpec ENTITY = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health");
    private static final WatchSpec STORAGE = new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "bastion:persistence", "value");
    private static final WatchSpec OTHER_SCORE = new WatchSpec(WatchSpec.Kind.SCORE, "other_world_points", "");
    private static final UUID PINNED_EXECUTOR = UUID.fromString("5a6b5176-4ca2-4668-8828-86d496fb2931");
    private static final List<WatchSpec> A_WATCHES = buildWorldAWatches();
    private static final UUID LEGACY_OWNER = UUID.fromString("68d45190-eb18-4784-a43c-5ea32ae31209");

    @Override
    public void runTest(ClientGameTestContext context) {
        require(A_WATCHES.size() == 256, "world A exercises the removed watch-count limit");
        require(WatchDefinitions.toJson(A_WATCHES).length() > 32_767,
            "world A exercises a definition payload larger than a command-sized JSON string");
        require(WatchDefinitions.pages(A_WATCHES).size() > 1,
            "world A definitions require multiple bounded transport pages");
        TestWorldSave worldA;
        UUID playerA;
        try (TestSingleplayerContext a = context.worldBuilder().create()) {
            a.getConnection().waitForChunksRender();
            playerA = grantOwner(a);
            worldA = a.getWorldSave();
            context.runOnClient(client -> {
                var watches = BastionClientMod.state().watches();
                watches.reset();
                require(watches.addAll(A_WATCHES), "atomically add world A watches");
            });
            waitForSaved(context, ownerFile(worldA), A_WATCHES);
        }
        // A prior dev launch used a different owner UUID. Reproduce its on-disk world metadata,
        // then let the next real server startup and join perform the production migration.
        require(!playerA.equals(LEGACY_OWNER), "the saved owner differs from the current login");
        seedLegacyOwner(worldA);

        TestWorldSave worldB;
        try (TestSingleplayerContext b = context.worldBuilder().create()) {
            b.getConnection().waitForChunksRender();
            grantOwner(b);
            worldB = b.getWorldSave();
            context.runOnClient(client -> {
                ClientWatchState watches = BastionClientMod.state().watches();
                require(watches.definitions().isEmpty(), "a new world starts with no definitions");
                require(watches.add(OTHER_SCORE), "add isolated world B score watch");
            });
            waitForSaved(context, ownerFile(worldB), List.of(OTHER_SCORE));
        }

        try (TestSingleplayerContext reopenedA = worldA.open()) {
            reopenedA.getConnection().waitForChunksRender();
            grantOwner(reopenedA);
            reopenedA.getServer().runOnServer(server -> require(
                LEGACY_OWNER.equals(server.getWorldData().getSinglePlayerUUID()),
                "startup reads the previous owner UUID from the actual world save"));
            context.waitFor(client -> BastionClientMod.state().watches().definitions().equals(A_WATCHES), 200);
            waitForSaved(context, ownerFile(worldA), A_WATCHES);
            context.runOnClient(client -> {
                var entries = BastionClientMod.state().watches().entries();
                require(entries.size() == A_WATCHES.size(), "world A restores every definition");
                require(entries.stream().allMatch(entry -> entry.result() == null), "join restores definitions without stale runtime values");
                BastionClientMod.state().watches().clearDefinitions();
            });
            waitForSaved(context, ownerFile(worldA), List.of());
            require(savedDefinitionsEqual(legacyFile(worldA), A_WATCHES), "migration preserves the old owner's file");
        }

        try (TestSingleplayerContext reopenedB = worldB.open()) {
            reopenedB.getConnection().waitForChunksRender();
            grantOwner(reopenedB);
            context.waitFor(client -> BastionClientMod.state().watches().definitions().equals(List.of(OTHER_SCORE)), 200);
            context.runOnClient(client -> require(BastionClientMod.state().watches().entries().size() == 1,
                "world B retains its own definition after world A is cleared"));
        }
        try (TestSingleplayerContext clearedA = worldA.open()) {
            clearedA.getConnection().waitForChunksRender();
            context.runOnClient(client -> require(BastionClientMod.state().watches().definitions().isEmpty(),
                "removing the final watch remains empty when world A is reopened"));
            require(savedDefinitionsEqual(ownerFile(worldA), List.of()), "an empty saved list is retained without resurrecting legacy watches");
        }
    }

    private static UUID grantOwner(TestSingleplayerContext world) {
        return world.getServer().computeOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            server.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
            return player.getUUID();
        });
    }

    private static List<WatchSpec> buildWorldAWatches() {
        List<WatchSpec> watches = new ArrayList<>(256);
        watches.addAll(List.of(SCORE, ENTITY, STORAGE, SCORE.withExecutor(PINNED_EXECUTOR), ENTITY.withExecutor(PINNED_EXECUTOR)));
        for (int i = 0; i < 91; i++) {
            watches.add(new WatchSpec(WatchSpec.Kind.SCORE, "persistence_bulk_score_" + i, ""));
        }
        String longPath = "p".repeat(100);
        for (int i = 0; i < 160; i++) {
            watches.add(new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", longPath + i, PINNED_EXECUTOR));
        }
        return List.copyOf(watches);
    }

    private static Path ownerFile(TestWorldSave save) {
        return save.getSaveDirectory().resolve("data/bastion-watches/singleplayer.json");
    }

    private static Path legacyFile(TestWorldSave save) {
        return save.getSaveDirectory().resolve("data/bastion-watches").resolve(LEGACY_OWNER + ".json");
    }

    private static void seedLegacyOwner(TestWorldSave save) {
        try {
            Files.move(ownerFile(save), legacyFile(save));
            Path levelFile = save.getSaveDirectory().resolve("level.dat");
            var root = NbtIo.readCompressed(levelFile, NbtAccounter.unlimitedHeap());
            root.getCompound("Data").orElseThrow().store("singleplayer_uuid", UUIDUtil.CODEC, LEGACY_OWNER);
            NbtIo.writeCompressed(root, levelFile);
        } catch (IOException failure) {
            throw new AssertionError("Could not prepare a legacy singleplayer-owner test save", failure);
        }
    }

    private static void waitForSaved(ClientGameTestContext context, Path file, List<WatchSpec> expected) {
        context.waitFor(client -> savedDefinitionsEqual(file, expected), 200);
    }

    private static boolean savedDefinitionsEqual(Path file, List<WatchSpec> expected) {
        try {
            var document = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            return WatchDefinitions.decode(document.get("watches")).equals(expected);
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
