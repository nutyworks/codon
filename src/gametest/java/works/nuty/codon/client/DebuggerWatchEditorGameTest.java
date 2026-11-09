package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.core.model.WatchExpression;
import works.nuty.codon.core.model.WatchSpec;

import java.util.List;

/**
 * Inline Watch row regressions against a deterministic paused-state fixture: optional executors,
 * literal score holders, in-place edits and row management. Server-backed completion is covered by
 * {@link DebuggerWatchComposerGameTest}; transport and readers by the real parked-server Watch tests.
 */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerWatchEditorGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext ignored = context.worldBuilder().create()) {
            context.getInput().resizeWindow(640, 400);
            ClientDebuggerState state = new ClientDebuggerState();
            DebuggerOverlay overlay = context.computeOnClient(client -> new DebuggerOverlay(state));
            InputManager input = context.computeOnClient(client -> {
                var base = DebuggerPresentationGameTest.fixture(client);
                // Pin requires an identified pause; this remains a client-only fixture.
                state.applyPause(new works.nuty.codon.core.model.PauseSnapshot(base.location(), base.command(), base.depth(),
                    base.callStack(), base.pauseSources(), base.executionFlows(), base.reason(), 101));
                InputManager result = DebuggerPresentationGameTest.input(client, state);
                client.setScreenAndShow(new CodonScreen(result, overlay));
                return result;
            });
            context.waitTicks(3);

            checkExecutorBinding(context, state, input, overlay);
            checkLiteralHolderEditing(context, state, input, overlay);
            checkRowManagement(context, state);
            context.takeScreenshot("codon-watches-top-management");

            // Hiding Watches ends the row; opening a row shows Watches again.
            context.runOnClient(client -> overlay.watchPanel().beginComposer(-1, input, overlay));
            context.waitTicks(3);
            context.runOnClient(client -> { box(client); state.preferences().setWatchesVisible(false); });
            context.waitTicks(3);
            context.runOnClient(client -> require(screen(client).children().stream().noneMatch(EditBox.class::isInstance),
                "hiding Watches closes the expression row instead of leaving an invisible field"));
            context.runOnClient(client -> overlay.watchPanel().beginComposer(-1, input, overlay));
            context.waitTicks(3);
            context.runOnClient(client -> require(box(client) != null && state.preferences().watchesVisible(),
                "opening the expression row shows the hidden Watches panel"));
            context.runOnClient(client -> box(client).setValue("storage demo:state nested.counter"));
            context.getInput().resizeWindow(320, 240);
            context.waitTicks(3);
            context.takeScreenshot("codon-watch-editor-compact-320x240");
            context.runOnClient(client -> {
                for (DebuggerButton button : buttons(screen(client))) {
                    require(button.getX() >= 0 && button.getY() >= 0
                            && button.getRight() <= screen(client).width && button.getBottom() <= screen(client).height,
                        "compact Watch controls remain within the window");
                }
                require(box(client).getX() >= 0 && box(client).getRight() <= screen(client).width,
                    "the compact expression row remains within the window");
            });
            context.getInput().resizeWindow(640, 400);
            context.waitTicks(3);
            context.takeScreenshot("codon-watch-editor-normal-640x400");
            context.runOnClient(client -> client.setScreenAndShow(null));
        }
    }

    /** A blank executor follows the selected context; a UUID binds the watch to that context. */
    private static void checkExecutorBinding(ClientGameTestContext context, ClientDebuggerState state,
                                             InputManager input, DebuggerOverlay overlay) {
        String uuid = context.computeOnClient(client -> state.selectedSource().entity().uuid().toString());
        enter(context, overlay, input, -1, "score follow_points");
        context.runOnClient(client -> require(state.watches().definitions().stream().anyMatch(spec ->
                spec.kind() == WatchSpec.Kind.SCORE && spec.target().equals("follow_points") && spec.executor() == null),
            "a blank optional entity follows the selected context for Scores"));
        enter(context, overlay, input, -1, "score bound_points " + uuid);
        context.runOnClient(client -> require(state.watches().definitions().stream().anyMatch(spec ->
                spec.kind() == WatchSpec.Kind.SCORE && spec.target().equals("bound_points")
                    && state.selectedSource().entity().uuid().equals(spec.executor())),
            "an optional entity UUID binds Scores to that context"));
        enter(context, overlay, input, -1, "entity Health " + uuid);
        context.runOnClient(client -> require(state.watches().definitions().stream().anyMatch(spec ->
                spec.kind() == WatchSpec.Kind.ENTITY_NBT && spec.path().equals("Health")
                    && state.selectedSource().entity().uuid().equals(spec.executor())),
            "an optional entity UUID binds Entity NBT to that context"));
    }

    /** Score holders are literal names; quoting keeps a UUID-shaped name from binding an entity. */
    private static void checkLiteralHolderEditing(ClientGameTestContext context, ClientDebuggerState state,
                                                  InputManager input, DebuggerOverlay overlay) {
        String uuid = context.computeOnClient(client -> state.selectedSource().entity().uuid().toString());
        enter(context, overlay, input, -1, "score fake_points #counter");
        long[] id = new long[1];
        context.runOnClient(client -> {
            id[0] = state.watches().findId(WatchSpec.scoreHolder("fake_points", "#counter"));
            require(id[0] > 0, "plain fake-player names can be saved from the Watch row");
            overlay.watchPanel().beginComposer(id[0], input, overlay);
        });
        context.waitTicks(3);
        context.runOnClient(client -> require(box(client).getValue().equals(
                WatchExpression.format(WatchSpec.scoreHolder("fake_points", "#counter"))),
            "editing restores the exact literal holder binding"));
        enter(context, overlay, input, id[0], "score fake_points \"" + uuid + "\"");
        context.runOnClient(client -> require(state.watches().findId(WatchSpec.scoreHolder("fake_points", uuid)) == id[0],
            "quoting preserves a UUID-shaped score holder instead of binding the loaded entity"));
        enter(context, overlay, input, id[0], "score fake_points \" " + uuid + " \"");
        context.runOnClient(client -> require(
            state.watches().findId(WatchSpec.scoreHolder("fake_points", " " + uuid + " ")) == id[0],
            "quoting preserves a UUID-shaped holder and its surrounding spaces instead of binding an entity"));
        context.takeScreenshot("codon-watch-fake-player");
    }

    private static void checkRowManagement(ClientGameTestContext context, ClientDebuggerState state) {
        var following = context.computeOnClient(client -> state.watches().entries().stream()
            .filter(entry -> entry.spec().equals(new WatchSpec(WatchSpec.Kind.SCORE, "follow_points", "")))
            .findFirst().orElseThrow());
        var pinnedSpec = context.computeOnClient(client -> following.spec().withExecutor(state.selectedSource().entity().uuid()));
        context.runOnClient(client -> require(state.watches().findId(pinnedSpec) < 0,
            "The menu pin fixture has no conflicting binding"));
        var expected = context.computeOnClient(client -> new java.util.HashSet<>(state.watches().definitions()));
        expected.remove(following.spec());
        expected.add(pinnedSpec);
        WatchGameTestUi.perform(context, following.id(), "codon.watch.pin");
        context.runOnClient(client -> require(new java.util.HashSet<>(state.watches().definitions()).equals(expected),
            "Pin changes only the exact following watch; expected " + expected + "; actual " + state.watches().definitions()));
        long pinnedId = context.computeOnClient(client -> state.watches().findId(pinnedSpec));
        int beforeRemove = context.computeOnClient(client -> state.watches().definitions().size());
        WatchGameTestUi.perform(context, pinnedId, "codon.watch.menu.delete");
        context.runOnClient(client -> require(state.watches().definitions().size() == beforeRemove - 1
            && state.watches().entries().stream().noneMatch(entry -> entry.id() == pinnedId),
            "the Watches row menu removes the exact saved watch"));
    }

    /** Opens the row (add when id is -1), types the expression and presses Enter, as a user would. */
    private static void enter(ClientGameTestContext context, DebuggerOverlay overlay, InputManager input,
                              long id, String text) {
        context.runOnClient(client -> overlay.watchPanel().beginComposer(id, input, overlay));
        context.waitTicks(3);
        context.runOnClient(client -> box(client).setValue(text));
        context.waitTicks(3);
        context.runOnClient(client -> screen(client).keyPressed(
            new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0)));
        context.waitTicks(3);
    }

    private static Screen screen(Minecraft client) {
        if (client.gui.screen() == null) throw new AssertionError("A Watch screen is open");
        return client.gui.screen();
    }

    private static List<DebuggerButton> buttons(Screen screen) {
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast).toList();
    }

    private static EditBox box(Minecraft client) {
        return screen(client).children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
            .filter(EditBox::isVisible).findFirst()
            .orElseThrow(() -> new AssertionError("The Watch expression row is open"));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
