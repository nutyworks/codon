package works.nuty.codon.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import works.nuty.codon.client.state.ClientWatchState;

/** Shared name/dot-leader/value layout for the HUD and Watch editor. */
public final class WatchRowRenderer {
    private WatchRowRenderer() { }

    public static void render(GuiGraphicsExtractor graphics, Font font, ClientWatchState.Entry entry,
                              boolean paused, int x, int y, int width, int labelColor, int valueColor) {
        if (width <= 0) return;
        String label = WatchFormatting.specification(entry).getString();
        String badge = WatchFormatting.changeBadge(entry).getString();
        if (!badge.isEmpty()) label += " · " + badge;
        String value = fit(font, WatchFormatting.value(entry, paused).getString(), width / 2);
        int valueX = x + width - font.width(value);
        int dotWidth = Math.max(1, font.width("."));
        label = fit(font, label, Math.max(0, valueX - x - 8 - 3 * dotWidth));
        int leaderX = x + font.width(label) + 4;
        int dots = Math.max(0, (valueX - 4 - leaderX) / dotWidth);
        graphics.text(font, label, x, y, labelColor, false);
        graphics.text(font, ".".repeat(dots), leaderX, y, DebuggerTheme.MUTED, false);
        graphics.text(font, value, valueX, y, valueColor, false);
    }

    private static String fit(Font font, String value, int width) {
        if (width <= 0) return "";
        if (font.width(value) <= width) return value;
        if (width < font.width("…")) return "";
        return font.plainSubstrByWidth(value, width - font.width("…")) + "…";
    }
}
