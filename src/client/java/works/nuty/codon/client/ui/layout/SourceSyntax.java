package works.nuty.codon.client.ui.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Display-only lexical spans. Never parses commands or changes server stage offsets. */
public final class SourceSyntax {
    public enum Kind { COMMAND, KEYWORD, ARGUMENT, STRING, COMMENT, VALUE, RESOURCE, MACRO }
    public record Span(int start, int end, Kind kind) { }
    public record Match(int line, int start, int end) { }
    private static final Set<String> EXECUTE_KEYWORDS = Set.of("align", "anchored", "as", "at", "facing",
        "if", "unless", "in", "on", "positioned", "rotated", "store", "summon", "run");

    private SourceSyntax() { }

    public static List<Span> spans(String source) {
        List<Span> spans = new ArrayList<>();
        boolean command = true;
        boolean execute = false;
        for (int at = 0; at < source.length();) {
            char first = source.charAt(at);
            if (Character.isWhitespace(first)) { at++; continue; }
            int start = at;
            if (command && first == '#') {
                spans.add(new Span(at, source.length(), Kind.COMMENT));
                break;
            }
            if (command && first == '$') {
                spans.add(new Span(at, ++at, Kind.MACRO));
                continue;
            }
            // Quoted strings and selectors/NBT are single arguments, including embedded whitespace.
            char quote = 0;
            int depth = 0;
            while (at < source.length()) {
                char ch = source.charAt(at);
                if (quote != 0) {
                    if (ch == '\\' && at + 1 < source.length()) { at += 2; continue; }
                    if (ch == quote) quote = 0;
                } else if (ch == '"' || ch == '\'') quote = ch;
                else if (ch == '[' || ch == '{' || ch == '(') depth++;
                else if (ch == ']' || ch == '}' || ch == ')') depth = Math.max(0, depth - 1);
                else if (depth == 0 && Character.isWhitespace(ch)) break;
                at++;
            }
            String token = source.substring(start, at);
            Kind kind;
            if (command) {
                kind = Kind.COMMAND;
                execute = token.equals("execute");
                command = false;
            } else if (execute && EXECUTE_KEYWORDS.contains(token)) {
                kind = Kind.KEYWORD;
                if (token.equals("run")) { command = true; execute = false; }
            } else if (first == '"' || first == '\'') kind = Kind.STRING;
            else if (token.startsWith("$(")) kind = Kind.MACRO;
            else if (first == '@' || first == '~' || first == '^' || token.matches("[-+]?\\d+(?:\\.\\d+)?[bBsSlLfFdD]?")) kind = Kind.VALUE;
            else if (token.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) kind = Kind.RESOURCE;
            else kind = Kind.ARGUMENT;
            spans.add(new Span(start, at, kind));
        }
        return List.copyOf(spans);
    }

    /** Exact source indices for a literal, case-insensitive search; no regex or source mutation. */
    public static List<Match> find(List<String> lines, String query) {
        if (query.isEmpty()) return List.of();
        List<Match> matches = new ArrayList<>();
        for (int line = 0; line < lines.size(); line++) {
            String source = lines.get(line);
            // regionMatches retains original UTF-16 indices even when case folding changes length.
            for (int at = 0; at <= source.length() - query.length(); at++) {
                if (source.regionMatches(true, at, query, 0, query.length())) {
                    matches.add(new Match(line + 1, at, at + query.length()));
                    at += query.length() - 1;
                }
            }
        }
        return List.copyOf(matches);
    }
}
