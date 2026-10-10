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
    private static final FunctionId ONLY = new FunctionId("codon_test", "enter/only_match");
    private static final List<FunctionId> ENTER_FUNCTIONS = List.of(ONLY,
        new FunctionId("codon_test", "enter/shared_a"), new FunctionId("codon_test", "enter/shared_b"));

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
                verifyUniqueEnter(context, input.menuKey);
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

    /**
     * Enter in Functions Search at the docked wide layout, then the compact drawer. Return is bound to the parent's
     * menu key so a leaked press closes cursor mode. The function list and source are synthetic client fixtures.
     */
    private static void verifyUniqueEnter(ClientGameTestContext context, KeyMapping menuKey) {
        var wide = context.computeOnClient(client -> {
            bind(menuKey, InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_RETURN));
            return openFunctions(client, 8, ENTER_FUNCTIONS);
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            var source = (FunctionSourceScreen) client.gui.screen();
            var search = (EditBox) field(source, "search");
            require((boolean) field(source, "docked") && !(boolean) field(source, "drawerMode")
                && source.getFocused() == search, "wide fixture has the docked parent and a focused Search");
            search.setValue("shared");
            tap(client, InputConstants.KEY_RETURN);
            require(reads(wide).isEmpty() && wide.selected() == null && client.gui.screen() == source
                && source.getFocused() == search, "two matches consume Return without a selection, read or parent shortcut");
            search.setValue("no_such_function");
            tap(client, InputConstants.KEY_NUMPADENTER);
            require(reads(wide).isEmpty() && wide.selected() == null && client.gui.screen() == source,
                "zero matches consume numpad Enter without a selection or read");
            search.setValue("only_match");
            require(((List<?>) field(source, "entries")).size() == 3, "the sole function sits below namespace and folder rows");
            key(client, InputConstants.KEY_RETURN, InputConstants.PRESS, 0);
            require(isOnlyRead(reads(wide)) && ONLY.equals(wide.selected()) && source.getFocused() == search,
                "Return opens the exact sole function with one read and keeps wide Search focused");
            key(client, InputConstants.KEY_RETURN, InputConstants.REPEAT, 0);
            source.setFocused(null);
            key(client, InputConstants.KEY_RETURN, InputConstants.REPEAT, 0);
            require(reads(wide).isEmpty() && client.gui.screen() == source,
                "Return repeats neither read again nor reach parent shortcuts, even after focus leaves Search");
            key(client, InputConstants.KEY_RETURN, InputConstants.RELEASE, 0);
            source.setFocused(search);
            key(client, InputConstants.KEY_NUMPADENTER, InputConstants.PRESS, 0);
            require(isOnlyRead(reads(wide)), "numpad Enter opens it as a new action after Return was released");
            key(client, InputConstants.KEY_RETURN, InputConstants.PRESS, 0);
            require(isOnlyRead(reads(wide)), "Return is an independent action while numpad Enter is still held");
            key(client, InputConstants.KEY_RETURN, InputConstants.RELEASE, 0);
            key(client, InputConstants.KEY_NUMPADENTER, InputConstants.REPEAT, 0);
            require(reads(wide).isEmpty(), "releasing Return leaves the numpad Enter repeat owned");
            key(client, InputConstants.KEY_NUMPADENTER, InputConstants.RELEASE, 0);
            key(client, InputConstants.KEY_RETURN, InputConstants.PRESS, 0);
            var read = reads(wide);
            require(isOnlyRead(read), "a Return press after release acts again");
            key(client, InputConstants.KEY_RETURN, InputConstants.RELEASE, 0);
            acceptOnly(wide, read.getFirst());
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-source-unique-enter-wide");
        context.runOnClient(client -> {
            var source = (FunctionSourceScreen) client.gui.screen();
            var find = (EditBox) field(source, "sourceSearch");
            find.setValue("synthetic");
            source.setFocused(find);
            tap(client, InputConstants.KEY_RETURN);
            require((int) field(source, "matchIndex") == 1 && reads(wide).isEmpty() && ONLY.equals(wide.selected()),
                "Find Enter still advances its matches instead of opening the Search result");
            source.setFocused((EditBox) field(source, "search"));
            key(client, InputConstants.KEY_RETURN, InputConstants.PRESS, 0);
            require(isOnlyRead(reads(wide)), "Return opens the function before Source is removed");
            client.setScreenAndShow(null);
            client.setScreenAndShow(source);
            key(client, InputConstants.KEY_RETURN, InputConstants.PRESS, 0);
            require(isOnlyRead(reads(wide)), "removal clears a Return whose release was never delivered");
            key(client, InputConstants.KEY_RETURN, InputConstants.RELEASE, 0);
            source.setFocused(null);
            key(client, InputConstants.KEY_RETURN, InputConstants.PRESS, 0);
            require(client.gui.screen() == null, "a fresh Return with no focused field still reaches the docked parent");
            key(client, InputConstants.KEY_RETURN, InputConstants.RELEASE, 0);
        });

        var compact = context.computeOnClient(client -> openFunctions(client, 10, List.of(ONLY)));
        context.waitTicks(2);
        context.runOnClient(client -> {
            var source = (FunctionSourceScreen) client.gui.screen();
            var search = (EditBox) field(source, "search");
            require((boolean) field(source, "drawerMode") && (boolean) field(source, "drawerOpen")
                && source.getFocused() == search, "compact fixture opens with a focused Functions drawer");
            // Empty, ASCII spaces (the sole function stays listed) and a literal U+2003 EM SPACE (Java whitespace, no row).
            for (String blank : new String[] {"", "   ", " "}) {
                search.setValue(blank);
                tap(client, InputConstants.KEY_RETURN);
                require(reads(compact).isEmpty() && compact.selected() == null && (boolean) field(source, "drawerOpen")
                    && source.getFocused() == search, "blank query of length " + blank.length() + " consumes Return");
            }
            search.setValue("only_match");
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-source-unique-enter-compact-drawer");
        context.runOnClient(client -> {
            var source = (FunctionSourceScreen) client.gui.screen();
            key(client, InputConstants.KEY_RETURN, InputConstants.PRESS, 0);
            var read = reads(compact);
            var hidden = (EditBox) field(source, "search");
            require(isOnlyRead(read) && ONLY.equals(compact.selected()), "compact Return opens the exact sole function once");
            require(!(boolean) field(source, "drawerOpen") && !hidden.visible && source.getFocused() == null,
                "compact selection closes the drawer without a hidden focused Search");
            key(client, InputConstants.KEY_RETURN, InputConstants.REPEAT, 0);
            key(client, InputConstants.KEY_RETURN, InputConstants.REPEAT, 0);
            require(reads(compact).isEmpty() && client.gui.screen() == source, "compact repeats neither read nor leak");
            acceptOnly(compact, read.getFirst());
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-source-unique-enter-compact-source");
        context.getInput().resizeWindow(1600, 900);
        context.waitTicks(2);
        context.runOnClient(client -> {
            var source = (FunctionSourceScreen) client.gui.screen();
            require(!(boolean) field(source, "drawerMode") && source.getFocused() == field(source, "search"),
                "resizing to the wide layout restores Search focus while Return is still held");
            key(client, InputConstants.KEY_RETURN, InputConstants.REPEAT, 0);
            require(reads(compact).isEmpty() && compact.document() != null, "the held Return keeps ownership across resize");
            key(client, InputConstants.KEY_RETURN, InputConstants.RELEASE, 0);
            key(client, InputConstants.KEY_RETURN, InputConstants.PRESS, 0);
            require(isOnlyRead(reads(compact)), "a Return press after release opens it again");
            key(client, InputConstants.KEY_RETURN, InputConstants.RELEASE, 0);
        });
        context.getInput().resizeWindow(1280, 720);
    }

    private static ClientFunctionSourceState openFunctions(Minecraft client, int scale, List<FunctionId> functions) {
        var preferences = new DebuggerPreferences();
        preferences.setCustomUiScale(scale);
        preferences.setUiScaleMode(DebuggerPreferences.UiScaleMode.CUSTOM);
        var state = new works.nuty.codon.client.state.ClientDebuggerState(preferences);
        var parent = new CodonScreen(CodonClientMod.input(), new DebuggerOverlay(state));
        var sources = new ClientFunctionSourceState();
        client.setScreenAndShow(new FunctionSourceScreen(parent, sources));
        var request = sources.drainRequests().stream().filter(ClientFunctionSourceState.Request.ListFunctions.class::isInstance)
            .reduce((first, second) -> second).orElseThrow();
        sources.accept(new ClientFunctionSourceState.ListPage(request.requestId(), ClientFunctionSourceState.Status.READY,
            0, true, functions));
        return sources;
    }

    private static List<ClientFunctionSourceState.Request.ReadFunction> reads(ClientFunctionSourceState sources) {
        return sources.drainRequests().stream().filter(ClientFunctionSourceState.Request.ReadFunction.class::isInstance)
            .map(ClientFunctionSourceState.Request.ReadFunction.class::cast).toList();
    }

    private static boolean isOnlyRead(List<ClientFunctionSourceState.Request.ReadFunction> reads) {
        return reads.size() == 1 && reads.getFirst().function().equals(ONLY);
    }

    private static void acceptOnly(ClientFunctionSourceState sources, ClientFunctionSourceState.Request.ReadFunction read) {
        sources.accept(new ClientFunctionSourceState.SourcePage(read.requestId(), ClientFunctionSourceState.Status.READY,
            ONLY, "gametest", "unique_enter", false, 0, true, List.of(
                "# synthetic fixture for unique-result Enter; not a datapack file",
                "say synthetic only_match opened by Enter")));
    }

    private static void tap(Minecraft client, int key) {
        key(client, key, InputConstants.PRESS, 0);
        key(client, key, InputConstants.RELEASE, 0);
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
