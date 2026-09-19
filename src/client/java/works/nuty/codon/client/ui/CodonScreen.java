package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.input.InputManager;

import java.util.List;

/** Cursor mode for the shared debugger HUD. The underlying world remains visible. */
public final class CodonScreen extends Screen {
    private final InputManager input;
    private final DebuggerOverlay overlay;
    private List<DebuggerButton> registered = List.of();

    public CodonScreen(InputManager input, DebuggerOverlay overlay) {
        super(Component.translatable("codon.ui.title"));
        this.input = input;
        this.overlay = overlay;
    }

    @Override
    protected void init() { registered = List.of(); overlay.scrollbars().release(); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        GuiEventListener focused = getFocused();
        overlay.navigation().rememberFocus(focused);
        List<DebuggerButton> buttons = overlay.render(graphics, mouseX, mouseY, partialTick, true, input);
        if (!registered.equals(buttons)) {
            setFocused(null);
            clearWidgets();
            buttons.forEach(this::addWidget);
            registered = buttons;
        }
        setFocused(overlay.navigation().restoreFocus(focused, minecraft.getLastInputType().isKeyboard()));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // No world dimming/blur: markers must retain their scene context.
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && overlay.scrollbars().click(event.x(), event.y())) {
            overlay.navigation().mouseScrolled();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && overlay.scrollbars().drag(event.x(), event.y())) return true;
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && overlay.scrollbars().release()) return true;
        return super.mouseReleased(event);
    }

    @Override
    public void removed() {
        overlay.scrollbars().release();
        super.removed();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (overlay.scroll(x, y, scrollX, scrollY)) {
            overlay.navigation().mouseScrolled();
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (input.menuKey.matches(event)) {
            while (input.menuKey.consumeClick()) { }
            onClose();
            return true;
        }
        return input.handleScreenKey(event)
            || overlay.navigation().keyPressed(event, getFocused(), this::setFocused)
            || super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean isInGameUi() { return true; }
}
