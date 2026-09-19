package works.nuty.codon.client.ui;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.Vec3d;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceDetailsFormattingTest {
    @Test
    void coordinateChangeKeepsUnchangedAnglesAsPlaceholders() {
        var before = source(7.5, -59.5, 5.5, 220.2f, 0);
        var after = source(-4.73, -60, 11.77, 220.2f, 0);
        assertEquals("-4.73 / -60.00 / 11.77 / 220.2° / 0.0°", SourceDetailsFormatting.transform(after));
        assertEquals("← 7.50 / -59.50 / 5.50 / - / -", SourceDetailsFormatting.previousTransform(before, after));
    }

    @Test
    void eachAxisAndAngleIsComparedIndependently() {
        var before = source(1, 2, 3, 45, -10);
        assertEquals("← - / 2.00 / - / - / -", SourceDetailsFormatting.previousTransform(before, source(1, 4, 3, 45, -10)));
        assertEquals("← - / - / - / 45.0° / -10.0°", SourceDetailsFormatting.previousTransform(before, source(1, 2, 3, 90, 0)));
    }

    @Test
    void missingParentOrUnchangedTransformDoesNotProduceHistoryRow() {
        var source = source(1, 2, 3, 45, -10);
        assertEquals("", SourceDetailsFormatting.previousTransform(null, source));
        assertEquals("", SourceDetailsFormatting.previousTransform(source, source));
        var otherDimension = new PauseSource(source.anchor(), source.pitch(), source.yaw(), null, "minecraft:the_nether");
        assertEquals("", SourceDetailsFormatting.previousTransform(source, otherDimension));
    }

    private static PauseSource source(double x, double y, double z, float yaw, float pitch) {
        return new PauseSource(new Vec3d(x, y, z), pitch, yaw, null, "minecraft:overworld");
    }
}
