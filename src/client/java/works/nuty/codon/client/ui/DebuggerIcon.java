package works.nuty.codon.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Pixel icons used by the debugger toolbar. */
public enum DebuggerIcon {
    BREAKPOINT(new String[]{
        "...###...",
        ".#######.",
        ".#######.",
        "#########",
        "#########",
        "#########",
        ".#######.",
        ".#######.",
        "...###..."
    }),
    BREAKPOINT_EMPTY(new String[]{
        "...###...",
        ".##...##.",
        ".#.....#.",
        "#.......#",
        "#.......#",
        "#.......#",
        ".#.....#.",
        ".##...##.",
        "...###..."
    }),
    BREAKPOINT_CONDITIONAL(new String[]{
        "....#....",
        "...###...",
        "..#####..",
        ".#######.",
        "#########",
        ".#######.",
        "..#####..",
        "...###...",
        "....#...."
    }),
    BREAKPOINT_CONDITIONAL_EMPTY(new String[]{
        "....#....",
        "...#.#...",
        "..#...#..",
        ".#.....#.",
        "#.......#",
        ".#.....#.",
        "..#...#..",
        "...#.#...",
        "....#...."
    }),
    WATCHES,
    COMMAND(new String[]{
        "#......",
        ".#.....",
        "..#....",
        ".#.....",
        "#......",
        ".......",
        "...####"
    }),
    INFORMATION,
    SOURCE_FILE(new String[]{
        ".#####.",
        ".#...#.",
        ".#..##.",
        ".#.#.#.",
        ".##..#.",
        ".#...#.",
        ".#####."
    }),
    BREAKPOINT_LIST(new String[]{
        ".......",
        "##.####",
        ".......",
        "##.####",
        ".......",
        "##.####",
        "......."
    }),
    FREECAM,
    WARNING,
    LINE_WRAP,
    CONTINUE,
    PAUSE,
    STEP_OVER,
    STEP_INTO,
    STEP_OUT,
    GIZMO_GROUPED,
    GIZMO_LABELS,
    DETAILS_OPEN,
    DETAILS_CLOSED,
    WATCH_NBT(new String[]{
        ".##.##.",
        ".#...#.",
        ".#...#.",
        "##...##",
        ".#...#.",
        ".#...#.",
        ".##.##."
    }),
    WATCH_STORAGE(new String[]{
        ".#####.",
        "#.....#",
        "#######",
        "#.....#",
        "#######",
        "#.....#",
        ".#####."
    }),
    WATCH_SCORE(new String[]{
        "..#.#..",
        "..#.#..",
        "#######",
        "..#.#..",
        "#######",
        "..#.#..",
        "..#.#.."
    }),
    PIN(new String[]{
        ".#####.",
        "..###..",
        "..###..",
        "#######",
        "...#...",
        "...#...",
        "...#..."
    }),
    EDIT(new String[]{
        "....##.",
        "...#.##",
        "..#..#.",
        ".#..#..",
        "#..#...",
        "#.#....",
        "##....."
    }),
    REMOVE(new String[]{
        ".......",
        ".#...#.",
        "..#.#..",
        "...#...",
        "..#.#..",
        ".#...#.",
        "......."
    }),
    SOURCE_CREATED,
    SOURCE_EXCLUDED,
    OUTSIDE_VIEWPORT,
    COPY_UUID,
    HISTORY_PREVIOUS,
    HISTORY_NEXT,
    PANEL_EXPAND,
    PANEL_COLLAPSE,
    EXPAND,
    COLLAPSE;

    public static final int SIZE = 12;
    private final String[] smallPixels;

    DebuggerIcon() { this(new String[0]); }
    DebuggerIcon(String[] smallPixels) { this.smallPixels = smallPixels; }

    public int smallSize() { return smallPixels.length == 0 ? SIZE : smallPixels.length; }

    /** Dedicated integer-pixel artwork keeps small row actions crisp at every GUI scale. */
    public void drawSmall(GuiGraphicsExtractor graphics, int x, int y, int color) {
        if (smallPixels.length == 0) {
            draw(graphics, x, y, color);
            return;
        }
        for (int row = 0; row < smallPixels.length; row++)
            for (int column = 0; column < smallPixels[row].length(); column++)
                if (smallPixels[row].charAt(column) == '#')
                    graphics.fill(x + column, y + row, x + column + 1, y + row + 1, color);
    }

    public void draw(GuiGraphicsExtractor graphics, int x, int y, int color) {
        switch (this) {
            case FREECAM -> {
                outline(graphics, x + 1, y + 3, 10, 8, color);
                graphics.fill(x + 3, y + 1, x + 7, y + 3, color);
                outline(graphics, x + 4, y + 5, 4, 4, color);
            }
            case LINE_WRAP -> {
                graphics.fill(x + 9, y + 2, x + 10, y + 7, color);
                graphics.fill(x + 2, y + 6, x + 10, y + 7, color);
                graphics.fill(x + 3, y + 5, x + 4, y + 8, color);
                graphics.fill(x + 4, y + 4, x + 5, y + 5, color);
                graphics.fill(x + 4, y + 8, x + 5, y + 9, color);
            }
            case WARNING -> {
                for (int row = 0; row < 10; row++) {
                    int halfWidth = row / 2;
                    graphics.fill(x + 5 - halfWidth, y + row + 1, x + 6 - halfWidth, y + row + 2, color);
                    graphics.fill(x + 6 + halfWidth, y + row + 1, x + 7 + halfWidth, y + row + 2, color);
                }
                graphics.fill(x + 1, y + 10, x + 11, y + 11, color);
                graphics.fill(x + 5, y + 4, x + 7, y + 7, color);
                graphics.fill(x + 5, y + 8, x + 7, y + 9, color);
            }
            case INFORMATION -> {
                // Symmetric pixel-circle silhouette around the central information mark.
                graphics.fill(x + 4, y, x + 8, y + 1, color);
                graphics.fill(x + 4, y + 11, x + 8, y + 12, color);
                graphics.fill(x, y + 4, x + 1, y + 8, color);
                graphics.fill(x + 11, y + 4, x + 12, y + 8, color);
                for (int step = 0; step < 3; step++) {
                    int dx = 3 - step;
                    int dy = 1 + step;
                    graphics.fill(x + dx, y + dy, x + dx + 1, y + dy + 1, color);
                    graphics.fill(x + 11 - dx, y + dy, x + 12 - dx, y + dy + 1, color);
                    graphics.fill(x + dx, y + 11 - dy, x + dx + 1, y + 12 - dy, color);
                    graphics.fill(x + 11 - dx, y + 11 - dy, x + 12 - dx, y + 12 - dy, color);
                }
                graphics.fill(x + 5, y + 3, x + 7, y + 4, color);
                graphics.fill(x + 5, y + 5, x + 7, y + 9, color);
            }
            case CONTINUE -> play(graphics, x, y, color);
            case PAUSE -> {
                graphics.fill(x + 2, y + 2, x + 5, y + 10, color);
                graphics.fill(x + 7, y + 2, x + 10, y + 10, color);
            }
            case STEP_OVER -> stepOver(graphics, x, y, color);
            case STEP_INTO -> stepInto(graphics, x, y, color);
            case STEP_OUT -> stepOut(graphics, x, y, color);
            case GIZMO_GROUPED -> grouped(graphics, x, y, color);
            case GIZMO_LABELS -> labels(graphics, x, y, color);
            case DETAILS_OPEN -> details(graphics, x, y, color, true);
            case DETAILS_CLOSED -> details(graphics, x, y, color, false);
            case WATCHES -> {
                graphics.fill(x + 4, y + 2, x + 8, y + 3, color);
                graphics.fill(x + 4, y + 9, x + 8, y + 10, color);
                for (int step = 0; step < 3; step++) {
                    int dx = 3 - step;
                    int dy = 3 + step;
                    graphics.fill(x + dx, y + dy, x + dx + 1, y + dy + 1, color);
                    graphics.fill(x + 11 - dx, y + dy, x + 12 - dx, y + dy + 1, color);
                    graphics.fill(x + dx, y + 11 - dy, x + dx + 1, y + 12 - dy, color);
                    graphics.fill(x + 11 - dx, y + 11 - dy, x + 12 - dx, y + 12 - dy, color);
                }
                graphics.fill(x + 5, y + 4, x + 7, y + 8, color);
            }
            case BREAKPOINT, BREAKPOINT_EMPTY, BREAKPOINT_CONDITIONAL, BREAKPOINT_CONDITIONAL_EMPTY ->
                drawSmall(graphics, x + 1, y + 1, color);
            case COMMAND, SOURCE_FILE, BREAKPOINT_LIST, WATCH_NBT, WATCH_STORAGE, WATCH_SCORE ->
                drawSmall(graphics, x + 2, y + 2, color);
            case PIN -> pin(graphics, x, y, color);
            case EDIT -> {
                // Outlined diagonal pencil, with a separate eraser and tapered graphite tip.
                for (int step = 0; step < 5; step++) {
                    graphics.fill(x + 3 + step, y + 7 - step, x + 4 + step, y + 8 - step, color);
                    graphics.fill(x + 5 + step, y + 9 - step, x + 6 + step, y + 10 - step, color);
                }
                graphics.fill(x + 8, y + 1, x + 10, y + 2, color);
                graphics.fill(x + 9, y + 2, x + 11, y + 3, color);
                graphics.fill(x + 10, y + 3, x + 11, y + 4, color);
                graphics.fill(x + 2, y + 8, x + 3, y + 10, color);
                graphics.fill(x + 3, y + 9, x + 5, y + 10, color);
                graphics.fill(x + 1, y + 10, x + 2, y + 11, color);
            }
            case REMOVE -> {
                for (int step = 0; step < 8; step++) {
                    graphics.fill(x + 2 + step, y + 2 + step, x + 3 + step, y + 3 + step, color);
                    graphics.fill(x + 9 - step, y + 2 + step, x + 10 - step, y + 3 + step, color);
                }
            }
            case SOURCE_CREATED, SOURCE_EXCLUDED -> {
                graphics.fill(x + 2, y + 5, x + 10, y + 7, color);
                if (this == SOURCE_CREATED) graphics.fill(x + 5, y + 2, x + 7, y + 10, color);
            }
            case OUTSIDE_VIEWPORT -> {
                outline(graphics, x + 1, y + 3, 7, 8, color);
                graphics.fill(x + 6, y + 1, x + 11, y + 2, color);
                graphics.fill(x + 10, y + 1, x + 11, y + 6, color);
                for (int i = 0; i < 6; i++) graphics.fill(x + 5 + i, y + 6 - i, x + 6 + i, y + 7 - i, color);
            }
            case COPY_UUID -> {
                outline(graphics, x + 1, y + 1, 7, 8, color);
                outline(graphics, x + 4, y + 4, 7, 8, color);
            }
            case HISTORY_PREVIOUS, HISTORY_NEXT -> {
                graphics.fill(x + 2, y + 5, x + 10, y + 6, color);
                for (int step = 0; step < 4; step++) {
                    int arrowX = this == HISTORY_NEXT ? x + 9 - step : x + 2 + step;
                    graphics.fill(arrowX, y + 5 - step, arrowX + 1, y + 6 - step, color);
                    graphics.fill(arrowX, y + 5 + step, arrowX + 1, y + 6 + step, color);
                }
            }
            case PANEL_EXPAND, PANEL_COLLAPSE -> {
                // Four outward/inward corners distinguish resizing from history navigation.
                for (int cornerX : new int[]{0, 1}) {
                    for (int cornerY : new int[]{0, 1}) {
                        int dx = cornerX == 0 ? 1 : -1;
                        int dy = cornerY == 0 ? 1 : -1;
                        int cx = x + (cornerX == 0 ? 1 : 10);
                        int cy = y + (cornerY == 0 ? 1 : 10);
                        if (this == PANEL_COLLAPSE) {
                            cx += dx * 3;
                            cy += dy * 3;
                            dx = -dx;
                            dy = -dy;
                        }
                        for (int step = 0; step < 4; step++) {
                            graphics.fill(cx + dx * step, cy, cx + dx * step + 1, cy + 1, color);
                            graphics.fill(cx, cy + dy * step, cx + 1, cy + dy * step + 1, color);
                        }
                    }
                }
            }
            case EXPAND, COLLAPSE -> {
                for (int step = 0; step < 3; step++) {
                    if (this == EXPAND) {
                        graphics.fill(x + 4 + step, y + 4 + step, x + 5 + step, y + 5 + step, color);
                        graphics.fill(x + 4 + step, y + 8 - step, x + 5 + step, y + 9 - step, color);
                    } else {
                        graphics.fill(x + 3 + step, y + 5 + step, x + 4 + step, y + 6 + step, color);
                        graphics.fill(x + 7 - step, y + 5 + step, x + 8 - step, y + 6 + step, color);
                    }
                }
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

    private static void details(GuiGraphicsExtractor graphics, int x, int y, int color, boolean open) {
        outline(graphics, x + 1, y + 2, 10, 8, color);
        graphics.fill(x + 6, y + 3, x + 7, y + 9, color);
        if (open) {
            graphics.fill(x + 2, y + 3, x + 6, y + 9, color);
        }
    }

    private static void pin(GuiGraphicsExtractor graphics, int x, int y, int color) {
        // Thumbtack: flat cap, narrow neck, flared shoulder and pointed needle.
        graphics.fill(x + 3, y + 1, x + 9, y + 3, color);
        graphics.fill(x + 4, y + 3, x + 8, y + 5, color);
        graphics.fill(x + 3, y + 5, x + 9, y + 6, color);
        graphics.fill(x + 2, y + 6, x + 10, y + 7, color);
        graphics.fill(x + 5, y + 7, x + 7, y + 10, color);
        graphics.fill(x + 5, y + 10, x + 6, y + 12, color);
    }

    private static void outline(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y + 1, x + 1, y + height - 1, color);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }
}
