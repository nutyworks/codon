package works.nuty.codon.client.ui.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Display-only lexical spans with conservative command boundaries. Never changes server stage offsets. */
public final class SourceSyntax {
    public enum Kind { COMMAND, KEYWORD, ARGUMENT, STRING, COMMENT, VALUE, RESOURCE, MACRO }
    public record Span(int start, int end, Kind kind) { }
    public record Match(int line, int start, int end) { }
    /** Bound both the client-thread search allocations and the per-line highlight index. */
    public static final int MAX_MATCHES = 1_000;
    public record SearchResults(List<Match> matches, boolean hasMore) {
        public SearchResults { matches = List.copyOf(matches); }
    }
    private static final Set<String> EXECUTE_KEYWORDS = Set.of("align", "anchored", "as", "at", "facing",
        "if", "unless", "in", "on", "positioned", "rotated", "store", "summon", "run");

    private SourceSyntax() { }

    public static List<Span> spans(String source) {
        List<Span> spans = new ArrayList<>();
        boolean command = true;
        boolean execute = false;
        boolean returnCommand = false;
        boolean schedule = false;
        int executeArgumentEnd = 0;
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
            at = tokenEnd(source, at);
            String token = source.substring(start, at);
            if (execute && start >= executeArgumentEnd && !EXECUTE_KEYWORDS.contains(token))
                executeArgumentEnd = Integer.MAX_VALUE;
            Kind kind;
            if (command) {
                kind = Kind.COMMAND;
                execute = token.equals("execute");
                executeArgumentEnd = at;
                returnCommand = token.equals("return");
                schedule = token.equals("schedule");
                command = false;
            } else if (execute && start >= executeArgumentEnd && EXECUTE_KEYWORDS.contains(token)) {
                kind = Kind.KEYWORD;
                if (token.equals("run")) { command = true; execute = false; }
                else executeArgumentEnd = executeArgumentEnd(source, start);
            } else if (returnCommand && token.equals("run")) {
                kind = Kind.KEYWORD;
                command = true;
                returnCommand = false;
            } else if (schedule && token.equals("function")) {
                kind = Kind.COMMAND;
                schedule = false;
            } else if (first == '"' || first == '\'') kind = Kind.STRING;
            else if (token.startsWith("$(")) kind = Kind.MACRO;
            else if (first == '@' || first == '~' || first == '^' || token.matches("[-+]?\\d+(?:\\.\\d+)?[bBsSlLfFdD]?")) kind = Kind.VALUE;
            else if (token.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) kind = Kind.RESOURCE;
            else kind = Kind.ARGUMENT;
            spans.add(new Span(start, at, kind));
        }
        return List.copyOf(spans);
    }

    private static int tokenEnd(String source, int at) {
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
        return at;
    }

    /** Bounded lookahead for known execute clauses, not a command validator. Unknown
     * or incomplete clauses suppress nested links rather than guessing at a run argument. */
    private static int executeArgumentEnd(String source, int start) {
        String[] tokens = new String[12];
        java.util.Arrays.fill(tokens, "");
        int[] ends = new int[12];
        int size = 0;
        for (int at = start; at < source.length() && size < tokens.length;) {
            if (Character.isWhitespace(source.charAt(at))) { at++; continue; }
            int end = tokenEnd(source, at);
            tokens[size] = source.substring(at, end);
            ends[size++] = end;
            at = end;
        }
        int arguments = switch (tokens[0]) {
            case "align", "anchored", "as", "at", "in", "on", "summon" -> 1;
            case "positioned" -> Set.of("as", "over").contains(tokens[1]) ? 2 : 3;
            case "rotated" -> 2;
            case "facing" -> 3;
            case "if", "unless" -> switch (tokens[1]) {
                case "entity", "predicate", "function", "dimension" -> 2;
                case "loaded" -> 4;
                case "stopwatch" -> 3;
                case "block", "biome" -> 5;
                case "blocks" -> 11;
                case "score" -> tokens[4].equals("matches") ? 5
                    : Set.of("<", "<=", "=", ">=", ">").contains(tokens[4]) ? 6 : -1;
                case "data" -> switch (tokens[2]) { case "entity", "storage" -> 4; case "block" -> 6; default -> -1; };
                default -> -1;
            };
            case "store" -> Set.of("result", "success").contains(tokens[1]) ? switch (tokens[2]) {
                case "score", "bossbar" -> 4;
                case "entity", "storage" -> 6;
                case "block" -> 8;
                default -> -1;
            } : -1;
            default -> -1;
        };
        return arguments >= 0 && size > arguments ? ends[arguments] : Integer.MAX_VALUE;
    }

    /** Exact source indices for a literal, case-insensitive search; no regex or source mutation. */
    public static SearchResults find(List<String> lines, String query) {
        if (query.isEmpty()) return new SearchResults(List.of(), false);
        List<Match> matches = new ArrayList<>();
        for (int line = 0; line < lines.size(); line++) {
            String source = lines.get(line);
            // regionMatches retains original UTF-16 indices even when case folding changes length.
            for (int at = 0; at <= source.length() - query.length(); at++) {
                if (source.regionMatches(true, at, query, 0, query.length())) {
                    // Probe one extra occurrence without materializing it, then stop scanning.
                    if (matches.size() == MAX_MATCHES) return new SearchResults(matches, true);
                    matches.add(new Match(line + 1, at, at + query.length()));
                    at += query.length() - 1;
                }
            }
        }
        return new SearchResults(matches, false);
    }
}
