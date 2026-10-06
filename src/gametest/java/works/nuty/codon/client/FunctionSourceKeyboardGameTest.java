package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.InputQuirks;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.state.ClientFunctionSourceState;
import works.nuty.codon.client.state.DebuggerPreferences;
import works.nuty.codon.client.ui.FunctionSourceScreen;
import works.nuty.codon.client.ui.ScaledCodonScreen;
import works.nuty.codon.client.ui.ScreenLayers;
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
                    var id = new FunctionId("codon_test", "keyboard");
                    var sources = new ClientFunctionSourceState();
                    sources.select(id);
                    long request = sources.drainRequests().getFirst().requestId();
                    sources.accept(new ClientFunctionSourceState.SourcePage(request, ClientFunctionSourceState.Status.READY,
                        id, "gametest", "keyboard", false, 0, true, lines()));
                    client.setScreenAndShow(new FunctionSourceScreen(
                        new ScaledCodonScreen(Component.empty(), new DebuggerPreferences()) { }, sources));
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
                verifyGoToLine(context);
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
            } finally {
                context.runOnClient(client -> {
                    client.setScreenAndShow(null);
                    client.debugEntries.setOverlayVisible(originalOverlay);
                });
            }
        }
        if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
    }

    private static List<String> lines() {
        var lines = new ArrayList<>(List.of("say needle first", "say needle second", "say needle third"));
        for (int line = 4; line <= 40; line++) lines.add("# Go to line fixture " + line);
        return lines;
    }

    private void verifyGoToLine(ClientGameTestContext context) {
        String language = context.computeOnClient(client -> client.getLanguageManager().getSelected());
        int scale = context.computeOnClient(client -> client.options.guiScale().get());
        try {
            context.getInput().resizeWindow(960, 720);
            context.runOnClient(client -> { client.options.guiScale().set(3); client.resizeGui(); });
            for (String locale : List.of("en_us", "ko_kr")) {
                var reload = context.computeOnClient(client -> {
                    client.getLanguageManager().setSelected(locale);
                    return client.reloadResourcePacks();
                });
                context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 200);
                context.runOnClient(client -> {
                    var source = (FunctionSourceScreen) client.gui.screen();
                    var sources = (ClientFunctionSourceState) field(source, "sources");
                    var find = (EditBox) field(source, "sourceSearch");
                    key(client, InputConstants.KEY_F, 1, InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER);
                    key(client, InputConstants.KEY_F, 0, InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER);
                    require(source.getFocused() == find, "Ctrl/Cmd+F still focuses Find");
                    find.setValue("");
                    var before = sources.browseView();
                    openJump(client, source);
                    var layer = ScreenLayers.get(source);
                    var number = (EditBox) field(layer, "lineNumber");
                    require(layer.getTitle().getString().equals(locale.equals("ko_kr") ? "줄로 이동" : "Go to line"),
                        "Go to line is localized: " + locale);
                    for (String invalid : List.of("", "0", "-1", "41", "1.5", "word", "999999999")) {
                        number.setValue(invalid);
                        require(!((AbstractWidget) field(layer, "go")).active, "invalid line disables Go: " + invalid);
                        key(client, InputConstants.KEY_RETURN, 1, 0);
                        key(client, InputConstants.KEY_RETURN, 0, 0);
                        require(ScreenLayers.get(source) == layer && sources.browseView().equals(before),
                            "invalid Enter keeps the draft and source viewport unchanged");
                    }
                    number.setValue("41");
                });
                context.waitTicks(2);
                context.takeScreenshot("codon-source-go-line-invalid-" + locale + "-320x240");
                context.runOnClient(client -> {
                    var source = (FunctionSourceScreen) client.gui.screen();
                    key(client, InputConstants.KEY_ESCAPE, 1, 0);
                    key(client, InputConstants.KEY_ESCAPE, 0, 0);
                    require(ScreenLayers.get(source) == null && source.getFocused() == field(source, "sourceSearch"),
                        "Escape restores the original Find focus without closing Source");
                    openJump(client, source);
                    var number = (EditBox) field(ScreenLayers.get(source), "lineNumber");
                    number.setValue("");
                    client.keyboardHandler.charTyped(client.getWindow().handle(), new CharacterEvent('3'));
                    client.keyboardHandler.charTyped(client.getWindow().handle(), new CharacterEvent('9'));
                    require(number.getValue().equals("39"), "native character input owns the line field");
                });
                context.waitTicks(2);
                context.takeScreenshot("codon-source-go-line-ready-" + locale + "-320x240");
                context.runOnClient(client -> {
                    var source = (FunctionSourceScreen) client.gui.screen();
                    var sources = (ClientFunctionSourceState) field(source, "sources");
                    var definitionState = CodonClientMod.state().breakpoints().definitions();
                    var pause = CodonClientMod.state().snapshot();
                    key(client, InputConstants.KEY_RETURN, 1, 0);
                    key(client, InputConstants.KEY_RETURN, 0, 0);
                    require(ScreenLayers.get(source) == null && source.getFocused() == null, "successful Go returns to code navigation");
                    var view = sources.browseView();
                    require(view.selectedLine() == 39 && view.selectedStageIndex() == -1 && view.lineOffset() > 0
                        && view.lineOffset() <= 38 && 38 < view.lineOffset() + sourceRows(source),
                        "Go selects and reveals the original physical line, including a comment");
                    require(CodonClientMod.state().snapshot() == pause
                        && CodonClientMod.state().breakpoints().definitions().equals(definitionState),
                        "Go changes no execution or breakpoint definition");
                });
                context.waitTicks(2);
                context.takeScreenshot("codon-source-go-line-selected-" + locale + "-320x240");
                context.runOnClient(client -> {
                    var source = (FunctionSourceScreen) client.gui.screen();
                    source.setFocused((EditBox) field(source, "sourceSearch"));
                    openJump(client, source);
                    var layer = ScreenLayers.get(source);
                    ((EditBox) field(layer, "lineNumber")).setValue("0");
                    key(client, InputConstants.KEY_TAB, 1, 0);
                    key(client, InputConstants.KEY_TAB, 0, 0);
                    require(layer.getFocused() == field(layer, "cancel"), "Tab skips disabled Go and reaches Cancel");
                    key(client, InputConstants.KEY_RETURN, 1, 0);
                    key(client, InputConstants.KEY_RETURN, 0, 0);
                    require(ScreenLayers.get(source) == null && source.getFocused() == field(source, "sourceSearch"),
                        "keyboard Cancel restores Find focus");
                    openJump(client, source);
                    ((EditBox) field(ScreenLayers.get(source), "lineNumber")).setValue("5");
                });
                context.getInput().resizeWindow(1280, 720);
                context.waitTicks(2);
                context.runOnClient(client -> {
                    var source = (FunctionSourceScreen) client.gui.screen();
                    var layer = ScreenLayers.get(source);
                    require(((EditBox) field(layer, "lineNumber")).getValue().equals("5"), "resize keeps the Go draft");
                    key(client, InputConstants.KEY_ESCAPE, 1, 0);
                    key(client, InputConstants.KEY_ESCAPE, 0, 0);
                    require(source.getFocused() == field(source, "sourceSearch"), "Cancel after resize restores the rebuilt original field");
                    var sources = (ClientFunctionSourceState) field(source, "sources");
                    var before = sources.browseView();
                    openJump(client, source);
                    ((EditBox) field(ScreenLayers.get(source), "lineNumber")).setValue("2");
                    sources.refreshSource();
                    key(client, InputConstants.KEY_RETURN, 1, 0);
                    key(client, InputConstants.KEY_RETURN, 0, 0);
                    require(ScreenLayers.get(source) == null && sources.browseView().equals(before),
                        "a reread invalidates the open input before its next tick");
                    key(client, InputConstants.KEY_G, 1, InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER);
                    key(client, InputConstants.KEY_G, 0, InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER);
                    require(ScreenLayers.get(source) == null, "a loading source cannot open Go");
                    var read = sources.drainRequests().stream().filter(ClientFunctionSourceState.Request.ReadFunction.class::isInstance)
                        .map(ClientFunctionSourceState.Request.ReadFunction.class::cast).reduce((a, b) -> b).orElseThrow();
                    sources.accept(new ClientFunctionSourceState.SourcePage(read.requestId(), ClientFunctionSourceState.Status.READY,
                        read.function(), "gametest", "keyboard-reloaded", false, 0, true, lines()));
                    openJump(client, source);
                    ((EditBox) field(ScreenLayers.get(source), "lineNumber")).setValue("1");
                    sources.reset();
                    key(client, InputConstants.KEY_RETURN, 1, 0);
                    key(client, InputConstants.KEY_RETURN, 0, 0);
                    require(ScreenLayers.get(source) == null, "a connection reset invalidates the draft");
                    sources.select(read.function());
                    var next = sources.drainRequests().getFirst();
                    sources.accept(new ClientFunctionSourceState.SourcePage(next.requestId(), ClientFunctionSourceState.Status.READY,
                        read.function(), "gametest", "keyboard", false, 0, true, lines()));
                });
                context.getInput().resizeWindow(960, 720);
                context.waitTicks(2);
                context.takeScreenshot("codon-source-go-line-restored-source-" + locale + "-320x240");
            }
        } finally {
            var reload = context.computeOnClient(client -> {
                ScreenLayers.close(ScreenLayers.get(client.gui.screen()));
                client.getLanguageManager().setSelected(language);
                client.options.guiScale().set(scale);
                client.resizeGui();
                return client.reloadResourcePacks();
            });
            context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 200);
        }
    }

    private static void openJump(Minecraft client, FunctionSourceScreen source) {
        key(client, InputConstants.KEY_G, 1, InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER);
        key(client, InputConstants.KEY_G, 0, InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER);
        require(client.gui.screen() == source && ScreenLayers.get(source) != null
            && ScreenLayers.get(source).getClass().getSimpleName().equals("SourceLineJumpScreen"),
            "Ctrl/Cmd+G opens a focused modal above Source");
    }
    private static int sourceRows(Screen source) {
        try { var method = source.getClass().getDeclaredMethod("sourceRows"); method.setAccessible(true); return (int) method.invoke(source); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("Source Go to line PASS: " + message);
    }
    private static void key(Minecraft client, int key, int action, int modifiers) {
        int keycode = key == InputConstants.KEY_G ? 'g' : key == InputConstants.KEY_F ? InputConstants.KEYCODE_F : 0;
        client.keyboardHandler.keyPress(client.getWindow().handle(), action, new KeyEvent(key, keycode, modifiers));
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
