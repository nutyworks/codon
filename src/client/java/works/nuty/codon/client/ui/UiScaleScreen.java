package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.state.DebuggerPreferences;
import works.nuty.codon.client.ui.layout.UiScale;

import static works.nuty.codon.client.ui.DebuggerTheme.*;

/** Immediate, persisted display preference; game menus and the game GUI setting remain untouched. */
public final class UiScaleScreen extends ScaledCodonScreen {
    private final Screen parent;
    private final Map<String, DebuggerButton> buttons = new LinkedHashMap<>();
    private String focusKey = "follow";
    private int left, top, panelWidth;
    private record PointerAnchor(String key, double x, double y, double fractionX, double fractionY) { }
    private @Nullable PointerAnchor pointerAnchor;

    public UiScaleScreen(Screen parent, DebuggerPreferences preferences) {
        super(text("title"), preferences);
        this.parent = parent;
    }

    private static Component text(String key, Object... args) { return Component.translatable("codon.ui.scale." + key, args); }
    private static String number(double value) { return String.format(Locale.ROOT, "%.2f×", value); }

    @Override protected void init() {
        buttons.clear();
        panelWidth = Math.min(310, width - 12);
        left = (width - panelWidth) / 2;
        top = Math.max(6, (height - 218) / 2);
        if (pointerAnchor != null) {
            // Preserve the pressed point in framebuffer pixels across the scale change.
            // Keeping the step controls near the center also leaves room for the panel to grow.
            double buttonX = panelWidth / 2 + (pointerAnchor.key().equals("plus") ? 5 : -35);
            double scale = uiScale().effective();
            left = Math.clamp((int) Math.round(pointerAnchor.x() / scale - buttonX - pointerAnchor.fractionX() * 30),
                6, Math.max(6, width - panelWidth - 6));
            top = Math.clamp((int) Math.round(pointerAnchor.y() / scale - 100 - pointerAnchor.fractionY() * 20),
                6, Math.max(6, height - 218 - 6));
            pointerAnchor = null;
        }
        add("follow", left + 8, top + 28, panelWidth - 16, text("follow"), () -> uiPreferences().setUiScaleMode(DebuggerPreferences.UiScaleMode.FOLLOW_GAME));
        add("custom", left + 8, top + 52, panelWidth - 16, text("custom"), () -> uiPreferences().selectCustomUiScale(minecraft.getWindow().getGuiScale()));
        add("minus", left + panelWidth / 2 - 35, top + 100, 30, Component.literal("−"), () -> uiPreferences().setCustomUiScale(requestedScale() - 1));
        add("plus", left + panelWidth / 2 + 5, top + 100, 30, Component.literal("+"), () -> uiPreferences().setCustomUiScale(requestedScale() + 1));
        int half = (panelWidth - 20) / 2;
        add("reset", left + 8, top + 190, half, text("reset"), uiPreferences()::resetUiScale);
        add("done", left + 12 + half, top + 190, half, Component.translatable("gui.done"), this::onClose);
        if (minecraft.getLastInputType().isKeyboard()) setFocused(buttons.get(focusKey));
    }

    @Override protected void setInitialFocus() {
        if (minecraft.getLastInputType().isKeyboard() && buttons.containsKey(focusKey)) setFocused(buttons.get(focusKey));
        else super.setInitialFocus();
    }

    private void add(String key, int x, int y, int width, Component label, Runnable action) {
        var button = WatchUi.button(x, y, width, 20, label, () -> {
            focusKey = key;
            action.run();
        });
        buttons.put(key, addRenderableWidget(button));
    }

    private int requestedScale() {
        return uiPreferences().customUiScaleInitialized() ? uiPreferences().customUiScale()
            : DebuggerPreferences.gameUiScaleRequest(minecraft.getWindow().getGuiScale());
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (String key : new String[]{"minus", "plus"}) {
                DebuggerButton button = buttons.get(key);
                if (button.active && button.isMouseOver(event.x(), event.y())) {
                    double scale = uiScale().effective();
                    pointerAnchor = new PointerAnchor(key, event.x() * scale, event.y() * scale,
                        (event.x() - button.getX()) / button.getWidth(), (event.y() - button.getY()) / button.getHeight());
                    break;
                }
            }
        }
        boolean handled = super.mouseClicked(event, doubleClick);
        // A capped request can change without changing the effective viewport, so no resize follows.
        if (!handled || width == uiScale().width() && height == uiScale().height()) pointerAnchor = null;
        return handled;
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
        buttons.forEach((key, button) -> { if (getFocused() == button) focusKey = key; });
        boolean custom = uiPreferences().uiScaleMode() == DebuggerPreferences.UiScaleMode.CUSTOM;
        buttons.get("follow").setMessage(Component.literal(custom ? "  " : "● ").append(text("follow")));
        buttons.get("custom").setMessage(Component.literal(custom ? "● " : "  ").append(text("custom")));
        buttons.get("minus").active = custom && requestedScale() > DebuggerPreferences.MIN_UI_SCALE;
        var window = minecraft.getWindow();
        buttons.get("plus").active = custom && requestedScale() < UiScale.maximumRequest(window.getWidth(), window.getHeight(), minecraft.isEnforceUnicode());
        graphics.fill(0, 0, width, height, modalColor(0x70000000));
        graphics.fill(left, top, left + panelWidth, top + 218, modalColor(PANEL));
        graphics.outline(left, top, panelWidth, 218, modalColor(BORDER));
        graphics.text(font, title, left + 8, top + 10, modalColor(TEAL), false);
        graphics.centeredText(font, text("requested", number(requestedScale() / 4.0)),
            left + panelWidth / 2, top + 84, modalColor(custom ? TEXT : MUTED));
        int lineY = top + 128;
        for (var line : font.split(text("units"), panelWidth - 16)) {
            graphics.text(font, line, left + 8, lineY, modalColor(MUTED), false);
            lineY += font.lineHeight + 2;
        }
        var scale = uiScale();
        boolean limited = custom && scale.effective() < uiPreferences().customUiScale() / 4.0;
        Component applied = limited ? text("limited", number(scale.effective())) : text("applied", number(scale.effective()));
        for (var line : font.split(applied, panelWidth - 16)) {
            graphics.text(font, line, left + 8, lineY + 4, modalColor(limited ? AMBER : MUTED), false);
            lineY += font.lineHeight + 2;
        }
        super.extractRenderState(graphics, x, y, delta);
    }

    @Override public void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float delta) { }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
}
