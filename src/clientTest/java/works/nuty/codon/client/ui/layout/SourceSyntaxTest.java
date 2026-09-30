package works.nuty.codon.client.ui.layout;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceSyntaxTest {
    @Test void standardItemsAndSlotsConditionsKeepTheirRealNestedFunction() {
        for (String condition : List.of("if", "unless"))
            for (String target : List.of("entity run", "block ~ ~1 ~-2"))
                for (String kind : List.of("items", "slots")) {
                    String source = "execute " + condition + " " + kind + " " + target
                        + " container.*" + (kind.equals("items") ? " minecraft:stick" : "") + " run function pack:helper";
                    var spans = SourceSyntax.spans(source);
                    var function = spans.stream().filter(span -> source.substring(span.start(), span.end()).equals("function"))
                        .findFirst().orElseThrow();
                    assertEquals(SourceSyntax.Kind.COMMAND, function.kind(), source);
                    if (target.startsWith("entity")) {
                        var argument = spans.stream().filter(span -> source.substring(span.start(), span.end()).equals("run"))
                            .findFirst().orElseThrow();
                        assertEquals(SourceSyntax.Kind.ARGUMENT, argument.kind(), source);
                    }
                }
        String source = "execute if items entity @s weapon.mainhand minecraft:stick[minecraft:custom_data~{label:'run function pack:other'}] run function pack:helper";
        var spans = SourceSyntax.spans(source);
        assertEquals(1, spans.stream().filter(span -> span.kind() == SourceSyntax.Kind.COMMAND
            && source.substring(span.start(), span.end()).equals("function")).count());
        assertEquals("function", source.substring(spans.get(spans.size() - 2).start(), spans.get(spans.size() - 2).end()));
        assertEquals(SourceSyntax.Kind.COMMAND, spans.get(spans.size() - 2).kind());
        String chained = "execute if items entity run weapon.mainhand minecraft:stick unless slots block 0 64 -2 container.0 run function pack:helper";
        var chain = SourceSyntax.spans(chained);
        assertEquals(SourceSyntax.Kind.COMMAND, chain.get(chain.size() - 2).kind(), chained);
    }

    @Test void itemSlotAndPredicateRunArgumentsAndUnsupportedFormsDoNotInventLinks() {
        for (String source : List.of("execute if items entity run run run run function pack:helper",
            "execute unless slots entity run run run function pack:helper")) {
            var spans = SourceSyntax.spans(source);
            assertEquals(SourceSyntax.Kind.COMMAND, spans.get(spans.size() - 2).kind(), source);
            assertTrue(spans.stream().filter(span -> source.substring(span.start(), span.end()).equals("run"))
                .limit(source.contains(" items ") ? 3 : 2).allMatch(span -> span.kind() == SourceSyntax.Kind.ARGUMENT), source);
        }
        for (String source : List.of("execute if items storage run slot item run function pack:helper",
            "execute if slots unknown run run function pack:helper", "execute if items entity run weapon.mainhand run function pack:helper",
            "execute unless slots entity run run function pack:helper"))
            assertTrue(SourceSyntax.spans(source).stream().noneMatch(span -> span.kind() == SourceSyntax.Kind.COMMAND
                && source.substring(span.start(), span.end()).equals("function")), source);
    }
    @Test void executeArgumentsNamedRunDoNotBecomeCommandDelimiters() {
        for (String source : List.of(
            "execute if score run objective matches 1 run function demo:next",
            "execute as run at @s run function demo:next",
            "execute if score run run = run run run function demo:next",
            "execute store result score run run run function demo:next",
            "execute if stopwatch run 1 run function demo:next",
            "execute if data storage run path run function demo:next",
            "execute store success storage run path int 1 run function demo:next",
            "execute facing entity run eyes run function demo:next")) {
            var spans = SourceSyntax.spans(source);
            var function = spans.stream().filter(span -> source.substring(span.start(), span.end()).equals("function"))
                .findFirst().orElseThrow();
            assertEquals(SourceSyntax.Kind.COMMAND, function.kind(), source);
            var firstRun = spans.stream().filter(span -> source.substring(span.start(), span.end()).equals("run"))
                .findFirst().orElseThrow();
            assertEquals(SourceSyntax.Kind.ARGUMENT, firstRun.kind(), source);
        }
        String argument = "execute if score run function matches 1 run say minecraft:matches";
        var function = SourceSyntax.spans(argument).stream()
            .filter(span -> argument.substring(span.start(), span.end()).equals("function")).findFirst().orElseThrow();
        assertEquals(SourceSyntax.Kind.ARGUMENT, function.kind(), "a score objective named function is not a link");
    }

    @Test void unknownOrIncompleteExecuteClausesNeverGuessACommandBoundary() {
        for (String source : List.of("execute if unknown run function demo:next",
            "execute if score run objective matches", "execute positioned run function demo:next"))
            assertTrue(SourceSyntax.spans(source).stream().noneMatch(span -> span.kind() == SourceSyntax.Kind.COMMAND
                && source.substring(span.start(), span.end()).equals("function")), source);
    }
    @Test void nestedReturnAndScheduleFunctionPositionsExcludeSayTextAndStrings() {
        for (String source : List.of("return run function demo:inner", "schedule function demo:inner 1t",
            "execute as @s run return run function demo:inner")) {
            var function = SourceSyntax.spans(source).stream()
                .filter(span -> source.substring(span.start(), span.end()).equals("function")).findFirst().orElseThrow();
            assertEquals(SourceSyntax.Kind.COMMAND, function.kind());
        }
        assertEquals(SourceSyntax.Kind.ARGUMENT, SourceSyntax.spans("say function demo:inner").get(1).kind());
        assertEquals(SourceSyntax.Kind.STRING, SourceSyntax.spans("say \"return run function demo:inner\"").getLast().kind());
        assertEquals(SourceSyntax.Kind.COMMENT, SourceSyntax.spans("# schedule function demo:inner").getFirst().kind());
    }
    @Test void commentsStringsSelectorsAndMacrosRetainOriginalOffsets() {
        assertEquals(List.of(new SourceSyntax.Span(2, 30, SourceSyntax.Kind.COMMENT)),
            SourceSyntax.spans("  # function demo:hidden 주석 끝!"));
        String source = "$execute as @e[tag=\"run say\"] run say \"escaped \\\" text\" $(value)";
        var spans = SourceSyntax.spans(source);
        assertEquals(SourceSyntax.Kind.MACRO, spans.getFirst().kind());
        assertEquals(SourceSyntax.Kind.COMMAND, spans.get(1).kind());
        assertEquals("@e[tag=\"run say\"]", source.substring(spans.get(3).start(), spans.get(3).end()));
        assertEquals(SourceSyntax.Kind.VALUE, spans.get(3).kind());
        assertEquals(SourceSyntax.Kind.COMMAND, spans.get(5).kind());
        assertEquals(SourceSyntax.Kind.STRING, spans.get(6).kind());
        assertEquals(SourceSyntax.Kind.MACRO, spans.getLast().kind());
        for (int i = 1; i < spans.size(); i++) assertTrue(spans.get(i - 1).end() <= spans.get(i).start());
    }

    @Test void anIncompleteQuotedArgumentAndHashInSayTextAreNotComments() {
        assertEquals(SourceSyntax.Kind.STRING, SourceSyntax.spans("say \"unfinished").getLast().kind());
        assertEquals(SourceSyntax.Kind.ARGUMENT, SourceSyntax.spans("say #hello").getLast().kind());
        assertTrue(SourceSyntax.spans("   ").isEmpty());
    }

    @Test void literalSearchIncludesCommentsUnicodeAndMultipleMatchesWithoutChangingSource() {
        List<String> lines = List.of("say Hello hello", "# HELLO 한글", "say [literal].*", "say İHELLO");
        assertEquals(List.of(new SourceSyntax.Match(1, 4, 9), new SourceSyntax.Match(1, 10, 15),
            new SourceSyntax.Match(2, 2, 7), new SourceSyntax.Match(4, 5, 10)), SourceSyntax.find(lines, "hello").matches());
        assertEquals(List.of(new SourceSyntax.Match(3, 4, 15)), SourceSyntax.find(lines, "[literal].*").matches());
        assertEquals(List.of(new SourceSyntax.Match(2, 8, 10)), SourceSyntax.find(lines, "한글").matches());
        assertEquals(new SourceSyntax.SearchResults(List.of(), false), SourceSyntax.find(lines, ""));
    }

    @Test void denseSourceStopsAfterTheLimitAndOneProbeWithoutReadingTheRest() {
        String line = "# " + "a".repeat(13_998);
        // 50 lines fit the repository's 700,000-character response limit.
        List<String> lines = new java.util.AbstractList<>() {
            @Override public int size() { return 50; }
            @Override public String get(int index) {
                assertEquals(0, index, "a capped search must stop before reading later lines");
                return line;
            }
        };
        var results = SourceSyntax.find(lines, "A");
        assertTrue(results.hasMore());
        assertEquals(SourceSyntax.MAX_MATCHES, results.matches().size());
        assertEquals(new SourceSyntax.Match(1, 2, 3), results.matches().getFirst());
        assertEquals(new SourceSyntax.Match(1, SourceSyntax.MAX_MATCHES + 1, SourceSyntax.MAX_MATCHES + 2),
            results.matches().getLast());
    }

    @Test void limitIsGlobalAndOnlyMarksAnActualExtraNonOverlappingOccurrence() {
        String exact = "aa".repeat(SourceSyntax.MAX_MATCHES);
        var atLimit = SourceSyntax.find(List.of(exact, "# no match"), "aa");
        assertEquals(SourceSyntax.MAX_MATCHES, atLimit.matches().size());
        assertFalse(atLimit.hasMore());
        assertEquals(new SourceSyntax.Match(1, exact.length() - 2, exact.length()), atLimit.matches().getLast());
        var overLimit = SourceSyntax.find(List.of("aa", exact), "aa");
        assertTrue(overLimit.hasMore());
        assertEquals(SourceSyntax.MAX_MATCHES, overLimit.matches().size());
        assertEquals(new SourceSyntax.Match(2, exact.length() - 4, exact.length() - 2), overLimit.matches().getLast());
        assertEquals(new SourceSyntax.SearchResults(List.of(), false), SourceSyntax.find(List.of(exact), "missing"));
    }
}
