package works.nuty.codon.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.core.model.EntityRef;

/** Shared presentation helpers for the watch list, editor and read-only chooser. */
final class WatchUi {
    private WatchUi() { }

    static Component text(String suffix, Object... args) { return Component.translatable("codon.watch." + suffix, args); }

    static @Nullable EntityRef currentEntity(ClientDebuggerState state) {
        int index = state.selectedPauseSourceIndex();
        var snapshot = state.snapshot();
        return !state.isPaused() || snapshot == null || index < 0 || index >= snapshot.pauseSources().size()
            ? null : snapshot.pauseSources().get(index).entity();
    }

    static long pause(ClientDebuggerState state) { return state.isPaused() && state.snapshot() != null ? state.snapshot().pauseId() : 0; }

    static String fit(Font font, String value, int width) {
        if (width <= 0) return "";
        if (font.width(value) <= width) return value;
        if (width < font.width("…")) return "";
        return font.plainSubstrByWidth(value, width - font.width("…")) + "…";
    }

    static void line(GuiGraphicsExtractor graphics, Font font, String value, int x, int y, int width, int color) {
        graphics.text(font, fit(font, value, width), x, y, color, false);
    }

    static DebuggerButton button(int x, int y, int width, int height, Component label, Runnable action) {
        DebuggerButton button = new DebuggerButton();
        button.configure(x, y, width, height, label, true, false, false, false, action);
        button.setTooltip(Tooltip.create(label));
        return button;
    }
}
