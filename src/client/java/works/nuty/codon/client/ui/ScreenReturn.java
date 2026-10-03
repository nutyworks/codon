package works.nuty.codon.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.input.InputManager;

/** Retains the originating screen and its focus while a Watch form or detail view is open. */
final class ScreenReturn {
    private final Screen parent;
    private final @Nullable GuiEventListener focused;
    private final DebuggerNavigation navigation;

    ScreenReturn(InputManager input, DebuggerOverlay overlay) {
        Screen current = Minecraft.getInstance().gui.screen();
        parent = current == null ? new CodonScreen(input, overlay) : current;
        focused = parent.getFocused();
        navigation = overlay.navigation();
        navigation.rememberFocus(focused);
    }

    void restore() {
        navigation.rememberFocus(focused);
        Minecraft.getInstance().gui.setScreen(parent);
        if (focused != null && parent.children().contains(focused)) parent.setFocused(focused);
        else if (focused instanceof AbstractWidget previous) {
            // Screen.init recreates form fields. Their semantic labels survive that rebuild;
            // HUD rows instead restore their stable navigation id on the next render.
            parent.children().stream().filter(child -> child.getClass() == previous.getClass())
                .map(AbstractWidget.class::cast)
                .filter(child -> child.visible && child.active && child.getMessage().equals(previous.getMessage()))
                .findFirst().ifPresent(parent::setFocused);
        }
    }
}
