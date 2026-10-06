package works.nuty.codon.client.ui.layout;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import works.nuty.codon.client.ui.layout.SourceInteraction.FunctionQuery;
import works.nuty.codon.core.model.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceInteractionTest {
    @Test void editingRetainsOnlyItsInactiveSlotUntilTheEditorCloses() {
        assertTrue(SourceInteraction.markerVisible(false, false, true));
        assertFalse(SourceInteraction.markerVisible(false, false, false));
        assertTrue(SourceInteraction.markerVisible(true, false, false));
        var pinned = new SourceLineLayout(10, List.of(3), i -> i * 6);
        var closed = new SourceLineLayout(10, List.of(), i -> i * 6);
        assertEquals(closed.width() + SourceLineLayout.MARKER_WIDTH, pinned.width());
        for (int i = 0; i <= 10; i++) assertEquals(i * 6, closed.x(i));
    }
    @Test void linksStayInsideTheirOwnRowAndVisibleViewport() {
        var first = SourceInteraction.clippedRowHit(190, 260, 100, 18, 200, 100, 40, 36);
        var second = SourceInteraction.clippedRowHit(190, 260, 118, 18, 200, 100, 40, 36);
        assertTrue(first.contains(220, 100));
        assertTrue(first.contains(220, 117.999));
        assertFalse(first.contains(220, 118));
        assertTrue(second.contains(220, 118));
        assertFalse(first.contains(199, 110));
        assertFalse(first.contains(240, 110));
        var finalRow = SourceInteraction.clippedRowHit(200, 260, 118, 18, 200, 100, 40, 30);
        assertFalse(finalRow.contains(220, 130));
        assertNull(SourceInteraction.clippedRowHit(200, 260, 136, 18, 200, 100, 40, 36));
    }

    @Test void obsoleteStageFingerprintIsAReviewWarningRatherThanAReplacementControl() {
        var location = new SourceLocation.Function(new FunctionLocation(new FunctionId("pack", "main"), 1));
        var old = BreakpointDefinition.plain(BreakpointTarget.stage(location, 0, "execute run say old"));
        String next = BreakpointTarget.fingerprint("execute run say new");
        assertTrue(SourceInteraction.stageNeedsReview(old, next));
        assertFalse(SourceInteraction.stageNeedsReview(old, old.target().commandFingerprint()));
        assertTrue(SourceInteraction.stageNeedsReview(old.withStaleSource(true), next));
        assertFalse(SourceInteraction.stageNeedsReview(BreakpointDefinition.plain(BreakpointTarget.stage(location, 0, "execute run say new")), next));
        assertFalse(SourceInteraction.stageNeedsReview(BreakpointDefinition.plain(BreakpointTarget.whole(location)), next));
    }
    @Test void nativeHorizontalAndShiftWheelKeepTheirOwnDirection() {
        assertEquals(60, SourceInteraction.horizontalMovement(2, 0, false));
        assertEquals(-60, SourceInteraction.horizontalMovement(-2, 0, false));
        assertEquals(60, SourceInteraction.horizontalMovement(0, -2, true));
        assertEquals(-60, SourceInteraction.horizontalMovement(0, 2, true));
        assertEquals(60, SourceInteraction.horizontalMovement(2, 4, true));
        assertEquals(.3, SourceInteraction.horizontalMovement(.01, 0, false), .0001);
        assertEquals(0, SourceInteraction.horizontalMovement(0, -2, false));
    }

    @Test void splitterKeepsBothPanesUsableAndClampsOnlyTheCurrentViewport() {
        assertEquals(150, SourceInteraction.treeWidth(760, -10));
        assertEquals(460, SourceInteraction.treeWidth(760, 900));
        assertEquals(292, SourceInteraction.treeWidth(592, 410));
        assertEquals(410, SourceInteraction.treeWidth(760, 410));
    }

    @Test void onlyEnabledOrHoveredMarkersReserveSpaceAndLeavingRestoresEveryAdvance() {
        for (boolean enabled : new boolean[]{false, true}) {
            for (boolean hover : new boolean[]{false, true}) {
                boolean visible = SourceInteraction.markerVisible(enabled, hover);
                assertEquals(enabled || hover, visible);
                var layout = new SourceLineLayout(10, visible ? List.of(3) : List.of(), i -> i * 6);
                assertEquals(60 + (visible ? 20 : 0), layout.width());
                var left = new SourceLineLayout(10, enabled ? List.of(3) : List.of(), i -> i * 6);
                for (int i = 0; i <= 10; i++)
                    assertEquals(i * 6 + (enabled && i >= 3 ? 20 : 0), left.x(i));
            }
        }
    }

    @Test void blankFunctionQueriesAreNoFilterExactlyWhereStringIsBlankSaysSo() {
        for (String blank : List.of("", " ", "\t\n\r\f\u000b", " 　  \u001c", "   ")) {
            assertTrue(blank.isBlank());
            assertSame(FunctionQuery.NONE, FunctionQuery.parse(blank));
            assertFalse(FunctionQuery.parse(blank).active());
            assertTrue(FunctionQuery.parse(blank).matches("pack:util/tick"));
        }
        // The tree's expansion, glyph and click guard all read active(), so it must never
        // disagree with the String.isBlank test the click handler used before.
        for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            String text = Character.toString(codePoint);
            if (text.isBlank() == FunctionQuery.parse(text).active())
                fail("U+%04X disagrees with String.isBlank".formatted(codePoint));
        }
        assertTrue(FunctionQuery.parse(" ").active(), "a no-break space is not Java whitespace");
    }

    @Test void mixedJavaWhitespaceSeparatesTermsAndOuterWhitespaceIsIgnored() {
        assertEquals(List.of("pack", "tick"), FunctionQuery.parse(" \tPack　  TICK\n").terms());
        assertEquals(List.of("pack:util/tick"), FunctionQuery.parse(" pack:util/tick ").terms());
        assertEquals(List.of("pack tick"), FunctionQuery.parse("pack tick").terms());
    }

    @Test void everyTermMustOccurInAnyOrderAndAnyOverlap() {
        String tick = "pack:util/tick";
        assertTrue(FunctionQuery.parse("pack tick").matches(tick));
        assertTrue(FunctionQuery.parse("tick pack").matches(tick));
        assertTrue(FunctionQuery.parse("util\tTICK 　pack").matches(tick));
        assertTrue(FunctionQuery.parse("util til").matches(tick));
        assertFalse(FunctionQuery.parse("pack util/reset").matches(tick));
        assertFalse(FunctionQuery.parse("lib tick").matches(tick));
        assertFalse(FunctionQuery.parse("pack tick missing").matches(tick));
        assertFalse(FunctionQuery.parse("é").matches(tick));
        // The same query keeps the rows that contain all terms and drops the others.
        List<String> ids = List.of("lib:tick", "pack:main", "pack:util/reset", "pack:util/tick");
        assertEquals(List.of("pack:util/tick"), ids.stream().filter(FunctionQuery.parse("pack tick")::matches).toList());
    }

    @Test void caseFoldingUsesTheRootLocaleEvenWhenTheDefaultLocaleIsTurkish() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.of("tr", "TR"));
            assertEquals("tıck", "TICK".toLowerCase(), "premise: the default locale would fold I to a dotless i");
            assertTrue(FunctionQuery.parse("PACK TICK").matches("pack:util/tick"));
            assertTrue(FunctionQuery.parse("pack:util/tick").matches("PACK:UTIL/TICK"));
            assertEquals(List.of("pack", "tick"), FunctionQuery.parse("PACK TICK").terms());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test void singleTermsStayLiteralNamespacePathSubstringsWithoutPatternSemantics() {
        String tick = "pack:util/tick";
        for (String term : List.of("tick", "PACK", "pack:", ":util", "k:u", "util/", "/tick", "pack:util/tick", ":", "/"))
            assertTrue(FunctionQuery.parse(term).matches(tick), term);
        assertFalse(FunctionQuery.parse("pack:util/tick/extra").matches(tick));
        for (String pattern : List.of(".*", "pack.util", "t[a-z]ck", "^pack", "tick$", "pack|lib", "(tick)", "util\\/tick", "ti?ck"))
            assertFalse(FunctionQuery.parse(pattern).matches(tick), pattern);
        assertTrue(FunctionQuery.parse("v1.2 a-b_c").matches("pack:v1.2/a-b_c"));
        assertFalse(FunctionQuery.parse("v1.2").matches("pack:v1x2/a-b_c"));
    }
}
