package works.nuty.codon.client.ui;

import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.PauseSource;

import java.util.Locale;

/** Separate labeled coordinate and rotation rows. */
public final class SourceDetailsFormatting {
    private SourceDetailsFormatting() { }

    public static String position(PauseSource source) {
        return String.format(Locale.ROOT, "X %.2f / Y %.2f / Z %.2f",
            source.anchor().x(), source.anchor().y(), source.anchor().z());
    }

    public static String rotation(PauseSource source) {
        return String.format(Locale.ROOT, "yaw %.1f° / pitch %.1f°", source.yaw(), source.pitch());
    }

    public static String previousPosition(@Nullable PauseSource before, PauseSource after) {
        if (before == null) return "";
        return previous(new double[] { before.anchor().x(), before.anchor().y(), before.anchor().z() },
            new double[] { after.anchor().x(), after.anchor().y(), after.anchor().z() },
            new String[] { "X", "Y", "Z" }, "%.2f");
    }

    public static String previousRotation(@Nullable PauseSource before, PauseSource after) {
        if (before == null) return "";
        return previous(new double[] { before.yaw(), before.pitch() },
            new double[] { after.yaw(), after.pitch() }, new String[] { "yaw", "pitch" }, "%.1f°");
    }

    private static String previous(double[] oldValues, double[] newValues, String[] labels, String format) {
        String[] fields = new String[oldValues.length];
        boolean changed = false;
        for (int i = 0; i < fields.length; i++) {
            boolean differs = oldValues[i] != newValues[i];
            fields[i] = labels[i] + " " + (differs ? String.format(Locale.ROOT, format, oldValues[i]) : "-");
            changed |= differs;
        }
        return changed ? "← " + String.join(" / ", fields) : "";
    }
}
