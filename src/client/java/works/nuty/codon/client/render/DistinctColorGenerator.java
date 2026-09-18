package works.nuty.codon.client.render;

import java.awt.Color;

/**
 * Generates a sequence of visually distinct, saturated colors by walking the hue wheel in
 * golden-ratio steps. Used to give each pause source / window a different tint.
 */
public final class DistinctColorGenerator {
    private static final float GOLDEN_RATIO_CONJUGATE = 0.618033988749895f;

    private float currentHue = 0f;

    public int nextColor() {
        currentHue += GOLDEN_RATIO_CONJUGATE;
        currentHue %= 1.0f;
        int rgb = Color.HSBtoRGB(currentHue, 0.8f, 0.9f);
        return 0xFF000000 | rgb;
    }

    public int nextTranslucentColor(int alpha) {
        int color = nextColor();
        return (alpha << 24) | (color & 0x00FFFFFF);
    }
}
