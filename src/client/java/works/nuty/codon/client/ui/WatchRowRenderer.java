package works.nuty.codon.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import works.nuty.codon.client.state.ClientWatchState;

/** Shared name/value layout for the HUD and Watch editor. */
public final class WatchRowRenderer {
    private WatchRowRenderer() { }

    /** Compact HUD rows use full-width name and value lines. */
    public static void renderStacked(GuiGraphicsExtractor graphics, Font font, ClientWatchState.Entry entry,
                                     boolean paused, String label, int x, int y, int labelWidth, int valueWidth,
                                     int kindInset, int labelColor, int valueColor) {
        String badge = WatchFormatting.changeBadge(entry).getString();
        if (!badge.isEmpty()) label += " · " + badge;
        WatchUi.line(graphics, font, label, x + kindInset, y, labelWidth - kindInset, labelColor);
        graphics.text(font, fitValue(font, entry, paused, valueWidth), x, y + 16,
            DebuggerTheme.foreground(valueColor), false);
    }

    public static void render(GuiGraphicsExtractor graphics, Font font, ClientWatchState.Entry entry,
                              boolean paused, String label, int x, int y, int width, int labelColor, int valueColor) {
        if (width <= 0) return;
        String badge = WatchFormatting.changeBadge(entry).getString();
        if (!badge.isEmpty()) label += (label.isEmpty() ? "" : " · ") + badge;
        String value = fitValue(font, entry, paused, label.isEmpty() ? width : width / 2);
        int valueX = x + width - font.width(value);
        label = fit(font, label, Math.max(0, valueX - x - 8));
        graphics.text(font, label, x, y, DebuggerTheme.foreground(labelColor), false);
        graphics.text(font, value, valueX, y, DebuggerTheme.foreground(valueColor), false);
    }

    private static String fitValue(Font font, ClientWatchState.Entry entry, boolean paused, int width) {
        String full = WatchFormatting.value(entry, paused).getString();
        if (font.width(full) <= width) return full;
        String current = WatchFormatting.latestValue(entry, paused).getString();
        if (!entry.displayedChange().isValueChange()) return fit(font, current, width);
        String arrow = " → ";
        int currentWidth = Math.max(0, width - font.width(arrow) - font.width("…"));
        String visibleCurrent = fit(font, current, currentWidth);
        int previousWidth = width - font.width(arrow) - font.width(visibleCurrent);
        String before = entry.displayedPreviousValue();
        if (before.isEmpty()) before = net.minecraft.network.chat.Component.translatable("codon.watch.short.value_missing").getString();
        if (previousWidth < font.width("…")) return fit(font, current, width);
        return fit(font, before, previousWidth) + arrow + visibleCurrent;
    }

    private static String fit(Font font, String value, int width) {
        if (width <= 0) return "";
        if (font.width(value) <= width) return value;
        if (width < font.width("…")) return "";
        return font.plainSubstrByWidth(value, width - font.width("…")) + "…";
    }
}
