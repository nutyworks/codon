package works.nuty.codon.client.ui.layout;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceSyntaxTest {
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
            new SourceSyntax.Match(2, 2, 7), new SourceSyntax.Match(4, 5, 10)), SourceSyntax.find(lines, "hello"));
        assertEquals(List.of(new SourceSyntax.Match(3, 4, 15)), SourceSyntax.find(lines, "[literal].*"));
        assertEquals(List.of(new SourceSyntax.Match(2, 8, 10)), SourceSyntax.find(lines, "한글"));
        assertTrue(SourceSyntax.find(lines, "").isEmpty());
    }
}
