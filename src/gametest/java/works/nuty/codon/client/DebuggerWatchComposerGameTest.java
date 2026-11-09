package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.core.model.WatchSpec;

import java.util.Optional;

/** The inline Watch row against a real singleplayer server: typing, completion, validation and editing. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerWatchComposerGameTest implements FabricClientGameTest {
    private static final String BOX = "Watch expression";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            context.getInput().resizeWindow(640, 400);
            // Editor reads are owner-only, like every other debugger request.
            world.getServer().runOnServer(server -> server.getPlayerList().op(
                server.getPlayerList().getPlayers().getFirst().nameAndId(),
                Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty()));
            world.getServer().runCommand("scoreboard objectives add composer_points dummy");
            world.getServer().runCommand("data merge storage codon:composer_test {count:9,nested:{flag:1b}}");
            ClientDebuggerState state = CodonClientMod.state();
            DebuggerOverlay[] overlay = new DebuggerOverlay[1];
            context.runOnClient(client -> {
                overlay[0] = new DebuggerOverlay(state);
                client.setScreenAndShow(new CodonScreen(DebuggerPresentationGameTest.input(client, state), overlay[0]));
            });
            context.waitTicks(3);

            // Opening focuses the row, and typing never reaches the menu, hide or execution shortcuts.
            context.runOnClient(client -> click(client.gui.screen(), "+"));
            context.waitTicks(3);
            context.runOnClient(client -> {
                EditBox box = box(client);
                require(client.gui.screen().getFocused() == box, "the + button focuses the expression row");
                client.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_V, 0, 0));
                type(client, "v");
                require(client.gui.screen() instanceof CodonScreen && box.getValue().equals("v"),
                    "the menu key is plain text while the expression row is focused");
                box.setValue("s");
            });
            context.waitTicks(3);
            context.runOnClient(client -> require(hasButton(client, "score"),
                "the first word offers the three kinds"));
            context.takeScreenshot("codon-watch-composer-keywords");

            // Objective completion comes from the real server, and Tab inserts the choice.
            context.runOnClient(client -> box(client).setValue("score composer"));
            context.waitFor(client -> state.watchEditor().page() != null, 200);
            context.waitTicks(3);
            context.takeScreenshot("codon-watch-composer-objectives");
            context.runOnClient(client -> {
                require(hasButton(client, "composer_points"), "objectives are completed from the server");
                key(client, InputConstants.KEY_TAB);
                require(box(client).getValue().equals("score composer_points"), "Tab inserts the highlighted objective");
                key(client, InputConstants.KEY_RETURN);
                require(state.watches().definitions().contains(new WatchSpec(WatchSpec.Kind.SCORE, "composer_points", "")),
                    "Enter saves the typed objective");
            });
            context.waitTicks(3);
            context.runOnClient(client -> require(client.gui.screen() instanceof CodonScreen
                && client.gui.screen().children().stream().noneMatch(child -> child instanceof EditBox),
                "the row closes after Enter"));

            // Fake-player holders are offered for a named objective, and a resize keeps the typed row.
            world.getServer().runCommand("scoreboard players set #counter composer_points 0");
            context.runOnClient(client -> click(client.gui.screen(), "+"));
            context.waitTicks(3);
            context.runOnClient(client -> box(client).setValue("score composer_points #c"));
            context.waitFor(client -> state.watchEditor().page() != null, 200);
            context.waitTicks(3);
            context.runOnClient(client -> require(hasButton(client, "#counter"), "score holders are completed from the server"));
            context.getInput().resizeWindow(900, 600);
            context.waitTicks(3);
            context.runOnClient(client -> require(box(client).getValue().equals("score composer_points #c")
                    && client.gui.screen().getFocused() == box(client),
                "the typed row and its focus survive a window resize"));
            context.getInput().resizeWindow(640, 400);
            context.waitTicks(3);
            context.runOnClient(client -> key(client, InputConstants.KEY_ESCAPE));
            context.waitTicks(3);

            // Storage: ID completion, then plain-spelled child paths even though the server quotes them.
            context.runOnClient(client -> click(client.gui.screen(), "+"));
            context.waitTicks(3);
            context.runOnClient(client -> box(client).setValue("storage composer_t"));
            context.waitFor(client -> state.watchEditor().page() != null, 200);
            context.waitTicks(3);
            context.runOnClient(client -> {
                require(hasButton(client, "codon:composer_test"), "storage IDs are completed from the server");
                key(client, InputConstants.KEY_TAB);
                require(box(client).getValue().equals("storage codon:composer_test "), "a chosen storage ID continues to the path");
                box(client).setValue("storage codon:composer_test co");
            });
            // The server filters children by the typed prefix, so only "count" matches "co".
            context.waitFor(client -> state.watchEditor().page() != null
                && state.watchEditor().page().options().size() == 1, 200);
            context.waitTicks(3);
            context.takeScreenshot("codon-watch-composer-storage-path");
            context.runOnClient(client -> {
                require(hasButton(client, "count"), "child names complete without the server's quotes");
                key(client, InputConstants.KEY_TAB);
                require(box(client).getValue().equals("storage codon:composer_test count"), "the plain path is inserted");
                // Ctrl+Enter keeps the keyword for repeated additions.
                client.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN,
                    InputConstants.MOD_CONTROL));
                require(state.watches().definitions().contains(
                        new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "codon:composer_test", "count")),
                    "the storage path is saved");
                require(box(client).getValue().equals("storage "), "Ctrl+Enter keeps the kind for another watch");
            });

            // Invalid text stays editable with a reason; Escape closes only the row.
            context.runOnClient(client -> box(client).setValue("entity Health["));
            context.waitTicks(3);
            context.runOnClient(client -> {
                key(client, InputConstants.KEY_RETURN);
                require(box(client).getValue().equals("entity Health[") && state.watches().definitions().size() == 2,
                    "an invalid path is never added");
            });
            context.waitTicks(3);
            context.takeScreenshot("codon-watch-composer-invalid");
            context.runOnClient(client -> {
                key(client, InputConstants.KEY_ESCAPE);
                require(client.gui.screen() instanceof CodonScreen, "Escape closes the row, not cursor mode");
            });
            context.waitTicks(3);
            context.runOnClient(client -> require(client.gui.screen().children().stream()
                .noneMatch(child -> child instanceof EditBox), "the row is gone after Escape"));

            // Editing replaces the definition in place, preserving its identity.
            long[] id = new long[1];
            context.runOnClient(client -> {
                id[0] = state.watches().findId(new WatchSpec(WatchSpec.Kind.SCORE, "composer_points", ""));
                overlay[0].watchPanel().beginComposer(id[0], DebuggerPresentationGameTest.input(client, state), overlay[0]);
            });
            context.waitTicks(3);
            context.runOnClient(client -> {
                require(box(client).getValue().equals("score composer_points"), "editing starts from the saved expression");
                box(client).setValue("score composer_points #counter");
            });
            context.waitTicks(3);
            context.runOnClient(client -> {
                key(client, InputConstants.KEY_RETURN);
                require(state.watches().findId(WatchSpec.scoreHolder("composer_points", "#counter")) == id[0],
                    "editing updates the same watch");
            });
            context.runOnClient(client -> client.setScreenAndShow(null));
        }
    }

    private static EditBox box(Minecraft client) {
        return client.gui.screen().children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
            .filter(value -> value.getMessage().getString().equals(BOX)).findFirst()
            .orElseThrow(() -> new AssertionError("the Watch expression row is open"));
    }

    private static boolean hasButton(Minecraft client, String label) {
        return client.gui.screen().children().stream().filter(DebuggerButton.class::isInstance)
            .map(DebuggerButton.class::cast).anyMatch(button -> button.getMessage().getString().startsWith(label));
    }

    private static void key(Minecraft client, int key) {
        client.gui.screen().keyPressed(new KeyEvent(key, 0, 0));
    }

    private static void type(Minecraft client, String text) {
        text.codePoints().forEach(point -> client.gui.screen().charTyped(new CharacterEvent(point)));
    }

    private static void click(Screen screen, String label) {
        var button = screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(value -> value.getMessage().getString().equals(label)).findFirst()
            .orElseThrow(() -> new AssertionError("Required control is visible: " + label));
        var event = new MouseButtonEvent(button.getX() + 2, button.getY() + 2,
            new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
        require(screen.mouseClicked(event, false), "control accepts click: " + label);
        screen.mouseReleased(event);
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
