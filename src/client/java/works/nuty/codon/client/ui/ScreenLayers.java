package works.nuty.codon.client.ui;

import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/** One modal widget layer, without replacing or reinitializing its owning screen. */
public final class ScreenLayers {
    private static @Nullable Screen owner;
    private static @Nullable Screen layer;
    private static @Nullable GuiEventListener previousFocus;

    private ScreenLayers() { }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            attach(screen);
            Screen current = get(screen);
            if (current != null) current.resize(width, height);
        });
    }

    public static void open(Screen parent, Screen content) {
        if (owner != null) close(layer);
        owner = parent;
        layer = content;
        previousFocus = parent.getFocused();
        parent.setFocused(null);
        parent.setDragging(false);
        content.init(parent.width, parent.height);
        content.triggerImmediateNarration(false);
    }

    public static @Nullable Screen get(@Nullable Screen parent) {
        return owner == parent ? layer : null;
    }

    public static void close(@Nullable Screen content) {
        if (content == null || content != layer) return;
        Screen parent = owner;
        GuiEventListener focus = previousFocus;
        owner = null;
        layer = null;
        previousFocus = null;
        content.removed();
        if (parent != null) {
            parent.setDragging(false);
            if (focus != null && parent.children().contains(focus)) parent.setFocused(focus);
        }
    }

    private static boolean allowParent(Screen parent, Consumer<Screen> action) {
        Screen current = get(parent);
        if (current == null) return true;
        action.accept(current);
        return false;
    }

    private static void attach(Screen screen) {
        ScreenEvents.remove(screen).register(parent -> close(get(parent)));
        ScreenEvents.afterTick(screen).register(parent -> {
            Screen current = get(parent);
            if (current != null) current.tick();
        });
        ScreenEvents.afterExtract(screen).register((parent, graphics, x, y, delta) -> {
            Screen current = get(parent);
            if (current != null) current.extractRenderStateWithTooltipAndSubtitles(graphics, x, y, delta);
        });
        ScreenMouseEvents.allowMouseClick(screen).register((parent, event) -> allowParent(parent, current -> {
            current.mouseClicked(event, false);
            current.afterMouseAction();
        }));
        ScreenMouseEvents.allowMouseRelease(screen).register((parent, event) ->
            allowParent(parent, current -> current.mouseReleased(event)));
        ScreenMouseEvents.allowMouseDrag(screen).register((parent, event, dx, dy) ->
            allowParent(parent, current -> current.mouseDragged(event, dx, dy)));
        ScreenMouseEvents.allowMouseScroll(screen).register((parent, x, y, dx, dy) ->
            allowParent(parent, current -> current.mouseScrolled(x, y, dx, dy)));
        ScreenKeyboardEvents.allowKeyPress(screen).register((parent, event) -> allowParent(parent, current -> {
            current.keyPressed(event);
            current.afterKeyboardAction();
        }));
        ScreenKeyboardEvents.allowKeyRelease(screen).register((parent, event) ->
            allowParent(parent, current -> current.keyReleased(event)));
        ScreenKeyboardEvents.allowCharType(screen).register((parent, event) -> allowParent(parent, current -> {
            current.charTyped(event);
            current.afterKeyboardAction();
        }));
    }
}
