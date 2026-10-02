package works.nuty.codon.client.ui;

/** Adjustable panel colors over a transparent world; hue conveys meaning, never source identity. */
public final class DebuggerTheme {
    /** Neutral workspace chrome. Foreground reading surfaces use these without HUD opacity. */
    public static final int WORKSPACE = 0xFF202124;
    public static final int EDITOR = 0xFF18191C;
    public static final int DIVIDER = 0xFF3B3E44;
    public static final int ROW_HOVER = 0xFF2C2E33;
    public static final int SCROLLBAR = 0xFF747980;

    public static final int PANEL = (WORKSPACE & 0x00FFFFFF) | 0xF2000000;
    public static final int SURFACE = EDITOR;
    public static final int RAISED = ROW_HOVER;
    public static final int BORDER = DIVIDER;
    public static final int TEXT = 0xFFE6E6E8;
    public static final int MUTED = 0xFFA9ADB5;
    public static final int TEAL = 0xFF75DFD6;
    public static final int TEAL_SURFACE = 0xFF203E3E;
    public static final int AMBER = 0xFFF3C171;
    public static final int AMBER_SURFACE = 0xFF3B3022;
    public static final int RED = 0xFFFC8C8C;
    public static final int RED_SURFACE = 0xFF402A2D;
    public static final int GREEN = 0xFF83E89D;
    public static final int GREEN_SURFACE = 0xFF243D2D;
    public static final int PURPLE = 0xFFC7A0FF;
    public static final int PURPLE_SURFACE = 0xFF352B45;
    /** Keep keyboard hints teal even inside colored headings or wrapped translations. */
    public static net.minecraft.network.chat.Component keybind(net.minecraft.network.chat.Component label) {
        return label.copy().withStyle(style -> style.withColor(TEAL & 0x00FFFFFF));
    }

    private static java.util.function.IntSupplier opacity = () -> 100;

    public static void usePreferences(works.nuty.codon.client.state.DebuggerPreferences preferences) {
        opacity = preferences::backgroundOpacity;
    }

    /** Apply panel opacity to an ARGB color without changing its hue. */
    public static int color(int color) {
        return (color & 0x00FFFFFF) | (Math.round((color >>> 24) * opacity.getAsInt() / 100f) << 24);
    }

    /** Panel transparency must not reduce the contrast of text, icons or keyboard focus. */
    public static int foreground(int color) { return color | 0xFF000000; }

    /** A filled corner caret is separate from selection color and does not frame the row. */
    public static void focusMark(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x, y, x + 3, y + 1, TEXT);
        graphics.fill(x, y + 1, x + 2, y + 2, TEXT);
        graphics.fill(x, y + 2, x + 1, y + 3, TEXT);
    }

    private DebuggerTheme() { }
}
