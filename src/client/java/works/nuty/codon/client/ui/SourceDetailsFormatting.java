package works.nuty.codon.client.ui;

import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.PauseSource;

import java.util.Locale;

/** Coordinate and rotation rows, in X / Y / Z / yaw / pitch order. */
public final class SourceDetailsFormatting {
    private SourceDetailsFormatting() { }

    public static String transform(PauseSource source) {
        return String.format(Locale.ROOT, "%.2f / %.2f / %.2f / %.1f° / %.1f°",
            source.anchor().x(), source.anchor().y(), source.anchor().z(), source.yaw(), source.pitch());
    }

    public static String previousTransform(@Nullable PauseSource before, PauseSource after) {
        if (before == null) return "";
        double[] oldValues = values(before);
        double[] newValues = values(after);
        String[] fields = new String[oldValues.length];
        boolean changed = false;
        for (int i = 0; i < fields.length; i++) {
            if (oldValues[i] == newValues[i]) {
                fields[i] = "-";
            } else {
                fields[i] = String.format(Locale.ROOT, i < 3 ? "%.2f" : "%.1f°", oldValues[i]);
                changed = true;
            }
        }
        return changed ? "← " + String.join(" / ", fields) : "";
    }

    private static double[] values(PauseSource source) {
        return new double[] { source.anchor().x(), source.anchor().y(), source.anchor().z(),
            source.yaw(), source.pitch() };
    }
}
