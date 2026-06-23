package works.nuty.bastion.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import works.nuty.bastion.client.input.InputManager;

import java.util.Optional;

/**
 * Full-screen, transparent editor for the debugger windows: lets the cursor move freely to drag,
 * resize, and scroll windows without affecting the game. Opened with the menu key.
 */
public final class BastionScreen extends Screen {
    private final InputManager inputManager;
    private final WindowManager windowManager;
    private Optional<Window> latestHoveredWindow = Optional.empty();
    private Optional<Window> draggingWindow = Optional.empty();
    private Optional<Window> resizingWindow = Optional.empty();

    public BastionScreen(final InputManager inputManager, final WindowManager windowManager) {
        super(Component.empty());
        this.inputManager = inputManager;
        this.windowManager = windowManager;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        windowManager.getWindows().forEach(window -> window.render(graphics, mouseX, mouseY));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Transparent: the game world stays visible behind the windows.
    }

    @Override
    public void mouseMoved(double x, double y) {
        Optional<Window> currentHoveredWindow = windowManager.getWindows().reversed().stream()
            .filter(window -> window.checkHovered(x, y))
            .findFirst();

        latestHoveredWindow.ifPresent(l -> {
            if (l.equals(currentHoveredWindow.orElse(null))) {
                return;
            }
            l.unhovered(x, y);
        });
        currentHoveredWindow.ifPresent(w -> w.hovered(x, y));

        latestHoveredWindow = currentHoveredWindow;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        latestHoveredWindow.ifPresent(w -> {
            windowManager.bringToTop(w);
            if (w.isHeaderHovered(event.x(), event.y())) {
                draggingWindow = Optional.of(w);
            } else if (w.isResizeHovered(event.x(), event.y())) {
                resizingWindow = Optional.of(w);
            }
        });
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingWindow = Optional.empty();
        resizingWindow = Optional.empty();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        draggingWindow.ifPresent(w -> w.addXY(dx, dy));
        resizingWindow.ifPresent(w -> w.addWH(dx, dy));
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        latestHoveredWindow.ifPresent(w -> w.mouseScrolled(scrollX, scrollY));
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean isInGameUi() {
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.inputManager.menuKey.matches(event)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }
}
