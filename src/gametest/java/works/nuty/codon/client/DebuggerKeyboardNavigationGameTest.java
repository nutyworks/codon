package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.InputType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.KeyEvent;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.DebuggerHelpScreen;
import works.nuty.codon.client.ui.DebuggerIcon;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.client.ui.WatchScreen;
import net.minecraft.client.gui.components.EditBox;
import works.nuty.codon.client.ui.layout.DebuggerLayout;
import works.nuty.codon.core.model.CallFrame;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.FunctionId;
import works.nuty.codon.core.model.FunctionLocation;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.NbtPage;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Keyboard-only presentation regressions.  The synthetic snapshot deliberately does not claim
 * to be an acceptance test for a real server debug pause; it only supplies deterministic UI data.
 */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerKeyboardNavigationGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext ignored = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1280, 600);
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
            });

            ClientDebuggerState state = new ClientDebuggerState();
            CodonScreen screen = context.computeOnClient(client -> {
                // This is a UI fixture, not a server-side pause or execution-flow acceptance test.
                state.applyPause(DebuggerPresentationGameTest.fixture(client));
                InputManager input = DebuggerPresentationGameTest.input(client, state);
                CodonScreen result = new CodonScreen(input, new DebuggerOverlay(state));
                client.setScreenAndShow(result);
                return result;
            });
            context.waitTicks(3);

            checkActionRowAndToolbar(context, screen, state);
            checkSourceListNavigation(context, screen, state);
            checkScrolledSourceDoesNotActivateOrReset(context, screen, state);
            checkCallPathNavigation(context, screen, state);
            checkNbtNavigation(context, screen, state);
            checkWatchContainers(context, state);
            checkHelpKeysAndTopicFocus(context, screen, state);

            context.runOnClient(client -> client.setScreenAndShow(null));
        }
    }

    private static void checkActionRowAndToolbar(ClientGameTestContext context, CodonScreen screen,
                                                 ClientDebuggerState state) {
        context.runOnClient(client -> {
            DebuggerButton watch = button(screen, label -> label.equals("Watch"));
            screen.setFocused(watch);
            press(client, screen, InputConstants.KEY_RIGHT, InputConstants.KEYCODE_RIGHT, 0, InputType.KEYBOARD_ARROW);
            require(focusedLabel(screen).contains("Expand"), "Right moves within the action container");
            var expand = screen.getFocused();
            press(client, screen, InputConstants.KEY_DOWN, InputConstants.KEYCODE_DOWN, 0, InputType.KEYBOARD_ARROW);
            require(screen.getFocused() == expand, "Down cannot leave the action container");
            screen.setFocused(watch);
            press(client, screen, InputConstants.KEY_TAB, InputConstants.KEYCODE_TAB, 0, InputType.KEYBOARD_TAB);
            require(focused(screen).getY() < 50, "Tab skips the rest of the action row and enters the toolbar");
            press(client, screen, InputConstants.KEY_TAB, InputConstants.KEYCODE_TAB, InputConstants.MOD_SHIFT,
                InputType.KEYBOARD_TAB);
            require(screen.getFocused() == watch, "Shift+Tab restores the previous item in the action container");

            List<DebuggerButton> toolbar = screen.children().stream().filter(DebuggerButton.class::isInstance)
                .map(DebuggerButton.class::cast).filter(button -> button.getY() < 50 && button.active)
                .sorted(java.util.Comparator.comparingInt(DebuggerButton::getX)).toList();
            screen.setFocused(toolbar.getFirst());
            press(client, screen, InputConstants.KEY_RIGHT, InputConstants.KEYCODE_RIGHT, 0, InputType.KEYBOARD_ARROW);
            require(screen.getFocused() == toolbar.get(1), "Toolbar Right follows visual order");
            press(client, screen, InputConstants.KEY_DOWN, InputConstants.KEYCODE_DOWN, 0, InputType.KEYBOARD_ARROW);
            require(screen.getFocused() == toolbar.get(1), "Toolbar Down cannot enter the source list");
            press(client, screen, InputConstants.KEY_TAB, InputConstants.KEYCODE_TAB, 0, InputType.KEYBOARD_TAB);
            require(sourceIndex(focused(screen), state) == 1, "Tab enters the next container instead of the next toolbar button");
        });
    }

    private static void checkSourceListNavigation(ClientGameTestContext context, CodonScreen screen,
                                                  ClientDebuggerState state) {
        int sourceCount = context.computeOnClient(client -> state.displayedSources().size());
        require(sourceCount > 2, "Keyboard fixture has multiple logical sources");
        context.runOnClient(client -> {
            DebuggerButton first = sourceButton(screen, state, 1);
            screen.setFocused(first);
            int selected = state.selectedSourceIndex();
            press(client, screen, InputConstants.KEY_DOWN, InputConstants.KEYCODE_DOWN, 0, InputType.KEYBOARD_ARROW);
            require(state.selectedSourceIndex() == selected, "Arrow navigation previews a source without selecting it");
            if (screen.getFocused() == null) {
                press(client, screen, InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0, InputType.KEYBOARD_ARROW);
                require(state.selectedSourceIndex() == selected, "Enter during a deferred reveal does not activate the old source");
            }
        });
        context.waitTicks(2);

        for (int index = 0; index < sourceCount - 2; index++) {
            context.runOnClient(client -> press(client, screen, InputConstants.KEY_DOWN, InputConstants.KEYCODE_DOWN,
                0, InputType.KEYBOARD_ARROW));
            context.waitTicks(2);
        }
        context.runOnClient(client -> {
            require(sourceIndex(focused(screen), state) == sourceCount, "Down reveals and focuses the final logical source; expected " + sourceCount + ", actual " + focusedLabel(screen));
            int selected = state.selectedSourceIndex();
            for (int index = 0; index < 3; index++)
                press(client, screen, InputConstants.KEY_DOWN, InputConstants.KEYCODE_DOWN, 0, InputType.KEYBOARD_ARROW);
            require(sourceIndex(focused(screen), state) == sourceCount,
                "Down stays at the final source instead of escaping to NBT or another region; actual " + focusedLabel(screen));
            require(state.selectedSourceIndex() == selected, "Repeated arrow navigation does not activate a source");
        });

        context.takeScreenshot("codon-keyboard-final-source");
        context.getInput().resizeWindow(1100, 700);
        context.waitTicks(2);
        context.runOnClient(client -> require(sourceIndex(focused(screen), state) == sourceCount,
            "Keyboard focus survives a screen resize"));
        context.getInput().resizeWindow(1280, 600);
        context.waitTicks(2);
        for (int index = 0; index < sourceCount - 1; index++) {
            context.runOnClient(client -> press(client, screen, InputConstants.KEY_UP, InputConstants.KEYCODE_UP,
                0, InputType.KEYBOARD_ARROW));
            context.waitTicks(2);
        }
        context.runOnClient(client -> require(sourceIndex(focused(screen), state) == 1,
            "Up returns to the first logical source before the Tab traversal"));
        context.runOnClient(client -> press(client, screen, InputConstants.KEY_DOWN, InputConstants.KEYCODE_DOWN,
            0, InputType.KEYBOARD_ARROW));
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(sourceIndex(focused(screen), state) == 2, "Down reaches the second source");
            press(client, screen, InputConstants.KEY_TAB, InputConstants.KEYCODE_TAB, 0, InputType.KEYBOARD_TAB);
            require(focused(screen).icon() == DebuggerIcon.COPY_UUID, "Tab leaves Sources for the details container");
            press(client, screen, InputConstants.KEY_TAB, InputConstants.KEYCODE_TAB, InputConstants.MOD_SHIFT,
                InputType.KEYBOARD_TAB);
        });
        context.waitTicks(2);
        context.runOnClient(client -> require(sourceIndex(focused(screen), state) == 2,
            "Returning to Sources restores its previous logical focus"));
    }

    private static void checkScrolledSourceDoesNotActivateOrReset(ClientGameTestContext context, CodonScreen screen,
                                                                  ClientDebuggerState state) {
        context.runOnClient(client -> {
            DebuggerButton second = focused(screen);
            require(sourceIndex(second, state) == 2, "Keyboard setup reaches the second source");
            screen.setFocused(second);
            int selected = state.selectedSourceIndex();
            for (int index = 0; index < 8; index++) screen.mouseScrolled(second.getX() + 2, second.getY() + 2, 0, -1);
            press(client, screen, InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0, InputType.KEYBOARD_ARROW);
            require(state.selectedSourceIndex() == selected, "Enter before the next render cannot activate a wheel-hidden source");
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(screen.getFocused() == null, "A wheel-hidden source loses its concrete widget focus");
            int selected = state.selectedSourceIndex();
            press(client, screen, InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0, InputType.KEYBOARD_ARROW);
            require(state.selectedSourceIndex() == selected, "Enter cannot activate the wheel-hidden source");
            press(client, screen, InputConstants.KEY_TAB, InputConstants.KEYCODE_TAB, 0, InputType.KEYBOARD_TAB);
            require(focused(screen).icon() == DebuggerIcon.COPY_UUID, "Tab exits a scrolled container");
            press(client, screen, InputConstants.KEY_TAB, InputConstants.KEYCODE_TAB, InputConstants.MOD_SHIFT,
                InputType.KEYBOARD_TAB);
        });
        context.waitTicks(2);
        context.runOnClient(client -> require(sourceIndex(focused(screen), state) == 2,
            "Shift+Tab reveals the remembered source after mouse scrolling"));
    }

    private static void checkCallPathNavigation(ClientGameTestContext context, CodonScreen screen,
                                                ClientDebuggerState state) {
        context.runOnClient(client -> {
            PauseSnapshot base = DebuggerPresentationGameTest.fixture(client);
            List<CallFrame> frames = new ArrayList<>();
            frames.add(base.callStack().getFirst());
            for (int index = 1; index <= 9; index++) {
                SourceLocation location = new SourceLocation.Function(new FunctionLocation(
                    new FunctionId("demo", "caller_" + index), index));
                frames.add(new CallFrame(9 - index, location, CommandSnippet.plain("function demo:caller_" + (index - 1)),
                    9_000 + index, 0));
            }
            state.applyPause(new PauseSnapshot(base.location(), base.command(), base.depth(), frames,
                base.pauseSources(), base.executionFlows(), base.reason(), base.pauseId()));
        });
        context.waitTicks(2);
        context.runOnClient(client -> screen.setFocused(button(screen, label -> label.equals("demo:spawn_wave:12"))));
        for (int index = 0; index < 9; index++) {
            context.runOnClient(client -> press(client, screen, InputConstants.KEY_LEFT, InputConstants.KEYCODE_LEFT,
                0, InputType.KEYBOARD_ARROW));
            context.waitTicks(2);
        }
        context.runOnClient(client -> require(focusedLabel(screen).contains("caller_9"),
            "Left reveals the hidden call frame and preserves keyboard focus after rendering"));
        context.takeScreenshot("codon-keyboard-hidden-call-frame");
        for (int index = 0; index < 9; index++) {
            context.runOnClient(client -> press(client, screen, InputConstants.KEY_RIGHT, InputConstants.KEYCODE_RIGHT,
                0, InputType.KEYBOARD_ARROW));
            context.waitTicks(2);
        }
        context.runOnClient(client -> require(focusedLabel(screen).equals("demo:spawn_wave:12"),
            "Right returns through the hidden call frames while keeping keyboard focus"));
    }

    private static void checkNbtNavigation(ClientGameTestContext context, CodonScreen screen,
                                           ClientDebuggerState state) {
        context.runOnClient(client -> {
            PauseSnapshot base = DebuggerPresentationGameTest.fixture(client);
            state.applyPause(new PauseSnapshot(base.location(), base.command(), base.depth(), base.callStack(),
                base.pauseSources(), base.executionFlows(), base.reason(), 91));
            state.selectSource(0);
            var executor = state.selectedSource().entity().uuid();
            var query = state.nbt().drainQueries().stream().filter(value -> value.executor().equals(executor))
                .findFirst().orElseThrow();
            List<NbtPage.Node> nodes = new ArrayList<>();
            for (int i = 0; i < 12; i++) nodes.add(new NbtPage.Node("field" + i, "\"field" + i + "\"", "{}", true));
            state.nbt().accept(query.pauseId(), query.requestId(), new NbtPage(WatchResult.Status.VALUE, nodes, 0, nodes.size()));
        });
        context.waitTicks(2);
        context.runOnClient(client -> screen.setFocused(button(screen, label -> label.startsWith("▸ field0:"))));
        for (int i = 0; i < 11; i++) {
            context.runOnClient(client -> press(client, screen, InputConstants.KEY_DOWN, InputConstants.KEYCODE_DOWN,
                0, InputType.KEYBOARD_ARROW));
            context.waitTicks(2);
        }
        context.runOnClient(client -> {
            require(focusedLabel(screen).startsWith("▸ field11:"), "Down reveals hidden NBT rows in the same column");
            press(client, screen, InputConstants.KEY_RIGHT, InputConstants.KEYCODE_RIGHT, 0, InputType.KEYBOARD_ARROW);
            require(focused(screen).icon() == DebuggerIcon.PIN, "Right visits the pin within the NBT container");
            press(client, screen, InputConstants.KEY_UP, InputConstants.KEYCODE_UP, 0, InputType.KEYBOARD_ARROW);
        });
        context.waitTicks(2);
        context.runOnClient(client -> require(focused(screen).icon() == DebuggerIcon.PIN, "NBT Up preserves the pin column"));
        context.takeScreenshot("codon-keyboard-nbt-hidden-row");
    }

    private static void checkHelpKeysAndTopicFocus(ClientGameTestContext context, CodonScreen parent,
                                                   ClientDebuggerState state) {
        DebuggerHelpScreen help = context.computeOnClient(client -> {
            DebuggerHelpScreen result = new DebuggerHelpScreen(parent, DebuggerPresentationGameTest.input(client, state));
            client.setScreenAndShow(result);
            return result;
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            DebuggerButton controls = help.children().stream().filter(DebuggerButton.class::isInstance)
                .map(DebuggerButton.class::cast).filter(button -> button.getMessage().getString().equals("Controls"))
                .findFirst().orElseThrow(() -> new AssertionError("Controls help tab is visible"));
            help.setFocused(controls);
            press(client, help, InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0, InputType.KEYBOARD_TAB);
            require(focused(help) != null && focusedLabel(help).equals("Controls"),
                "Keyboard Enter keeps focus on the rebuilt selected help tab");
            press(client, help, InputConstants.KEY_TAB, InputConstants.KEYCODE_TAB, 0, InputType.KEYBOARD_TAB);
            require(focusedLabel(help).equals("Done"), "Help Tab leaves the entire topic container");
            var close = help.getFocused();
            press(client, help, InputConstants.KEY_LEFT, InputConstants.KEYCODE_LEFT, 0, InputType.KEYBOARD_ARROW);
            require(help.getFocused() == close, "Help arrows cannot leave the close-button container");
            press(client, help, InputConstants.KEY_TAB, InputConstants.KEYCODE_TAB, InputConstants.MOD_SHIFT, InputType.KEYBOARD_TAB);
            require(focusedLabel(help).equals("Controls"), "Help restores its remembered topic focus");

            int initial = offset(help);
            press(client, help, InputConstants.KEY_END, InputConstants.KEYCODE_END, 0, InputType.KEYBOARD_ARROW);
            int end = offset(help);
            require(end > initial, "End uses the actual key constant to scroll help content");
            press(client, help, InputConstants.KEY_HOME, InputConstants.KEYCODE_HOME, 0, InputType.KEYBOARD_ARROW);
            require(offset(help) == 0, "Home returns help content to the beginning");
            press(client, help, InputConstants.KEY_PAGEDOWN, InputConstants.KEYCODE_PAGEDOWN, 0, InputType.KEYBOARD_ARROW);
            require(offset(help) > 0, "Page Down changes help scroll offset");
            press(client, help, InputConstants.KEY_PAGEUP, InputConstants.KEYCODE_PAGEUP, 0, InputType.KEYBOARD_ARROW);
            require(offset(help) == 0, "Page Up reverses help scrolling");
        });
        context.takeScreenshot("codon-keyboard-help-selected-tab");
    }

    private static void checkWatchContainers(ClientGameTestContext context, ClientDebuggerState state) {
        WatchScreen watch = context.computeOnClient(client -> {
            var result = new WatchScreen(DebuggerPresentationGameTest.input(client, state), state, new DebuggerOverlay(state));
            client.setScreenAndShow(result);
            return result;
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            EditBox field = watch.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                .filter(EditBox::isVisible).findFirst().orElseThrow();
            require(watch.getFocused() == field, "Watch editor starts at the expression field");
            field.setValue("keyboard_watch");
            watch.setFocused(field);
            press(client, watch, InputConstants.KEY_TAB, InputConstants.KEYCODE_TAB, 0, InputType.KEYBOARD_TAB);
            require(watch.getFocused() != null && watch.getFocused() != field,
                "Tab moves through the form instead of into Watch management");
            watch.setFocused(field);
            press(client, watch, InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0, InputType.KEYBOARD_ARROW);
            require(state.watches().definitions().stream().anyMatch(spec -> spec.target().equals("keyboard_watch")),
                "Enter saves a Watch expression without keyboard access to management rows");
        });
        context.takeScreenshot("codon-keyboard-watch-editor");
    }

    private static void press(Minecraft client, net.minecraft.client.gui.screens.Screen screen,
                              int key, int keyCode, int modifiers, InputType inputType) {
        client.setLastInputType(inputType);
        screen.keyPressed(new KeyEvent(key, keyCode, modifiers));
    }

    private static DebuggerButton sourceButton(CodonScreen screen, ClientDebuggerState state, int oneBasedIndex) {
        var inspector = DebuggerLayout.create(screen.width, screen.height, true).inspector();
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> button.getX() >= inspector.x() && button.getRight() <= inspector.x() + inspector.width()
                && sourceIndex(button, state) == oneBasedIndex)
            .findFirst().orElseThrow(() -> new AssertionError("Required visible inspector source is missing"));
    }

    private static int sourceIndex(DebuggerButton button, ClientDebuggerState state) {
        String label = button.getMessage().getString();
        // Dropped sources intentionally show a cross instead of an ordinal.
        if (label.startsWith("× ")) {
            for (int i = 0; i < state.displayedSources().size(); i++) {
                var source = state.displayedSources().get(i);
                if (source.entity() != null && label.equals("× " + source.entity().name())) return i + 1;
            }
        }
        if (label.startsWith("#")) return Integer.parseInt(label.substring(1, label.indexOf(' ')));
        if (label.startsWith("[")) return Integer.parseInt(label.substring(1, label.indexOf(']')));
        return -1;
    }

    private static DebuggerButton button(CodonScreen screen, java.util.function.Predicate<String> label) {
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> label.test(button.getMessage().getString())).findFirst()
            .orElseThrow(() -> new AssertionError("Required visible button is missing"));
    }

    private static DebuggerButton focused(net.minecraft.client.gui.screens.Screen screen) {
        GuiEventListener focused = screen.getFocused();
        if (!(focused instanceof DebuggerButton button)) throw new AssertionError("A debugger button should be focused");
        return button;
    }

    private static String focusedLabel(net.minecraft.client.gui.screens.Screen screen) {
        return focused(screen).getMessage().getString();
    }

    private static int offset(DebuggerHelpScreen screen) {
        try {
            Field field = DebuggerHelpScreen.class.getDeclaredField("offset");
            field.setAccessible(true);
            return field.getInt(screen);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Help scroll offset is inspectable", exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
