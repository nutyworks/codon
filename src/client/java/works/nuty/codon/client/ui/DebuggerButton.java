package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Standard keyboard/narration behavior with Codon's compact, high-contrast chrome. */
public final class DebuggerButton extends AbstractButton {
    private Runnable action = () -> { };
    private @Nullable Runnable secondaryAction;
    private boolean selected;
    private boolean leftAligned;
    private boolean subdued;
    private boolean borderless;
    private boolean leadingIcon;
    private @Nullable DebuggerIcon icon;
    private @Nullable Tooltip tooltip;
    private int foregroundColor = DebuggerTheme.TEXT;
    private int accentColor = DebuggerTheme.TEAL;
    private int selectedSurface = DebuggerTheme.TEAL_SURFACE;

    public DebuggerButton() {
        super(0, 0, 1, 1, Component.empty());
    }

    public void configure(int x, int y, int width, int height, Component label, boolean active,
                          boolean selected, boolean leftAligned, boolean subdued, Runnable action) {
        setX(x);
        setY(y);
        setSize(width, height);
        setMessage(label);
        setTooltip(null);
        this.active = active;
        this.selected = selected;
        this.leftAligned = leftAligned;
        this.subdued = subdued;
        this.borderless = false;
        this.leadingIcon = false;
        this.action = action;
        this.secondaryAction = null;
        this.icon = null;
        this.foregroundColor = DebuggerTheme.TEXT;
        this.accentColor = DebuggerTheme.TEAL;
        this.selectedSurface = DebuggerTheme.TEAL_SURFACE;
    }

    /** Semantic source status remains visible when selected, without changing keyboard behavior. */
    public DebuggerButton withStatusColor(int foreground, int surface) {
        this.foregroundColor = this.accentColor = foreground;
        this.selectedSurface = surface;
        return this;
    }

    public int foregroundColor() { return foregroundColor; }

    public DebuggerButton withIcon(DebuggerIcon icon) {
        this.icon = icon;
        return this;
    }

    public DebuggerButton withoutChrome() {
        this.borderless = true;
        return this;
    }

    public DebuggerButton withLeadingIcon(DebuggerIcon icon) {
        this.icon = icon;
        this.leadingIcon = true;
        return this;
    }

    /** An optional right-click action; normal left-click and keyboard activation stay unchanged. */
    public DebuggerButton withSecondaryAction(Runnable action) {
        this.secondaryAction = action;
        return this;
    }

    public @Nullable DebuggerIcon icon() {
        return icon;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (active) action.run();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (secondaryAction != null && active && event.button() == InputConstants.MOUSE_BUTTON_RIGHT && isMouseOver(event.x(), event.y())) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            secondaryAction.run();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        var client = Minecraft.getInstance();
        boolean keyboardFocus = isFocused() && client.getLastInputType().isKeyboard();
        int background = selected && active ? selectedSurface
            : (isHovered() || keyboardFocus) && active ? DebuggerTheme.RAISED : DebuggerTheme.SURFACE;
        int foreground = !active || subdued ? DebuggerTheme.MUTED : foregroundColor;
        int outline = active && (selected || keyboardFocus) ? accentColor : DebuggerTheme.BORDER;
        if (borderless) {
            if (active && (isHovered() || keyboardFocus)) foreground = accentColor;
        } else {
            graphics.fill(getX(), getY(), getRight(), getBottom(), background);
            graphics.outline(getX(), getY(), width, height, outline);
        }
        if (icon != null) {
            graphics.enableScissor(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1);
            icon.draw(graphics, getX() + (leadingIcon ? 1 : (width - DebuggerIcon.SIZE) / 2),
                getY() + (height - DebuggerIcon.SIZE) / 2, foreground);
            graphics.disableScissor();
            return;
        }
        var font = client.font;
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
    public void setTooltip(@Nullable Tooltip tooltip) {
        this.tooltip = tooltip;
        super.setTooltip(tooltip);
    }

    @Override
    protected void extractTooltipForNextRenderPass(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        var client = Minecraft.getInstance();
        if (icon == null && isHovered() && client.font.width(getMessage()) > Math.max(0, width - 10)) {
            var lines = new java.util.ArrayList<>(Tooltip.splitTooltip(client, getMessage()));
            if (tooltip != null) {
                var hint = tooltip.toCharSequence(client);
                if (!hint.equals(lines)) lines.addAll(hint);
            }
            graphics.setTooltipForNextFrame(client.font, lines, mouseX, mouseY);
            return;
        }
        // Icon names remain available to narration; visual shortcut hints require an actual hover.
        if (icon == null || isHovered()) super.extractTooltipForNextRenderPass(graphics, mouseX, mouseY);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
