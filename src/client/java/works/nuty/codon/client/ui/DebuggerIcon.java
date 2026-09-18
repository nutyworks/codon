package works.nuty.codon.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Pixel icons used by the debugger toolbar. */
public enum DebuggerIcon {
    CONTINUE,
    STEP_OVER,
    STEP_INTO,
    STEP_OUT,
    GIZMO_GROUPED,
    GIZMO_LABELS,
    GIZMO_FOCUS,
    DETAILS_OPEN,
    DETAILS_CLOSED,
    PIN,
    EXPAND,
    COLLAPSE;

    public static final int SIZE = 12;

    public void draw(GuiGraphicsExtractor graphics, int x, int y, int color) {
        switch (this) {
            case CONTINUE -> play(graphics, x, y, color);
            case STEP_OVER -> stepOver(graphics, x, y, color);
            case STEP_INTO -> stepInto(graphics, x, y, color);
            case STEP_OUT -> stepOut(graphics, x, y, color);
            case GIZMO_GROUPED -> grouped(graphics, x, y, color);
            case GIZMO_LABELS -> labels(graphics, x, y, color);
            case GIZMO_FOCUS -> focus(graphics, x, y, color);
            case DETAILS_OPEN -> details(graphics, x, y, color, true);
            case DETAILS_CLOSED -> details(graphics, x, y, color, false);
            case PIN -> pin(graphics, x, y, color);
            case EXPAND, COLLAPSE -> {
                graphics.fill(x + 2, y + 5, x + 10, y + 7, color);
                if (this == EXPAND) graphics.fill(x + 5, y + 2, x + 7, y + 10, color);
            }
        }
    }

    private static void play(GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.fill(x + 3, y + 2, x + 5, y + 10, color);
        graphics.fill(x + 5, y + 3, x + 7, y + 9, color);
        graphics.fill(x + 7, y + 4, x + 9, y + 8, color);
        graphics.fill(x + 9, y + 5, x + 10, y + 7, color);
    }

    private static void stepOver(GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.fill(x + 2, y + 4, x + 4, y + 6, color);
        graphics.fill(x + 3, y + 3, x + 5, y + 4, color);
        graphics.fill(x + 4, y + 2, x + 8, y + 4, color);
        graphics.fill(x + 8, y + 3, x + 10, y + 6, color);
        graphics.fill(x + 6, y + 5, x + 12, y + 6, color);
        graphics.fill(x + 7, y + 6, x + 11, y + 7, color);
        graphics.fill(x + 8, y + 7, x + 10, y + 8, color);
        graphics.fill(x + 5, y + 9, x + 7, y + 11, color);
    }

    private static void stepInto(GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.fill(x + 5, y + 1, x + 7, y + 5, color);
        graphics.fill(x + 3, y + 4, x + 9, y + 5, color);
        graphics.fill(x + 4, y + 5, x + 8, y + 6, color);
        graphics.fill(x + 5, y + 6, x + 7, y + 7, color);
        graphics.fill(x + 5, y + 9, x + 7, y + 11, color);
    }

    private static void stepOut(GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.fill(x + 5, y + 3, x + 7, y + 7, color);
        graphics.fill(x + 5, y + 1, x + 7, y + 3, color);
        graphics.fill(x + 4, y + 2, x + 8, y + 3, color);
        graphics.fill(x + 3, y + 3, x + 9, y + 4, color);
        graphics.fill(x + 5, y + 9, x + 7, y + 11, color);
    }

    private static void grouped(GuiGraphicsExtractor graphics, int x, int y, int color) {
        outline(graphics, x + 1, y + 3, 7, 7, color);
        outline(graphics, x + 4, y + 1, 7, 7, color);
    }

    private static void labels(GuiGraphicsExtractor graphics, int x, int y, int color) {
        outline(graphics, x + 1, y + 2, 10, 3, color);
        outline(graphics, x + 1, y + 7, 10, 3, color);
        graphics.fill(x + 3, y + 3, x + 6, y + 4, color);
        graphics.fill(x + 3, y + 8, x + 8, y + 9, color);
    }

    private static void focus(GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.fill(x + 5, y + 1, x + 7, y + 4, color);
        graphics.fill(x + 5, y + 8, x + 7, y + 11, color);
        graphics.fill(x + 1, y + 5, x + 4, y + 7, color);
        graphics.fill(x + 8, y + 5, x + 11, y + 7, color);
        outline(graphics, x + 4, y + 4, 4, 4, color);
    }

    private static void details(GuiGraphicsExtractor graphics, int x, int y, int color, boolean open) {
        outline(graphics, x + 1, y + 2, 10, 8, color);
        graphics.fill(x + 6, y + 3, x + 7, y + 9, color);
        if (open) {
            graphics.fill(x + 2, y + 3, x + 6, y + 9, color);
        }
    }

    private static void pin(GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.fill(x + 4, y + 1, x + 8, y + 2, color);
        graphics.fill(x + 3, y + 2, x + 9, y + 7, color);
        graphics.fill(x + 4, y + 7, x + 8, y + 9, color);
        graphics.fill(x + 5, y + 9, x + 7, y + 12, color);
        graphics.fill(x + 5, y + 4, x + 7, y + 6, DebuggerTheme.SURFACE);
    }

    private static void outline(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y + 1, x + 1, y + height - 1, color);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }
}
