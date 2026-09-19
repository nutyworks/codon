package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.InputType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.client.ui.WatchScreen;
import works.nuty.codon.core.model.WatchSpec;

import java.util.List;

/**
 * Editor-only regressions use a deterministic paused-state fixture.  Watch transport and the
 * server-side reader remain covered by the real parked-server Watch tests.
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
                state.applyPause(DebuggerPresentationGameTest.fixture(client));
                InputManager result = DebuggerPresentationGameTest.input(client, state);
                client.setScreenAndShow(new WatchScreen(result, state, overlay));
                return result;
            });
            context.waitTicks(3);

            checkAddOnlyEditor(context, state, input, overlay);
            checkDraftsValidationAndKeyboard(context, state, input, overlay);
            checkOptionalExecutorAndHudManagement(context, state, input, overlay);
            context.takeScreenshot("codon-watches-top-management");
            context.runOnClient(client -> {
                client.setScreenAndShow(new WatchScreen(input, state, overlay));
                click(editor(client), button(editor(client), "Storage NBT"));
                field(editor(client), "Storage ID").setValue("demo:state");
                field(editor(client), "NBT path").setValue("nested.counter");
            });
            context.getInput().resizeWindow(320, 240);
            context.waitTicks(3);
            context.takeScreenshot("codon-watch-editor-compact-320x240");
            context.runOnClient(client -> {
                for (DebuggerButton button : buttons(screen(client))) {
                    require(button.getX() >= 0 && button.getY() >= 0
                            && button.getRight() <= screen(client).width && button.getBottom() <= screen(client).height,
                        "compact Watch controls remain within the window");
                }
            });
            context.getInput().resizeWindow(640, 400);
            context.waitTicks(3);
            context.takeScreenshot("codon-watch-editor-normal-640x400");
            context.runOnClient(client -> client.setScreenAndShow(null));
        }
    }

    private static void checkAddOnlyEditor(ClientGameTestContext context, ClientDebuggerState state,
                                           InputManager input, DebuggerOverlay overlay) {
        context.runOnClient(client -> {
            WatchScreen editor = editor(client);
            require(button(editor, "Score") != null && button(editor, "Entity NBT") != null
                    && button(editor, "Storage NBT") != null,
                "Watch editor exposes direct, non-cycling kind buttons");
            require(buttons(editor).stream().noneMatch(value -> value.getMessage().getString().equals("Remove")
                    || value.getMessage().getString().equals("Pin executor")
                    || value.getMessage().getString().equals("Unpin executor")),
                "Watch editor is add-only; management stays in the Watches HUD");
            EditBox objective = field(editor, "Objective");
            require(editor.getFocused() == objective, "the first expression field receives initial keyboard focus");
            objective.setValue("editor_points");
            client.setLastInputType(InputType.KEYBOARD_TAB);
            editor.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0));
            require(client.gui.screen() instanceof CodonScreen, "Enter adds once and returns to the Watches HUD");
            require(state.watches().definitions().contains(new WatchSpec(WatchSpec.Kind.SCORE, "editor_points", "")),
                "Enter saves the Score objective");
            client.setScreenAndShow(new WatchScreen(input, state, overlay));
        });
        context.waitTicks(3);
    }

    private static void checkDraftsValidationAndKeyboard(ClientGameTestContext context, ClientDebuggerState state,
                                                          InputManager input, DebuggerOverlay overlay) {
        context.runOnClient(client -> {
            WatchScreen editor = editor(client);
            EditBox objective = field(editor, "Objective");
            objective.setValue("score_draft");
            click(editor, button(editor, "Entity NBT"));
            EditBox path = field(editor, "NBT path");
            path.setValue("Health");
            click(editor, button(editor, "Score"));
            require(field(editor, "Objective").getValue().equals("score_draft"),
                "Score retains its own draft after changing kinds");
            click(editor, button(editor, "Entity NBT"));
            require(field(editor, "NBT path").getValue().equals("Health"),
                "Entity NBT retains its own draft after changing kinds");
            path = field(editor, "NBT path");
            path.setValue("Health[");
            int beforeInvalid = state.watches().definitions().size();
            editor.setFocused(path);
            editor.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0));
            require(state.watches().definitions().size() == beforeInvalid && path.getValue().equals("Health["),
                "invalid NBT paths remain editable and are never added");

            path.setValue("Health");
            editor.setFocused(path);
            boolean freecam = state.preferences().keepFreecam();
            editor.keyPressed(new KeyEvent(InputConstants.KEY_G, 0, 0));
            require(state.preferences().keepFreecam() == freecam,
                "typing a printable key never triggers a debugger shortcut from an editor field");
            client.setLastInputType(InputType.KEYBOARD_TAB);
            editor.keyPressed(new KeyEvent(InputConstants.KEY_TAB, InputConstants.KEYCODE_TAB, 0));
            require(editor.getFocused() instanceof EditBox || focusedLabel(editor).equals("Add"),
                "Tab advances across visible editor fields before leaving the form");
            editor.setFocused(path);
            editor.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, InputConstants.MOD_CONTROL));
            require(client.gui.screen() instanceof WatchScreen && state.watches().definitions().size() == beforeInvalid + 1,
                "Ctrl+Enter adds another watch without closing the editor");
            WatchScreen afterAdd = editor(client);
            require(field(afterAdd, "NBT path").getValue().isEmpty() && afterAdd.getFocused() instanceof EditBox,
                "Ctrl+Enter clears the expression and restores editor focus");
            afterAdd.onClose();
            require(client.gui.screen() instanceof CodonScreen, "closing the editor returns to the existing overlay");
            client.setScreenAndShow(new WatchScreen(input, state, overlay));
        });
        context.waitTicks(3);
    }

    private static void checkOptionalExecutorAndHudManagement(ClientGameTestContext context, ClientDebuggerState state,
                                                               InputManager input, DebuggerOverlay overlay) {
        context.runOnClient(client -> {
            WatchScreen editor = editor(client);
            click(editor, button(editor, "Score"));
            field(editor, "Objective").setValue("follow_points");
            click(editor, button(editor, "Add"));
            require(state.watches().definitions().stream().anyMatch(spec -> spec.kind() == WatchSpec.Kind.SCORE
                    && spec.target().equals("follow_points") && spec.executor() == null),
                "a blank optional Entity follows the selected context for Scores");

            client.setScreenAndShow(new WatchScreen(input, state, overlay));
            editor = editor(client);
            click(editor, button(editor, "Score"));
            field(editor, "Objective").setValue("bound_points");
            field(editor, "Entity (optional)").setValue(state.selectedSource().entity().uuid().toString());
            click(editor, button(editor, "Add"));
            require(state.watches().definitions().stream().anyMatch(spec -> spec.kind() == WatchSpec.Kind.SCORE
                    && spec.target().equals("bound_points") && state.selectedSource().entity().uuid().equals(spec.executor())),
                "an optional Entity UUID binds Scores to that context");

            client.setScreenAndShow(new WatchScreen(input, state, overlay));
            editor = editor(client);
            click(editor, button(editor, "Entity NBT"));
            field(editor, "NBT path").setValue("Health");
            field(editor, "Entity (optional)").setValue(state.selectedSource().entity().uuid().toString());
            click(editor, button(editor, "Add"));
            require(state.watches().definitions().stream().anyMatch(spec -> spec.kind() == WatchSpec.Kind.ENTITY_NBT
                    && spec.path().equals("Health") && state.selectedSource().entity().uuid().equals(spec.executor())),
                "an optional Entity UUID binds Entity NBT to that context");
            client.setScreenAndShow(new CodonScreen(input, overlay));
        });
        context.waitTicks(3);
        context.runOnClient(client -> {
            CodonScreen hud = (CodonScreen) client.gui.screen();
            DebuggerButton pin = button(hud, "Pin executor");
            click(hud, pin);
            require(state.watches().definitions().stream().anyMatch(WatchSpec::isPinned),
                "the Watches HUD pins a following watch");
            DebuggerButton remove = button(hud, "Remove");
            int beforeRemove = state.watches().definitions().size();
            click(hud, remove);
            require(state.watches().definitions().size() == beforeRemove - 1,
                "the Watches HUD removes a saved watch");
        });
    }

    private static WatchScreen editor(Minecraft client) {
        if (!(client.gui.screen() instanceof WatchScreen editor)) throw new AssertionError("Watch editor is open");
        return editor;
    }

    private static Screen screen(Minecraft client) {
        if (client.gui.screen() == null) throw new AssertionError("A Watch screen is open");
        return client.gui.screen();
    }

    private static List<DebuggerButton> buttons(Screen screen) {
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast).toList();
    }

    private static DebuggerButton button(Screen screen, String label) {
        return buttons(screen).stream().filter(value -> value.getMessage().getString().equals(label)).findFirst()
            .orElseThrow(() -> new AssertionError("Required Watch button is visible: " + label));
    }

    private static EditBox field(Screen screen, String label) {
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
            .filter(EditBox::isVisible).filter(value -> value.getMessage().getString().equals(label)).findFirst()
            .orElseThrow(() -> new AssertionError("Required Watch field is visible: " + label));
    }

    private static String focusedLabel(Screen screen) {
        if (!(screen.getFocused() instanceof DebuggerButton button)) return "";
        return button.getMessage().getString();
    }

    private static void click(Screen screen, DebuggerButton button) {
        var event = new MouseButtonEvent(button.getX() + 2, button.getY() + 2,
            new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
        require(screen.mouseClicked(event, false), "Watch control accepts click: " + button.getMessage().getString());
        screen.mouseReleased(event);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
