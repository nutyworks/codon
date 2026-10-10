package works.nuty.codon.client.ui;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.Vec3d;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceDetailsFormattingTest {
    @Test
    void coordinateChangeDoesNotProduceRotationHistory() {
        var before = source(7.5, -59.5, 5.5, 220.2f, 0);
        var after = source(-4.73, -60, 11.77, 220.2f, 0);
        assertEquals("X -4.73 / Y -60.00 / Z 11.77", SourceDetailsFormatting.position(after));
        assertEquals("yaw 220.2° / pitch 0.0°", SourceDetailsFormatting.rotation(after, "yaw", "pitch"));
        assertEquals("", SourceDetailsFormatting.previousRotation(before, after, "yaw", "pitch"));
        assertEquals("← X 7.50 / Y -59.50 / Z 5.50", SourceDetailsFormatting.previousPosition(before, after));
    }

    @Test
    void eachAxisAndAngleIsComparedIndependently() {
        var before = source(1, 2, 3, 45, -10);
        assertEquals("← X - / Y 2.00 / Z -", SourceDetailsFormatting.previousPosition(before, source(1, 4, 3, 45, -10)));
        assertEquals("← yaw - / pitch -10.0°", SourceDetailsFormatting.previousRotation(before, source(1, 2, 3, 45, 0), "yaw", "pitch"));
        assertEquals("", SourceDetailsFormatting.previousPosition(before, source(1, 2, 3, 90, 0)));
        assertEquals("← yaw 45.0° / pitch -10.0°", SourceDetailsFormatting.previousRotation(before, source(1, 2, 3, 90, 0), "yaw", "pitch"));
    }

    @Test
    void missingParentOrUnchangedTransformDoesNotProduceHistoryRow() {
        var source = source(1, 2, 3, 45, -10);
        assertEquals("", SourceDetailsFormatting.previousPosition(null, source));
        assertEquals("", SourceDetailsFormatting.previousRotation(null, source, "yaw", "pitch"));
        assertEquals("", SourceDetailsFormatting.previousRotation(source, source, "yaw", "pitch"));
        assertEquals("", SourceDetailsFormatting.previousPosition(source, source));
        var otherDimension = new PauseSource(source.anchor(), source.pitch(), source.yaw(), null, "minecraft:the_nether");
        assertEquals("", SourceDetailsFormatting.previousPosition(source, otherDimension));
    }

    @Test
    void localizedAnglesPreserveValuesAndIndependentHistory() {
        var before = source(1, 2, 3, 45, -10);
        var after = source(1, 2, 3, 45, 0);
        assertEquals("수평각 45.0° / 수직각 0.0°", SourceDetailsFormatting.rotation(after, "수평각", "수직각"));
        assertEquals("← 수평각 - / 수직각 -10.0°",
            SourceDetailsFormatting.previousRotation(before, after, "수평각", "수직각"));
        assertEquals("", SourceDetailsFormatting.previousRotation(null, after, "수평각", "수직각"));
    }

    private static PauseSource source(double x, double y, double z, float yaw, float pitch) {
        return new PauseSource(new Vec3d(x, y, z), pitch, yaw, null, "minecraft:overworld");
    }
}
