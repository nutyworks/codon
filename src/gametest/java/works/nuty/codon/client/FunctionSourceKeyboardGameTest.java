package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.state.ClientFunctionSourceState;
import works.nuty.codon.client.state.DebuggerPreferences;
import works.nuty.codon.client.ui.FunctionSourceScreen;
import works.nuty.codon.client.ui.ScaledCodonScreen;
import works.nuty.codon.core.model.FunctionId;

import java.util.ArrayList;
import java.util.List;

/** Press/repeat/release ownership through the real Minecraft KeyboardHandler. */
@SuppressWarnings("UnstableApiUsage")
public final class FunctionSourceKeyboardGameTest implements FabricClientGameTest {
    private final List<String> failures = new ArrayList<>();

    @Override public void runTest(ClientGameTestContext context) {
        failures.clear();
        try (var world = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1280, 720);
            world.getConnection().waitForChunksRender();
            boolean originalOverlay = context.computeOnClient(client -> client.debugEntries.isOverlayVisible());
            try {
                context.runOnClient(client -> {
                    CodonClientMod.state().applyResume();
                    showSource(client);
                });
                context.waitTicks(2);
                for (boolean visible : new boolean[]{false, true}) {
                    context.runOnClient(client -> {
                        Screen screen = client.gui.screen();
                        var find = (EditBox) field(screen, "sourceSearch");
                        find.setValue("needle");
                        screen.setFocused(find);
                        client.debugEntries.setOverlayVisible(visible);
                        check(index(screen) == 0, "query starts at first result");
                        key(client, InputConstants.KEY_F3, 1, 0);
                        check(index(screen) == 1, "F3 press advances once");
                        key(client, InputConstants.KEY_F3, 0, 0);
                        check(index(screen) == 1 && client.debugEntries.isOverlayVisible() == visible,
                            "F3 release preserves result and overlay=" + visible);
                    });
                    context.waitTicks(2);
                    if (!visible) context.takeScreenshot("codon-source-f3-release");
                    context.runOnClient(client -> {
                        Screen screen = client.gui.screen();
                        client.debugEntries.setOverlayVisible(visible);
                        key(client, InputConstants.KEY_F3, 1, 1);
                        check(index(screen) == 0, "Shift+F3 press moves back once");
                        key(client, InputConstants.KEY_F3, 0, 1);
                        check(index(screen) == 0 && client.debugEntries.isOverlayVisible() == visible,
                            "Shift+F3 release preserves result and overlay=" + visible);
                        client.debugEntries.setOverlayVisible(visible);
                        key(client, InputConstants.KEY_F3, 1, 0);
                        key(client, InputConstants.KEY_F3, -1, 0);
                        key(client, InputConstants.KEY_F3, 0, 0);
                        check(index(screen) == 2 && client.debugEntries.isOverlayVisible() == visible,
                            "repeat advances once per repeat and release keeps overlay=" + visible);
                        check(!client.options.keyDebugModifier.isDown(), "Source release leaves no stuck debug modifier");
                        ((EditBox) field(screen, "sourceSearch")).setValue("");
                    });
                }
                context.runOnClient(client -> {
                    Screen screen = client.gui.screen();
                    screen.setFocused(null);
                    client.debugEntries.setOverlayVisible(false);
                    key(client, InputConstants.KEY_F3, 1, 0);
                    key(client, InputConstants.KEY_F3, 0, 0);
                    check(!client.debugEntries.isOverlayVisible(), "Source owns F3 with no Find focus/results too");
                    client.debugEntries.setOverlayVisible(false);
                    client.setScreenAndShow(null);
                    key(client, InputConstants.KEY_F3, 1, 0);
                    key(client, InputConstants.KEY_F3, 0, 0);
                    check(client.debugEntries.isOverlayVisible(), "outside Source vanilla F3 still enables overlay");
                    key(client, InputConstants.KEY_F3, 1, 1);
                    key(client, InputConstants.KEY_F3, 0, 1);
                    check(!client.debugEntries.isOverlayVisible(), "outside Source vanilla Shift+F3 still toggles overlay");
                });
                for (boolean visible : new boolean[]{false, true}) {
                    for (int modifiers : new int[]{0, 1}) closeThenRelease(context, visible, modifiers, Close.PARENT);
                }
                closeThenRelease(context, true, 1, Close.WORLD_REPEAT);
                closeThenRelease(context, false, 0, Close.LOST_RELEASE);
                closeThenRelease(context, false, 0, Close.FOREIGN_WINDOW);
            } finally {
                context.runOnClient(client -> {
                    client.setScreenAndShow(null);
                    client.debugEntries.setOverlayVisible(originalOverlay);
                });
            }
        }
        if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
    }

    private enum Close { PARENT, WORLD_REPEAT, LOST_RELEASE, FOREIGN_WINDOW }

    /** F3 pressed in Source, Source closed by a real Escape press, then F3 released elsewhere. */
    private void closeThenRelease(ClientGameTestContext context, boolean visible, int modifiers, Close close) {
        String label = (modifiers == 0 ? "F3" : "Shift+F3") + " overlay=" + visible + " " + close;
        context.runOnClient(FunctionSourceKeyboardGameTest::showSource);
        context.waitTicks(2);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            ((EditBox) field(screen, "sourceSearch")).setValue("needle");
            screen.setFocused(null);
            client.debugEntries.setOverlayVisible(visible);
            int before = index(screen);
            key(client, InputConstants.KEY_F3, 1, modifiers);
            check(index(screen) != before, label + ": Source press navigates");
            key(client, InputConstants.KEY_ESCAPE, 1, 0);
            check(!(client.gui.screen() instanceof FunctionSourceScreen), label + ": Escape closes Source");
            if (close == Close.WORLD_REPEAT) {
                client.setScreenAndShow(null);
                key(client, InputConstants.KEY_F3, -1, modifiers);
            }
            if (close == Close.FOREIGN_WINDOW) {
                client.keyboardHandler.keyPress(client.getWindow().handle() + 1, 0, new KeyEvent(InputConstants.KEY_F3, 0, modifiers));
            } else if (close != Close.LOST_RELEASE) {
                key(client, InputConstants.KEY_F3, 0, modifiers);
            }
            check(client.debugEntries.isOverlayVisible() == visible,
                label + ": release after Source closes preserves overlay, or ignores a foreign window");
            check(!client.options.keyDebugModifier.isDown(), label + ": no stuck debug modifier");
        });
        if (close == Close.PARENT && visible && modifiers == 0) {
            context.waitTicks(2);
            context.takeScreenshot("codon-source-f3-close-release");
        }
        context.runOnClient(client -> {
            client.setScreenAndShow(null);
            key(client, InputConstants.KEY_F3, 1, 0);
            key(client, InputConstants.KEY_F3, 0, 0);
            check(client.debugEntries.isOverlayVisible() == !visible, label + ": a fresh F3 outside Source toggles overlay");
            check(!client.options.keyDebugModifier.isDown(), label + ": fresh F3 leaves no stuck debug modifier");
        });
    }

    private static void showSource(Minecraft client) {
        var id = new FunctionId("codon_test", "keyboard");
        var sources = new ClientFunctionSourceState();
        sources.select(id);
        long request = sources.drainRequests().getFirst().requestId();
        sources.accept(new ClientFunctionSourceState.SourcePage(request, ClientFunctionSourceState.Status.READY,
            id, "gametest", "keyboard", false, 0, true, List.of("say needle first", "say needle second", "say needle third")));
        client.setScreenAndShow(new FunctionSourceScreen(
            new ScaledCodonScreen(Component.empty(), new DebuggerPreferences()) { }, sources));
    }

    private static void key(Minecraft client, int key, int action, int modifiers) {
        client.keyboardHandler.keyPress(client.getWindow().handle(), action, new KeyEvent(key, 0, modifiers));
    }
    private static int index(Screen screen) { return (int) field(screen, "matchIndex"); }
    private static Object field(Object target, String name) {
        try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void check(boolean condition, String message) {
        if (!condition) { failures.add(message); System.err.println("Source keyboard FAIL: " + message); }
        else System.out.println("Source keyboard PASS: " + message);
    }
}
