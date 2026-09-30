package works.nuty.codon.client.ui;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.FunctionId;
import works.nuty.codon.client.ui.layout.SourceInteraction;
import works.nuty.codon.client.ui.layout.SourceReferences;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SourceReviewRuntimeTest {
    @Test void currentMinecraftIdentifierUsesMinecraftForUnqualifiedFunctions() {
        assertEquals("minecraft", Identifier.parse("helper").getNamespace());
        assertEquals("helper", Identifier.parse("helper").getPath());
        assertEquals("pack", Identifier.parse("pack:helper").getNamespace());
    }

    @Test void sourceLinkMustNotCaptureTheFirstPixelOfTheNextRow() throws Exception {
        var type = Class.forName("works.nuty.codon.client.ui.FunctionSourceScreen$FunctionHit");
        var constructor = type.getDeclaredConstructor(SourceInteraction.HitBox.class, FunctionId.class);
        constructor.setAccessible(true);
        int rowY = 100;
        var bounds = SourceInteraction.clippedRowHit(200, 250, rowY, 18, 180, 100, 100, 36);
        var hit = constructor.newInstance(bounds, new FunctionId("pack", "helper"));
        var contains = type.getDeclaredMethod("contains", double.class, double.class);
        contains.setAccessible(true);
        assertFalse((boolean) contains.invoke(hit, 220d, (double) rowY + 18), "next row's first pixel must not follow the previous function link");
        assertTrue((boolean) contains.invoke(hit, 220d, (double) rowY), "current row's first pixel belongs to its own link");
    }

    @Test void loadedFunctionCollisionUsesActualIdentifierDefaultAndKeepsExplicitNamespaces() {
        var vanilla = new FunctionId("minecraft", "helper");
        var packed = new FunctionId("pack", "helper");
        var loaded = List.of(packed, vanilla);
        var actual = Identifier.parse("helper");
        assertEquals(new FunctionId(actual.getNamespace(), actual.getPath()), SourceReferences.loadedFunction("helper", loaded));
        assertEquals(vanilla, SourceReferences.loadedFunction("minecraft:helper", loaded));
        assertEquals(packed, SourceReferences.loadedFunction("pack:helper", loaded));
        assertNull(SourceReferences.loadedFunction("helper", List.of(packed)), "do not fall back to the caller's namespace");
        assertNull(SourceReferences.loadedFunction("#pack:helper", loaded));
    }
}
