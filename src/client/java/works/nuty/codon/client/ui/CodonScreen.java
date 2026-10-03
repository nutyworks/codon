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
public final class CodonScreen extends ScaledCodonScreen {
    private final InputManager input;
    private final DebuggerOverlay overlay;
    private List<DebuggerButton> registered = List.of();

    public CodonScreen(InputManager input, DebuggerOverlay overlay) {
        super(Component.translatable("codon.ui.title"), overlay.preferences());
        this.input = input;
        this.overlay = overlay;
    }

    @Override
    protected void init() { input.resetUiVisibility(); overlay.commitBackgroundOpacity(); registered = List.of(); overlay.scrollbars().release(); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (input.isUiHidden()) {
            suspendPointerInteraction();
            return;
        }
        GuiEventListener focused = getFocused();
        overlay.navigation().rememberFocus(focused);
        boolean covered = ScreenLayers.get(minecraft.gui.screen()) != null;
        if (covered) setFocused(null);
        List<DebuggerButton> buttons = overlay.render(graphics, covered ? -1 : mouseX, covered ? -1 : mouseY, partialTick, true, input);
        if (!registered.equals(buttons)) {
            setFocused(null);
            clearWidgets();
            buttons.forEach(this::addWidget);
            registered = buttons;
        }
        setFocused(covered ? null : overlay.navigation().restoreFocus(focused, minecraft.getLastInputType().isKeyboard()));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // No world dimming/blur: markers must retain their scene context.
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (input.handleHideMouse(event, InputConstants.PRESS)) {
            suspendPointerInteraction();
            return true;
        }
        if (input.isUiHidden()) return true;
        if (overlay.viewMenuOpen()) {
            DebuggerButton choice = overlay.viewMenuButtonAt(event.x(), event.y());
            if (choice != null) {
                if (choice.mouseClicked(event, doubleClick)) setFocused(choice);
                return true;
            }
            if (!overlay.viewTriggerContains(event.x(), event.y())) {
                overlay.closeViewMenu();
                if (overlay.viewMenuContains(event.x(), event.y())) return true;
            }
        }
        if (overlay.watchPanel().groupingMenuOpen()) {
            DebuggerButton choice = overlay.watchPanel().groupingChoiceAt(event.x(), event.y());
            if (choice != null) {
                if (choice.mouseClicked(event, doubleClick)) setFocused(choice);
                return true;
            }
            if (!overlay.watchPanel().groupingTriggerContains(event.x(), event.y())) {
                overlay.watchPanel().closeGroupingMenu();
                if (overlay.watchPanel().groupingMenuContains(event.x(), event.y())) return true;
            }
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && overlay.scrollbars().click(event.x(), event.y())) {
            overlay.navigation().mouseScrolled();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (input.isUiHidden()) return true;
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && overlay.scrollbars().drag(event.x(), event.y())) return true;
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (input.handleHideMouse(event, InputConstants.RELEASE)) {
            return true;
        }
        if (input.isUiHidden()) return true;
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && overlay.scrollbars().release()) return true;
        return super.mouseReleased(event);
    }

    @Override
    public void removed() {
        input.resetUiVisibility();
        suspendPointerInteraction();
        overlay.closeViewMenu();
        overlay.watchPanel().closeGroupingMenu();
        super.removed();
    }

    private void suspendPointerInteraction() {
        overlay.commitBackgroundOpacity();
        overlay.scrollbars().release();
        setDragging(false);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (input.isUiHidden()) return true;
        if (overlay.scroll(x, y, scrollX, scrollY)) {
            overlay.navigation().mouseScrolled();
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!input.isUiHidden() && event.key() == InputConstants.KEY_F10 && event.hasShiftDown()
            && overlay.watchPanel().openContextMenu(getFocused(), input, overlay)) {
            while (input.breakpointKey.consumeClick()) { }
            return true;
        }
        if (input.handleHideKey(event, InputConstants.PRESS)) {
            suspendPointerInteraction();
            return true;
        }
        if (input.menuKey.matches(event)) {
            while (input.menuKey.consumeClick()) { }
            onClose();
            return true;
        }
        if (input.isUiHidden()) {
            if (event.key() == InputConstants.KEY_ESCAPE) onClose();
            else input.handleScreenKey(event);
            return true;
        }
        if (event.key() == InputConstants.KEY_ESCAPE && overlay.viewMenuOpen()) {
            overlay.closeViewMenu();
            return true;
        }
        if (event.key() == InputConstants.KEY_ESCAPE && overlay.watchPanel().groupingMenuOpen()) {
            overlay.watchPanel().closeGroupingMenu();
            return true;
        }
        if (event.key() == InputConstants.KEY_ESCAPE && overlay.closeAuxiliaryPanel()) return true;
        if (getFocused() instanceof BackgroundOpacitySlider slider && slider.keyPressed(event)) return true;
        return input.handleScreenKey(event)
            || overlay.navigation().keyPressed(event, getFocused(), this::setFocused)
            || super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (input.handleHideKey(event, InputConstants.RELEASE)) {
            return true;
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean isInGameUi() { return true; }
}
