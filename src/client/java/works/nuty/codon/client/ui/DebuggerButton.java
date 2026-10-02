package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.tooltip.BelowOrAboveWidgetTooltipPositioner;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Standard keyboard/narration behavior with Codon's compact, high-contrast chrome. */
public class DebuggerButton extends AbstractButton {
    public static final int TEXT_ICON_INSET = DebuggerIcon.SIZE + 2;
    private Runnable action = () -> { };
    private @Nullable Runnable secondaryAction;
    private boolean selected;
    private boolean opaqueColors;
    private boolean inputBlocked;
    private boolean leftAligned;
    private boolean subdued;
    private boolean borderless;
    private boolean flatChrome;
    private boolean hitSurface;
    private boolean leadingIcon;
    private boolean iconWithText;
    private boolean openLeft;
    private boolean openRight;
    private boolean revealOnHover;
    private int revealX;
    private int revealY;
    private int revealWidth;
    private int revealHeight;
    private int contentOffset;
    private int contentWidth;
    private int textPadding = 10;
    private long hoverStartedAt = -1;
    private static final long HOVER_DELAY_NANOS = 350_000_000L;
    private @Nullable DebuggerIcon icon;
    private boolean smallIcon;
    private int iconOffsetY;
    private @Nullable Tooltip tooltip;
    private @Nullable Component singleLineTooltip;
    private int foregroundColor = DebuggerTheme.TEXT;
    private int accentColor = DebuggerTheme.TEAL;
    private int selectedSurface = DebuggerTheme.TEAL_SURFACE;

    public DebuggerButton() {
        super(0, 0, 1, 1, Component.empty());
    }

    public void configure(int x, int y, int width, int height, Component label, boolean active,
                          boolean selected, boolean leftAligned, boolean subdued, Runnable action) {
        if (x != getX() || y != getY() || width != getWidth() || height != getHeight()
            || !label.equals(getMessage())) hoverStartedAt = -1;
        setX(x);
        setY(y);
        setSize(width, height);
        setMessage(label);
        setTooltip(null);
        this.singleLineTooltip = null;
        this.active = active;
        this.inputBlocked = false;
        this.selected = selected;
        this.opaqueColors = false;
        this.leftAligned = leftAligned;
        this.subdued = subdued;
        this.borderless = false;
        this.flatChrome = false;
        this.hitSurface = false;
        this.leadingIcon = false;
        this.iconWithText = false;
        this.openLeft = false;
        this.openRight = false;
        this.revealOnHover = false;
        this.contentOffset = 0;
        this.contentWidth = width;
        this.textPadding = 10;
        this.action = action;
        this.secondaryAction = null;
        this.icon = null;
        this.smallIcon = false;
        this.iconOffsetY = 0;
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

    public DebuggerButton withOpaqueColors() { opaqueColors = true; return this; }

    public int foregroundColor() { return foregroundColor; }

    public void setSelected(boolean selected) { this.selected = selected; }

    public DebuggerButton withIcon(DebuggerIcon icon) {
        this.icon = icon;
        return this;
    }

    public DebuggerButton withSmallIcon(DebuggerIcon icon) {
        this.icon = icon;
        this.smallIcon = true;
        return this;
    }

    public DebuggerButton withIconOffsetY(int offset) {
        this.iconOffsetY = offset;
        return this;
    }

    public DebuggerButton withoutChrome() {
        this.borderless = true;
        return this;
    }

    /** Quiet idle rows/actions; selection and keyboard focus remain separate visible states. */
    public DebuggerButton withFlatChrome() {
        this.flatChrome = true;
        return this;
    }

    public DebuggerButton withTextPadding(int padding) {
        this.textPadding = padding;
        return this;
    }

    /** Keep the click target, but show an unused breakpoint marker only over its command clause. */
    public DebuggerButton revealOnHover(int x, int y, int width, int height) {
        this.revealOnHover = true;
        this.revealX = x;
        this.revealY = y;
        this.revealWidth = width;
        this.revealHeight = height;
        return this;
    }

    public DebuggerButton withSingleLineTooltip(Component text) {
        this.singleLineTooltip = text;
        return this;
    }

    /** Keyboard/narration target for content rendered by the owning panel. */
    public DebuggerButton asHitSurface() {
        this.hitSurface = true;
        return this;
    }

    /** Open edges connect fragments of the same wrapped command clause. */
    public DebuggerButton withOpenEdges(boolean left, boolean right) {
        this.openLeft = left;
        this.openRight = right;
        return this;
    }

    public DebuggerButton withLeadingIcon(DebuggerIcon icon) {
        this.icon = icon;
        this.leadingIcon = true;
        return this;
    }

    public DebuggerButton withTextIcon(DebuggerIcon icon) {
        this.icon = icon;
        this.iconWithText = true;
        return this;
    }

    /** Keeps the full breadcrumb text while the widget's hit box is clipped to its viewport. */
    public DebuggerButton withHorizontalViewport(int offset, int fullWidth) {
        this.contentOffset = offset;
        this.contentWidth = fullWidth;
        return this;
    }

    /** An optional right-click action; normal left-click and keyboard activation stay unchanged. */
    public DebuggerButton withSecondaryAction(Runnable action) {
        this.secondaryAction = action;
        return this;
    }

    /** Keep appearance and focus stable during a short data refresh without accepting actions. */
    public DebuggerButton withInputBlocked(boolean blocked) {
        this.inputBlocked = blocked;
        return this;
    }

    public boolean inputBlocked() { return inputBlocked; }

    public @Nullable DebuggerIcon icon() {
        return icon;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (active && !inputBlocked) action.run();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (inputBlocked) return active && isMouseOver(event.x(), event.y());
        if (secondaryAction != null && active && event.button() == InputConstants.MOUSE_BUTTON_RIGHT && isMouseOver(event.x(), event.y())) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            secondaryAction.run();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    protected void handleCursor(GuiGraphicsExtractor graphics) {
        if (inputBlocked && isHovered()) graphics.requestCursor(CursorTypes.NOT_ALLOWED);
        else super.handleCursor(graphics);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        var client = Minecraft.getInstance();
        boolean keyboardFocus = isFocused() && client.getLastInputType().isKeyboard();
        if (revealOnHover && !keyboardFocus && (ScreenLayers.get(client.gui.screen()) != null
            || mouseX < revealX || mouseX >= revealX + revealWidth
            || mouseY < revealY || mouseY >= revealY + revealHeight)) return;
        if (hitSurface) {
            // The owner paints hover/focus backgrounds before its row text.
            if (active && keyboardFocus) DebuggerTheme.focusMark(graphics, getX() + 1, getY() + 1);
            return;
        }
        int background = selected && active ? selectedSurface
            : (isHovered() || keyboardFocus) && active ? DebuggerTheme.RAISED : DebuggerTheme.SURFACE;
        int foreground = !active || subdued ? DebuggerTheme.MUTED : foregroundColor;
        int outline = paintColor(active && (selected || isHovered()) ? accentColor : DebuggerTheme.BORDER);
        if (borderless) {
            if (active && (isHovered() || keyboardFocus)) foreground = accentColor;
        } else if (flatChrome) {
            if (active && (selected || isHovered() || keyboardFocus))
                graphics.fill(getX(), getY(), getRight(), getBottom(), paintColor(background));
            if (active && selected)
                graphics.fill(getX(), getBottom() - 1, getRight(), getBottom(), DebuggerTheme.foreground(accentColor));
        } else {
            graphics.fill(getX(), getY(), getRight(), getBottom(), paintColor(background));
            graphics.fill(getX(), getY(), getRight(), getY() + 1, outline);
            graphics.fill(getX(), getBottom() - 1, getRight(), getBottom(), outline);
            if (!openLeft) graphics.fill(getX(), getY(), getX() + 1, getBottom(), outline);
            if (!openRight) graphics.fill(getRight() - 1, getY(), getRight(), getBottom(), outline);
        }
        if (icon != null) {
            graphics.enableScissor(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1);
            int iconSize = smallIcon ? icon.smallSize() : DebuggerIcon.SIZE;
            int iconX = getX() - contentOffset + (iconWithText ? 3 : leadingIcon ? 1 : (contentWidth - iconSize) / 2);
            int iconY = getY() + (height - iconSize) / 2 + iconOffsetY;
            if (smallIcon) icon.drawSmall(graphics, iconX, iconY, DebuggerTheme.foreground(foreground));
            else icon.draw(graphics, iconX, iconY, DebuggerTheme.foreground(foreground));
            graphics.disableScissor();
            if (!iconWithText) {
                if (active && keyboardFocus) DebuggerTheme.focusMark(graphics, getX() + 1, getY() + 1);
                return;
            }
        }
        var font = client.font;
        String full = getMessage().getString();
        int inset = iconWithText ? TEXT_ICON_INSET : 0;
        int available = Math.max(0, contentWidth - textPadding - inset);
        String text = font.width(full) <= available ? full
            : font.plainSubstrByWidth(full, Math.max(0, available - font.width("…"))) + "…";
        graphics.enableScissor(getX() + 2, getY(), getRight() - 2, getBottom());
        graphics.text(font, text, leftAligned ? getX() - contentOffset + textPadding / 2 + inset
            : getX() - contentOffset + inset + (contentWidth - inset - font.width(text)) / 2,
            getY() + (height - font.lineHeight) / 2 + 1, DebuggerTheme.foreground(foreground), false);
        graphics.disableScissor();
        if (active && keyboardFocus) DebuggerTheme.focusMark(graphics, getX() + 1, getY() + 1);
    }

    private int paintColor(int color) { return opaqueColors ? color : DebuggerTheme.color(color); }

    @Override
    public void setTooltip(@Nullable Tooltip tooltip) {
        this.tooltip = tooltip;
        super.setTooltip(tooltip);
    }

    @Override
    protected void extractTooltipForNextRenderPass(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // Modal layers and covered menu controls deliberately receive no pointer.
        if (mouseX < 0 && mouseY < 0) { hoverStartedAt = -1; return; }
        var client = Minecraft.getInstance();
        boolean keyboardFocus = isFocused() && client.getLastInputType().isKeyboard();
        if (!isHovered()) hoverStartedAt = -1;
        else if (hoverStartedAt < 0) hoverStartedAt = System.nanoTime();
        if (!keyboardFocus && (!isHovered() || System.nanoTime() - hoverStartedAt < HOVER_DELAY_NANOS)) return;
        if (singleLineTooltip != null) {
            showTooltip(graphics, java.util.List.of(singleLineTooltip.getVisualOrderText()), mouseX, mouseY, keyboardFocus);
            return;
        }
        boolean iconOnly = icon != null && !iconWithText;
        boolean clipped = !hitSurface && client.font.width(getMessage()) > Math.max(0,
            width - textPadding - (iconWithText ? TEXT_ICON_INSET : 0));
        var label = Tooltip.splitTooltip(client, getMessage());
        var lines = new java.util.ArrayList<net.minecraft.util.FormattedCharSequence>();
        if (iconOnly || clipped) lines.addAll(label);
        if (tooltip != null) {
            var hint = tooltip.toCharSequence(client);
            // A fully visible label needs no echo. Hit surfaces render their own content.
            if (hitSurface || !tooltipText(hint).equals(tooltipText(label))) lines.addAll(hint);
        }
        if (!lines.isEmpty()) showTooltip(graphics, lines, mouseX, mouseY, keyboardFocus);
    }

    // FormattedCharSequence instances use identity equality, even for identical labels.
    private static String tooltipText(java.util.List<net.minecraft.util.FormattedCharSequence> lines) {
        StringBuilder text = new StringBuilder();
        for (var line : lines) {
            line.accept((index, style, codePoint) -> { text.appendCodePoint(codePoint); return true; });
            text.append('\n');
        }
        return text.toString();
    }

    private void showTooltip(GuiGraphicsExtractor graphics,
                             java.util.List<net.minecraft.util.FormattedCharSequence> lines,
                             int mouseX, int mouseY, boolean keyboardFocus) {
        lines = CodonTooltips.fit(Minecraft.getInstance().font, lines, graphics.guiWidth());
        if (keyboardFocus) {
            graphics.setTooltipForNextFrame(Minecraft.getInstance().font, lines,
                CodonTooltips.withinViewport(new BelowOrAboveWidgetTooltipPositioner(getRectangle())), getX(), getBottom(), true);
        } else {
            graphics.setTooltipForNextFrame(Minecraft.getInstance().font, lines,
                CodonTooltips.withinViewport(net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner.INSTANCE),
                mouseX, mouseY, false);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
