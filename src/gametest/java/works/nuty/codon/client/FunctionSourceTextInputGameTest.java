package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.InputQuirks;
import works.nuty.codon.client.state.ClientFunctionSourceState;
import works.nuty.codon.client.state.DebuggerPreferences;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.client.ui.FunctionSourceScreen;
import works.nuty.codon.core.model.FunctionId;

import java.util.List;

/** Actual KeyboardHandler dispatch with keyPress preceding charTyped; source data is a client fixture. */
@SuppressWarnings("UnstableApiUsage")
public final class FunctionSourceTextInputGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1280, 720);
            world.getConnection().waitForChunksRender();
            var input = CodonClientMod.input();
            var menuBinding = context.computeOnClient(client -> KeyMappingHelper.getBoundKeyOf(input.menuKey));
            boolean keepFreecam = CodonClientMod.state().preferences().keepFreecam();
            try {
                context.runOnClient(client -> {
                    CodonClientMod.state().applyResume();
                    bind(input.menuKey, InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_V));
                    open(client);
                });
                context.waitTicks(2);
                context.runOnClient(client -> {
                    var source = (FunctionSourceScreen) client.gui.screen();
                    require((boolean) field(source, "docked"), "fixture uses the real docked Codon parent");
                    var find = (EditBox) field(source, "sourceSearch");
                    find.setValue("held_h");
                    find.moveCursorToEnd(false);
                    source.setFocused(find);
                });
                context.takeScreenshot("codon-source-text-before-v");
                context.runOnClient(client -> {
                    var source = (FunctionSourceScreen) client.gui.screen();
                    var find = (EditBox) field(source, "sourceSearch");
                    type(client, source, InputConstants.KEY_V, 'v', InputConstants.PRESS);
                    require(find.getValue().equals("held_hv"), "bound V reaches Find after its key press");
                    type(client, source, InputConstants.KEY_V, 'v', InputConstants.REPEAT);
                    key(client, InputConstants.KEY_V, InputConstants.RELEASE, 0);
                    require(find.getValue().equals("held_hvv"), "repeat inserts once; release inserts nothing");
                    type(client, source, InputConstants.KEY_H, 'h', InputConstants.PRESS);
                    key(client, InputConstants.KEY_H, InputConstants.RELEASE, 0);
                    type(client, source, InputConstants.KEY_G, 'g', InputConstants.PRESS);
                    key(client, InputConstants.KEY_G, InputConstants.RELEASE, 0);
                    require(!input.isUiHidden(), "Find H cannot hide UI");
                    require(CodonClientMod.state().preferences().keepFreecam() == keepFreecam,
                        "Find G cannot toggle Keep Freecam");
                    require(find.getValue().equals("held_hvvhg"), "other printable shortcuts insert into Find");
                    int selected = (int) field(source, "selectedLine");
                    key(client, InputConstants.KEY_LEFT, InputConstants.PRESS, 0);
                    key(client, InputConstants.KEY_LEFT, InputConstants.RELEASE, 0);
                    require(find.getCursorPosition() == find.getValue().length() - 1,
                        "field retains ordinary cursor navigation");
                    require((int) field(source, "selectedLine") == selected, "field navigation leaves code selection alone");
                    bind(input.menuKey, InputConstants.UNKNOWN);
                    find.moveCursorToEnd(false);
                    type(client, source, InputConstants.KEY_V, 'v', InputConstants.PRESS);
                    key(client, InputConstants.KEY_V, InputConstants.RELEASE, 0);
                    require(find.getValue().equals("held_hvvhgv"), "unbound V still inserts");
                    bind(input.menuKey, InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_J));
                    type(client, source, InputConstants.KEY_J, 'j', InputConstants.PRESS);
                    key(client, InputConstants.KEY_J, InputConstants.RELEASE, 0);
                    require(find.getValue().equals("held_hvvhgvj"), "remapped cursor-mode key still inserts");
                    var search = (EditBox) field(source, "search");
                    search.setValue("");
                    source.setFocused(search);
                    type(client, source, InputConstants.KEY_J, 'j', InputConstants.PRESS);
                    key(client, InputConstants.KEY_J, InputConstants.RELEASE, 0);
                    require(search.getValue().equals("j"), "function-list Search also owns printable shortcuts");
                    search.setValue("");
                    source.setFocused(find);
                    find.setValue("needle");
                    key(client, InputConstants.KEY_RETURN, InputConstants.PRESS, 0);
                    key(client, InputConstants.KEY_RETURN, InputConstants.RELEASE, 0);
                    require((int) field(source, "matchIndex") == 1, "Find Enter advances");
                    key(client, InputConstants.KEY_RETURN, InputConstants.PRESS, 1);
                    key(client, InputConstants.KEY_RETURN, InputConstants.RELEASE, 1);
                    require((int) field(source, "matchIndex") == 0, "Find Shift+Enter reverses");
                    key(client, InputConstants.KEY_ESCAPE, InputConstants.PRESS, 0);
                    key(client, InputConstants.KEY_ESCAPE, InputConstants.RELEASE, 0);
                    require(client.gui.screen() == source && source.getFocused() == null, "Escape leaves Find for code navigation");
                    key(client, InputConstants.KEY_F, InputConstants.PRESS, controlModifier());
                    key(client, InputConstants.KEY_F, InputConstants.RELEASE, controlModifier());
                    require(source.getFocused() == find, "Ctrl/Cmd+F returns focus to Find");
                    key(client, InputConstants.KEY_TAB, InputConstants.PRESS, 0);
                    key(client, InputConstants.KEY_TAB, InputConstants.RELEASE, 0);
                    require(source.getFocused() != find, "Tab moves focus out of Find");
                    key(client, InputConstants.KEY_TAB, InputConstants.PRESS, 1);
                    key(client, InputConstants.KEY_TAB, InputConstants.RELEASE, 1);
                    require(source.getFocused() == find, "Shift+Tab returns to Find");
                    bind(input.menuKey, InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_V));
                    find.setValue("held_hv");
                });
                context.waitTicks(2);
                context.takeScreenshot("codon-source-text-after-v");
                context.runOnClient(client -> {
                    var source = (FunctionSourceScreen) client.gui.screen();
                    source.setFocused(null);
                    key(client, InputConstants.KEY_G, InputConstants.PRESS, 0);
                    key(client, InputConstants.KEY_G, InputConstants.RELEASE, 0);
                    require(CodonClientMod.state().preferences().keepFreecam() != keepFreecam,
                        "unfocused Source still forwards parent shortcuts");
                    source.setFocused((AbstractWidget) field(source, "refresh"));
                    key(client, InputConstants.KEY_V, InputConstants.PRESS, 0);
                    key(client, InputConstants.KEY_V, InputConstants.RELEASE, 0);
                    require(client.gui.screen() == null, "button focus still lets bound V close cursor mode");
                });
            } finally {
                context.runOnClient(client -> {
                    client.setScreenAndShow(null);
                    bind(input.menuKey, menuBinding);
                    CodonClientMod.state().preferences().setKeepFreecam(keepFreecam);
                    input.resetUiVisibility();
                });
            }
        }
    }

    private static void open(Minecraft client) {
        var preferences = new DebuggerPreferences();
        preferences.setCustomUiScale(8);
        preferences.setUiScaleMode(DebuggerPreferences.UiScaleMode.CUSTOM);
        var state = new works.nuty.codon.client.state.ClientDebuggerState(preferences);
        var parent = new CodonScreen(CodonClientMod.input(), new DebuggerOverlay(state));
        var id = new FunctionId("codon_test", "text_input");
        var sources = new ClientFunctionSourceState();
        sources.select(id);
        long request = sources.drainRequests().getFirst().requestId();
        sources.accept(new ClientFunctionSourceState.SourcePage(request, ClientFunctionSourceState.Status.READY,
            id, "gametest", "text_input", false, 0, true, List.of("say needle first", "say needle second")));
        client.setScreenAndShow(new FunctionSourceScreen(parent, sources));
    }

    private static void type(Minecraft client, FunctionSourceScreen source, int key, char character, int action) {
        key(client, key, action, 0);
        require(client.gui.screen() == source, "key press must retain Source before charTyped: " + character);
        client.keyboardHandler.charTyped(client.getWindow().handle(), new CharacterEvent(character));
        require(client.gui.screen() == source, "charTyped must retain Source: " + character);
    }

    private static void key(Minecraft client, int key, int action, int modifiers) {
        int keycode = switch (key) {
            case InputConstants.KEY_V -> InputConstants.KEYCODE_V;
            case InputConstants.KEY_H -> 'h';
            case InputConstants.KEY_G -> 'g';
            case InputConstants.KEY_J -> 'j';
            case InputConstants.KEY_F -> InputConstants.KEYCODE_F;
            case InputConstants.KEY_LEFT -> InputConstants.KEYCODE_LEFT;
            case InputConstants.KEY_RETURN -> InputConstants.KEYCODE_RETURN;
            case InputConstants.KEY_TAB -> InputConstants.KEYCODE_TAB;
            default -> 0;
        };
        client.keyboardHandler.keyPress(client.getWindow().handle(), action, new KeyEvent(key, keycode, modifiers));
    }
    private static int controlModifier() { return InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER; }
    private static void bind(KeyMapping mapping, InputConstants.Key key) { mapping.setKey(key); KeyMapping.resetMapping(); }
    private static Object field(Object target, String name) {
        try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("Source text input PASS: " + message);
    }
}
