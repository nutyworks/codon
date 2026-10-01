package works.nuty.codon.client.ui;

import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

/** Carries Codon ownership through deferred glyph preparation without changing geometry. */
public final class CodonTextPose extends Matrix3x2f {
    private final double gameScale;

    public CodonTextPose(Matrix3x2fc pose, double gameScale) {
        super(pose);
        this.gameScale = gameScale;
    }

    public boolean needsFiltering() {
        // Dense Minecraft fonts need coverage below 2x; fractional magnification
        // is phase dependent even when every texel is represented.
        double x = Math.hypot(m00(), m01()) * gameScale;
        double y = Math.hypot(m10(), m11()) * gameScale;
        return !wholeTexels(x) || !wholeTexels(y);
    }

    public double gameScale() { return gameScale; }

    private static boolean wholeTexels(double scale) {
        return scale >= 2 && Math.abs(scale - Math.rint(scale)) < 0.0001;
    }
}
