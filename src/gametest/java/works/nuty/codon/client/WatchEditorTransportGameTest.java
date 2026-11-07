package works.nuty.codon.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.resources.Identifier;
import works.nuty.codon.adapter.WatchEditorReader;
import works.nuty.codon.core.model.WatchEditorQuery;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

/** Server-thread catalog and preview coverage for the editor's read-only reader contract; the network path is covered by DebuggerWatchComposerGameTest. */
@SuppressWarnings("UnstableApiUsage")
public final class WatchEditorTransportGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            world.getServer().runCommand("scoreboard objectives add editor_points dummy");
            world.getServer().runCommand("scoreboard players set @a editor_points 17");
            world.getServer().runCommand("scoreboard players set #counter editor_points 0");
            world.getServer().runCommand("data merge storage codon:editor_test {count:9,nested:{flag:1b}}");
            var snapshot = context.computeOnClient(DebuggerPresentationGameTest::fixture);
            world.getServer().runOnServer(server -> {
                var objectives = WatchEditorReader.read(server, snapshot, 0, query(WatchEditorQuery.Mode.OBJECTIVES, WatchSpec.Kind.SCORE, "editor", "", null));
                require(objectives.options().stream().anyMatch(value -> value.value().equals("editor_points")), "objective catalog searches server objectives");
                var storage = WatchEditorReader.read(server, snapshot, 0, query(WatchEditorQuery.Mode.STORAGES, WatchSpec.Kind.STORAGE_NBT, "editor", "", null));
                require(storage.options().stream().anyMatch(value -> value.value().equals("codon:editor_test")), "storage catalog searches command storage");
                var page = WatchEditorReader.read(server, snapshot, 0, query(WatchEditorQuery.Mode.NBT, WatchSpec.Kind.STORAGE_NBT, "codon:editor_test", "", null));
                require(page.status() == WatchResult.Status.VALUE && page.options().stream().anyMatch(value -> value.value().equals("\"count\"")), "storage NBT browser returns child paths");
                var player = server.getPlayerList().getPlayers().getFirst();
                server.getPlayerList().op(player.nameAndId(),
                    java.util.Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER), java.util.Optional.empty());
                var score = WatchEditorReader.read(server, snapshot, 0, query(WatchEditorQuery.Mode.PREVIEW, WatchSpec.Kind.SCORE, "editor_points", "", player.getUUID()));
                require(score.preview() != null && score.preview().status() == WatchResult.Status.VALUE && score.preview().value().equals("17"), "bound Score preview reads the selected loaded entity");
                var missing = WatchEditorReader.read(server, null, -1, query(WatchEditorQuery.Mode.PREVIEW, WatchSpec.Kind.ENTITY_NBT, "", "Health", null));
                require(missing.status() == WatchResult.Status.NO_EXECUTOR, "entity preview without context is explicit");
                var named = new WatchEditorQuery(WatchEditorQuery.Mode.PREVIEW, WatchSpec.Kind.SCORE,
                    "editor_points", "", null, "", 0, "#counter");
                var fakeScore = WatchEditorReader.read(server, null, -1, named);
                require(fakeScore.preview() != null && fakeScore.preview().value().equals("0")
                    && fakeScore.preview().targetKey().equals("score-holder:#counter"),
                    "fake-player zero previews without an entity or pause context");
                var holders = WatchEditorReader.read(server, null, -1,
                    new WatchEditorQuery(WatchEditorQuery.Mode.ENTITIES, WatchSpec.Kind.SCORE,
                        "editor_points", "", null, "#counter", 0));
                require(holders.options().size() == 1 && holders.options().getFirst().value().equals("\"#counter\""),
                    "the Score picker searches tracked fake players and returns a literal binding");
                var entities = WatchEditorReader.read(server, null, -1,
                    new WatchEditorQuery(WatchEditorQuery.Mode.ENTITIES, WatchSpec.Kind.ENTITY_NBT,
                        "", "", null, "#counter", 0));
                require(entities.options().isEmpty(), "Entity NBT never offers fake players");
                server.getScoreboard().resetAllPlayerScores(net.minecraft.world.scores.ScoreHolder.forNameOnly("#counter"));
                require(WatchEditorReader.read(server, null, -1, named).status() == WatchResult.Status.VALUE_MISSING,
                    "reset fake-player score becomes absent without recreating the holder");
                server.getScoreboard().removeObjective(server.getScoreboard().getObjective("editor_points"));
                require(WatchEditorReader.read(server, null, -1, named).status() == WatchResult.Status.OBJECTIVE_MISSING,
                    "missing named objective remains distinct from an unset score");
            });
        }
    }
    private static WatchEditorQuery query(WatchEditorQuery.Mode mode, WatchSpec.Kind kind, String target, String path, java.util.UUID executor) {
        return new WatchEditorQuery(mode, kind, target, path, executor, "", 0);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
