package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.state.DebuggerPreferences;

/** Live background preview with one settings write per drag gesture. */
public final class BackgroundOpacitySlider extends DebuggerButton {
    private static final int STEP = 1;
    private static final int SHIFT_STEP = 10;
    private final DebuggerPreferences preferences;
    private int dragStartOpacity = -1;

    public BackgroundOpacitySlider(DebuggerPreferences preferences) {
        this.preferences = preferences;
    }

    public void position(int x, int y, int width) {
        configure(x, y, width, 16, label(), true, false, false, false, () -> { });
    }

    private Component label() {
        return Component.translatable("codon.ui.background_opacity", preferences.backgroundOpacity());
    }

    private void update(double x) {
        preferences.previewBackgroundOpacity((int) Math.round((x - getX() - 4) * 100 / Math.max(1, getWidth() - 8)));
        setMessage(label());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || !isMouseOver(event.x(), event.y())) return false;
        commitPreview();
        dragStartOpacity = preferences.backgroundOpacity();
        update(event.x());
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || dragStartOpacity < 0) return false;
        update(event.x());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || dragStartOpacity < 0) return false;
        commitPreview();
        return true;
    }

    /** Also commit when the debugger is closed or rebuilt before the release event arrives. */
    public void commitPreview() {
        if (dragStartOpacity < 0) return;
        dragStartOpacity = -1;
        preferences.commitBackgroundOpacity();
    }

    @Override
    protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
        output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
            Component.translatable("gui.narrate.slider", label()));
        output.add(net.minecraft.client.gui.narration.NarratedElementType.USAGE,
            Component.translatable("codon.ui.background_opacity.usage"));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() != InputConstants.KEY_LEFT && event.key() != InputConstants.KEY_RIGHT) return false;
        commitPreview();
        int step = event.hasShiftDown() ? SHIFT_STEP : STEP;
        preferences.setBackgroundOpacity(preferences.backgroundOpacity() + (event.key() == InputConstants.KEY_LEFT ? -step : step));
        setMessage(label());
        return true;
    }

    @Override
    protected void extractTooltipForNextRenderPass(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (isHovered()) graphics.setTooltipForNextFrame(Minecraft.getInstance().font,
            java.util.List.of(label().getVisualOrderText()), mouseX, mouseY);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int left = getX() + 4;
        int right = getRight() - 4;
        int centerY = getY() + getHeight() / 2;
        int thumb = left + (right - left) * preferences.backgroundOpacity() / 100;
        graphics.fill(left, centerY - 1, right, centerY + 1, DebuggerTheme.BORDER);
        graphics.fill(left, centerY - 1, thumb, centerY + 1, DebuggerTheme.TEAL);
        graphics.fill(thumb - 2, centerY - 4, thumb + 2, centerY + 4,
            isHovered() || isFocused() ? DebuggerTheme.TEAL : DebuggerTheme.TEXT);
        if (isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard()) {
            graphics.outline(getX(), getY(), getWidth(), getHeight(), DebuggerTheme.TEAL);
        }
    }
}
