package works.nuty.codon.client.ui;

import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import works.nuty.codon.client.state.WatchGrouping;

/** Explicit grouping choices, reachable by mouse or the normal Tab/Enter screen navigation. */
public final class WatchGroupingScreen extends Screen {
    private final Screen parent;
    private final WatchGrouping.Mode selected;
    private final Consumer<WatchGrouping.Mode> choose;
    private int left, top, panelWidth;

    public WatchGroupingScreen(Screen parent, WatchGrouping.Mode selected, Consumer<WatchGrouping.Mode> choose) {
        super(WatchUi.text("grouping.title"));
        this.parent = parent;
        this.selected = selected;
        this.choose = choose;
    }

    @Override protected void init() {
        panelWidth = Math.min(220, width - 16);
        left = (width - panelWidth) / 2;
        top = Math.max(4, (height - 130) / 2);
        for (var mode : WatchGrouping.Mode.values()) {
            var label = WatchUi.text("grouping." + mode.name().toLowerCase(Locale.ROOT));
            Runnable action = () -> { choose.accept(mode); onClose(); };
            var button = WatchUi.button(left + 8, top + 26 + mode.ordinal() * 24, panelWidth - 16, 20, label, action);
            button.configure(button.getX(), button.getY(), button.getWidth(), button.getHeight(), label,
                true, selected == mode, false, false, action);
            addRenderableWidget(button);
            if (mode == selected) setInitialFocus(button);
        }
        addRenderableWidget(WatchUi.button(left + 8, top + 102, panelWidth - 16, 20, WatchUi.text("close"), this::onClose));
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, DebuggerTheme.color(0x70000000));
        graphics.fill(left, top, left + panelWidth, top + 130, DebuggerTheme.color(DebuggerTheme.PANEL));
        graphics.outline(left, top, panelWidth, 130, DebuggerTheme.color(DebuggerTheme.BORDER));
        WatchUi.line(graphics, font, title.getString(), left + 8, top + 9, panelWidth - 16, DebuggerTheme.TEAL);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) { }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
}
