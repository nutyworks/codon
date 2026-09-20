package works.nuty.codon.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import works.nuty.codon.client.state.ClientWatchState;

/** Shared name/dot-leader/value layout for the HUD and Watch editor. */
public final class WatchRowRenderer {
    private WatchRowRenderer() { }

    public static void render(GuiGraphicsExtractor graphics, Font font, ClientWatchState.Entry entry,
                              boolean paused, String label, int x, int y, int width, int labelColor, int valueColor) {
        if (width <= 0) return;
        String badge = WatchFormatting.changeBadge(entry).getString();
        if (!badge.isEmpty()) label += (label.isEmpty() ? "" : " · ") + badge;
        String value = fitValue(font, entry, paused, label.isEmpty() ? width : width / 2);
        int valueX = x + width - font.width(value);
        int dotWidth = Math.max(1, font.width("."));
        label = fit(font, label, Math.max(0, valueX - x - 8 - 3 * dotWidth));
        int leaderX = x + font.width(label) + 4;
        int dots = label.isEmpty() ? 0 : Math.max(0, (valueX - 4 - leaderX) / dotWidth);
        graphics.text(font, label, x, y, DebuggerTheme.color(labelColor), false);
        int leaderY = y + (font.lineHeight - 2) / 2;
        for (int dot = 0; dot < dots; dot++) {
            int dotX = leaderX + dot * dotWidth;
            graphics.fill(dotX, leaderY, dotX + 1, leaderY + 1, DebuggerTheme.color(DebuggerTheme.MUTED));
        }
        graphics.text(font, value, valueX, y, DebuggerTheme.color(valueColor), false);
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
