package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.resources.Identifier;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.WatchScreen;
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
            world.getServer().runCommand("scoreboard objectives add editor_points dummy");
            world.getServer().runCommand("scoreboard players set #counter editor_points 0");
            context.runOnClient(client -> {
                var state = CodonClientMod.state();
                var overlay = new works.nuty.codon.client.ui.DebuggerOverlay(state);
                var input = DebuggerPresentationGameTest.input(client, state);
                client.setScreenAndShow(new WatchScreen(input, state, overlay));
                click(client.gui.screen(), "Score");
                field(client.gui.screen(), "Objective").setValue("editor_points");
                field(client.gui.screen(), "Entity UUID or score holder (optional)").setValue("#counter");
                click(client.gui.screen(), "Choose");
            });
            context.waitFor(client -> CodonClientMod.state().watchEditor().page() != null, 200);
            context.waitTicks(3);
            context.takeScreenshot("codon-watch-picker-live-fake-player");
            context.runOnClient(client -> {
                var page = CodonClientMod.state().watchEditor().page();
                require(page.options().size() == 1 && page.options().getFirst().label().equals("#counter"),
                    "Score chooser receives the searched fake player over the actual network");
                client.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_DOWN, 0, 0));
                client.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0));
                require(client.gui.screen() instanceof WatchScreen
                    && field(client.gui.screen(), "Entity UUID or score holder (optional)").getValue().equals("\"#counter\""),
                    "choosing a fake player restores the form with a literal name binding");
                click(client.gui.screen(), "Add");
                require(CodonClientMod.state().watches().findId(WatchSpec.scoreHolder("editor_points", "#counter")) > 0,
                    "the chosen fake player is saved as a named score watch");
            });
            context.runOnClient(client -> client.setScreenAndShow(null));
        }
    }
    private static WatchEditorQuery query(WatchEditorQuery.Mode mode, WatchSpec.Kind kind, String target, String path, java.util.UUID executor) {
        return new WatchEditorQuery(mode, kind, target, path, executor, "", 0);
    }
    private static EditBox field(Screen screen, String label) {
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
            .filter(field -> field.getMessage().getString().equals(label)).findFirst().orElseThrow();
    }
    private static void click(Screen screen, String label) {
        var button = screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(value -> value.getMessage().getString().equals(label)).findFirst().orElseThrow();
        var event = new MouseButtonEvent(button.getX() + 2, button.getY() + 2,
            new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
        require(screen.mouseClicked(event, false), "Watch control accepts click: " + label);
        screen.mouseReleased(event);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
