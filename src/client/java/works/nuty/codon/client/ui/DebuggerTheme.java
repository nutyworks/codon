package works.nuty.codon.client.ui;

/** Adjustable panel colors over a transparent world; hue conveys meaning, never source identity. */
public final class DebuggerTheme {
    public static final int PANEL = 0xF2182228;
    public static final int SURFACE = 0xFF172126;
    public static final int RAISED = 0xFF273A42;
    public static final int BORDER = 0xFF415660;
    public static final int TEXT = 0xFFE2EDEF;
    public static final int MUTED = 0xFFA3B9C2;
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

    private DebuggerTheme() { }
}
