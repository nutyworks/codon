package works.nuty.bastion.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** Standard keyboard/narration behavior with Bastion's compact, high-contrast chrome. */
public final class DebuggerButton extends AbstractButton {
    private Runnable action = () -> { };
    private boolean selected;
    private boolean leftAligned;
    private boolean subdued;

    public DebuggerButton() {
        super(0, 0, 1, 1, Component.empty());
    }

    public void configure(int x, int y, int width, int height, Component label, boolean active,
                          boolean selected, boolean leftAligned, boolean subdued, Runnable action) {
        setX(x);
        setY(y);
        setSize(width, height);
        setMessage(label);
        this.active = active;
        this.selected = selected;
        this.leftAligned = leftAligned;
        this.subdued = subdued;
        this.action = action;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (active) action.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int background = selected ? DebuggerTheme.TEAL_SURFACE
            : isHoveredOrFocused() && active ? DebuggerTheme.RAISED : DebuggerTheme.SURFACE;
        int foreground = !active || subdued ? DebuggerTheme.MUTED : DebuggerTheme.TEXT;
        int outline = selected || isFocused() ? DebuggerTheme.TEAL : DebuggerTheme.BORDER;
        graphics.fill(getX(), getY(), getRight(), getBottom(), background);
        graphics.outline(getX(), getY(), width, height, outline);
        var font = Minecraft.getInstance().font;
        String full = getMessage().getString();
        int available = Math.max(0, width - 10);
        String text = font.width(full) <= available ? full
            : font.plainSubstrByWidth(full, Math.max(0, available - font.width("…"))) + "…";
        graphics.enableScissor(getX() + 2, getY(), getRight() - 2, getBottom());
        graphics.text(font, text, leftAligned ? getX() + 5 : getX() + (width - font.width(text)) / 2,
            getY() + (height - font.lineHeight) / 2 + 1, foreground, false);
        graphics.disableScissor();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
