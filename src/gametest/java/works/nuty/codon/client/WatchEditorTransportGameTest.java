package works.nuty.codon.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.resources.Identifier;
import works.nuty.codon.adapter.WatchEditorReader;
import works.nuty.codon.core.model.WatchEditorQuery;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

/** Server-thread catalog and preview coverage for the editor's read-only transport contract. */
@SuppressWarnings("UnstableApiUsage")
public final class WatchEditorTransportGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            world.getServer().runCommand("scoreboard objectives add editor_points dummy");
            world.getServer().runCommand("scoreboard players set @a editor_points 17");
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
            });
            context.runOnClient(client -> {
                var state = CodonClientMod.state();
                var overlay = new works.nuty.codon.client.ui.DebuggerOverlay(state);
                var input = DebuggerPresentationGameTest.input(client, state);
                var form = new works.nuty.codon.client.ui.WatchScreen(input, state, overlay);
                client.setScreenAndShow(new works.nuty.codon.client.ui.WatchPickerScreen(form, state,
                    query(WatchEditorQuery.Mode.NBT, WatchSpec.Kind.STORAGE_NBT, "codon:editor_test", "", null), option -> {}));
            });
            context.waitFor(client -> CodonClientMod.state().watchEditor().page() != null, 200);
            context.runOnClient(client -> require(CodonClientMod.state().watchEditor().page().options().size() == 2,
                "running NBT picker receives its two server options over the actual network"));
            context.waitTicks(3);
            context.takeScreenshot("codon-watch-picker-live-storage");
            context.runOnClient(client -> client.setScreenAndShow(null));
        }
    }
    private static WatchEditorQuery query(WatchEditorQuery.Mode mode, WatchSpec.Kind kind, String target, String path, java.util.UUID executor) {
        return new WatchEditorQuery(mode, kind, target, path, executor, "", 0);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
