package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;

import static works.nuty.codon.client.ui.DebuggerTheme.*;

/** A modal dropdown hosted over the current screen; pointer events never reach rows below it. */
final class WatchContextMenu extends ScaledCodonScreen {
    record Item(Component label, DebuggerIcon icon, boolean enabled, @Nullable Component hint, Runnable action) { }

    private final Screen parent;
    private final Bounds anchor;
    private final Component expression;
    private final Supplier<List<Item>> source;
    private final Predicate<MouseButtonEvent> switchRow;
    private final Runnable restoreFocus;
    private final List<DebuggerButton> choices = new ArrayList<>();
    private List<Item> items = List.of();
    private Bounds bounds = new Bounds(0, 0, 0, 0);
    private int offset, visibleRows, rowHeight, headerHeight;
    private boolean dismissed;

    WatchContextMenu(Screen parent, Bounds anchor, Component expression, Supplier<List<Item>> source,
                     Predicate<MouseButtonEvent> switchRow, Runnable restoreFocus) {
        super(WatchUi.text("menu.title"), preferencesFor(parent));
        this.parent = parent;
        this.anchor = anchor;
        this.expression = expression;
        this.source = source;
        this.switchRow = switchRow;
        this.restoreFocus = restoreFocus;
    }

    @Override protected void init() {
        if (refresh()) focusItem(0, 1);
    }

    private boolean refresh() {
        if (dismissed) return false;
        items = source.get();
        if (items.isEmpty()) { onClose(); return false; }
        if (choices.size() != items.size()) {
            clearWidgets();
            choices.clear();
            for (int i = 0; i < items.size(); i++) choices.add(addRenderableWidget(new DebuggerButton()));
        }
        int margin = Math.min(4, Math.max(0, Math.min(width, height) / 4));
        int availableHeight = Math.max(1, height - margin * 2);
        headerHeight = availableHeight >= 44 ? 22 : 0;
        rowHeight = Math.max(1, Math.min(18, availableHeight - headerHeight - 4));
        visibleRows = Math.max(1, Math.min(items.size(), (availableHeight - headerHeight - 4) / rowHeight));
        offset = Math.clamp(offset, 0, items.size() - visibleRows);
        int menuHeight = Math.min(availableHeight, headerHeight + visibleRows * rowHeight + 4);
        int desiredWidth = items.stream().mapToInt(item -> font.width(item.label())).max().orElse(0) + 28;
        int menuWidth = Math.min(Math.max(156, desiredWidth), Math.max(1, width - margin * 2));
        int left = Math.clamp(anchor.x() + anchor.width() - menuWidth, margin, Math.max(margin, width - menuWidth - margin));
        int top = anchor.y() + anchor.height();
        if (top + menuHeight > height - margin) top = anchor.y() - menuHeight;
        top = Math.clamp(top, margin, Math.max(margin, height - menuHeight - margin));
        bounds = new Bounds(left, top, menuWidth, menuHeight);
        for (int i = 0; i < choices.size(); i++) {
            int index = i;
            Item item = items.get(i);
            DebuggerButton button = choices.get(i);
            button.configure(left + 2, top + headerHeight + 2 + (i - offset) * rowHeight,
                menuWidth - 4, rowHeight, item.label(), item.enabled(), false, true, false, () -> activate(index));
            button.withFlatChrome().withOpaqueColors().withTextIcon(item.icon());
            button.visible = i >= offset && i < offset + visibleRows;
            if (item.hint() != null) button.setTooltip(Tooltip.create(item.hint()));
            button.setTabOrderGroup(i);
        }
        if (getFocused() instanceof DebuggerButton focused && !focused.visible) setFocused(null);
        return true;
    }

    private void activate(int index) {
        // Refresh eligibility and world/Watch identity before executing, then dismiss first.
        if (ScreenLayers.get(parent) != this || !refresh() || index >= items.size()) return;
        Item item = items.get(index);
        if (!item.enabled()) return;
        onClose();
        item.action().run();
    }

    private void focusItem(int start, int direction) {
        for (int step = 0; step < items.size(); step++) {
            int index = Math.floorMod(start + step * direction, items.size());
            if (!items.get(index).enabled()) continue;
            if (index < offset) offset = index;
            else if (index >= offset + visibleRows) offset = index - visibleRows + 1;
            if (refresh()) setFocused(choices.get(index));
            return;
        }
    }

    @Override public void tick() { refresh(); }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (!refresh()) return;
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + bounds.width(), bounds.y() + bounds.height(), WORKSPACE);
        graphics.outline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), DIVIDER);
        if (headerHeight > 0) {
            WatchUi.line(graphics, font, expression.getString(), bounds.x() + 7, bounds.y() + 7, bounds.width() - 14, MUTED);
            graphics.fill(bounds.x() + 3, bounds.y() + headerHeight - 1,
                bounds.x() + bounds.width() - 3, bounds.y() + headerHeight, DIVIDER);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!refresh()) return true;
        if (bounds.contains(event.x(), event.y())) {
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                for (DebuggerButton button : choices) if (button.visible && button.isMouseOver(event.x(), event.y())) {
                    button.mouseClicked(event, doubleClick);
                    return true;
                }
            }
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT && switchRow.test(event)) return true;
        onClose();
        return true;
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) { return true; }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) { return true; }

    @Override public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (bounds.contains(x, y) && scrollY != 0) {
            offset = Math.clamp(offset - (int) Math.signum(scrollY), 0, Math.max(0, items.size() - visibleRows));
            refresh();
        }
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (!refresh()) return true;
        int current = choices.indexOf(getFocused());
        switch (event.key()) {
            case InputConstants.KEY_ESCAPE -> onClose();
            case InputConstants.KEY_UP -> focusItem(current < 0 ? items.size() - 1 : current - 1, -1);
            case InputConstants.KEY_DOWN -> focusItem(current + 1, 1);
            case InputConstants.KEY_HOME -> focusItem(0, 1);
            case InputConstants.KEY_END -> focusItem(items.size() - 1, -1);
            case InputConstants.KEY_TAB -> focusItem(current + (event.hasShiftDown() ? -1 : 1), event.hasShiftDown() ? -1 : 1);
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER, InputConstants.KEY_SPACE -> {
                if (current >= 0) activate(current);
            }
            default -> { }
        }
        return true;
    }

    @Override public void onClose() { ScreenLayers.close(this); }
    @Override public void removed() {
        if (dismissed) return;
        dismissed = true;
        restoreFocus.run();
    }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) { }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
}
