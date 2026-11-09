package works.nuty.codon.core.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static works.nuty.codon.core.model.WatchExpression.Slot.*;

class WatchExpressionTest {
    /** Stand-in for vanilla's NbtPathArgument: a path runs to the first space outside quotes and brackets. */
    private static final WatchExpression.PathScanner PATHS = text -> {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && quoted) i++;
            else if (c == '"') quoted = !quoted;
            else if (!quoted && (c == '[' || c == '{')) depth++;
            else if (!quoted && (c == ']' || c == '}')) depth--;
            else if (!quoted && depth == 0 && Character.isWhitespace(c)) return i;
        }
        return quoted || depth != 0 ? -1 : text.length();
    };

    private static WatchExpression.Result parse(String text) { return WatchExpression.parse(text, PATHS); }

    private static final UUID ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test void scoreObjectiveAloneIsValidAndStillCompletingTheObjective() {
        var result = parse("score points");
        assertEquals(OBJECTIVE, result.slot());
        assertEquals("points", result.prefix());
        assertEquals(new WatchSpec(WatchSpec.Kind.SCORE, "points", ""), result.spec());
    }

    @Test void scoreHolderIsLiteralWhenQuotedAndAnExecutorWhenItIsAUuid() {
        assertEquals(WatchSpec.scoreHolder("points", "Steve"), parse("score points Steve").spec());
        assertEquals(WatchSpec.scoreHolder("points", "two words"),
            parse("score points \"two words\"").spec());
        assertEquals(WatchSpec.scoreHolder("points", ID.toString()),
            parse("score points \"" + ID + "\"").spec());
        assertEquals(new WatchSpec(WatchSpec.Kind.SCORE, "points", "", ID, null),
            parse("score points " + ID).spec());
        assertNull(parse("score points two words").spec());
    }

    @Test void everyValidWatchFormatsWithinTheMaximumLengthAndParsesBack() {
        String objective = "o".repeat(WatchSpec.MAX_INPUT_LENGTH);
        WatchSpec worst = WatchSpec.scoreHolder(objective, "\\".repeat(WatchSpec.MAX_INPUT_LENGTH));
        String text = WatchExpression.format(worst);
        assertTrue(text.length() <= WatchExpression.MAX_LENGTH, "formatted length " + text.length());
        assertEquals(worst, parse(text).spec());
    }

    @Test void entityPathStopsAtNbtPathEndSoFiltersAndIndexesKeepTheirSpaces() {
        assertEquals(new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Pos[0]"), parse("entity Pos[0]").spec());
        String filtered = "Items[{id:\"minecraft:stone\", count:1}].count";
        assertEquals(new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", filtered),
            parse("entity " + filtered).spec());
        assertEquals(new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health", ID, null),
            parse("entity Health " + ID).spec());
        var badTail = parse("entity Health nobody");
        assertNull(badTail.spec());
        assertEquals("entity", badTail.error());
    }

    @Test void unfinishedPathOffersCompletionsInsteadOfAnError() {
        var result = parse("entity Attributes[");
        assertEquals(ENTITY_PATH, result.slot());
        assertNull(result.spec());
        assertEquals("Attributes[", result.prefix());
    }

    @Test void storageNeedsAnIdAndPathAndNormalisesTheNamespace() {
        assertEquals(STORAGE_ID, parse("storage demo").slot());
        assertEquals(STORAGE_PATH, parse("storage demo:state ").slot());
        assertEquals(new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:state", "counter"),
            parse("storage demo:state counter").spec());
        assertEquals("minecraft:state", parse("storage state counter").spec().target());
        assertNull(parse("storage Bad:Id counter").spec());
    }

    @Test void unknownKeywordIsReportedOnlyOnceSomethingWasTyped() {
        assertEquals("", parse("").error());
        assertEquals(KEYWORD, parse("sco").slot());
        assertEquals("keyword", parse("lookup x").error());
    }

    @Test void completionStartPointsAtTheTokenToReplace() {
        assertEquals(6, parse("score po").start());
        assertEquals(13, parse("score points St").start());
    }

    @Test void formatRoundTripsEverySavedShape() {
        var specs = new WatchSpec[] {
            new WatchSpec(WatchSpec.Kind.SCORE, "points", ""),
            WatchSpec.scoreHolder("points", "two words"),
            WatchSpec.scoreHolder("points", ID.toString()),
            new WatchSpec(WatchSpec.Kind.SCORE, "points", "", ID, null),
            new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Items[{id:\"minecraft:stone\"}].count", ID, null),
            new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:state", "list[0].value")
        };
        for (WatchSpec spec : specs) assertEquals(spec, parse(WatchExpression.format(spec)).spec(),
            WatchExpression.format(spec));
    }
}
