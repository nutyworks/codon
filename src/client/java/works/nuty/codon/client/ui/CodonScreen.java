package works.nuty.codon.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
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
    protected void init() { registered = List.of(); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        List<DebuggerButton> buttons = overlay.render(graphics, mouseX, mouseY, partialTick, true, input);
        if (!registered.equals(buttons)) {
            GuiEventListener focused = getFocused();
            setFocused(null);
            clearWidgets();
            buttons.forEach(this::addWidget);
            if (focused != null && buttons.contains(focused)) setFocused(focused);
            registered = buttons;
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // No world dimming/blur: markers must retain their scene context.
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        return overlay.scroll(x, y, scrollY) || super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (input.menuKey.matches(event)) {
            while (input.menuKey.consumeClick()) { }
            onClose();
            return true;
        }
        return input.handleScreenKey(event) || super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean isInGameUi() { return true; }
}
