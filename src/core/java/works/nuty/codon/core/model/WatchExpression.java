package works.nuty.codon.core.model;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * One-line Watch syntax that maps onto the three existing watch kinds:
 * {@code score <objective> [holder|uuid]}, {@code entity <path> [uuid]} and
 * {@code storage <id> <path>}. A quoted holder is always a literal score holder.
 */
public final class WatchExpression {
    private WatchExpression() { }

    /** The part of the expression the cursor is completing. */
    public enum Slot { KEYWORD, OBJECTIVE, HOLDER, ENTITY_PATH, ENTITY_UUID, STORAGE_ID, STORAGE_PATH }

    /** Finds where an NBT path ends; Minecraft's parser is supplied by the client. */
    @FunctionalInterface
    public interface PathScanner {
        /** Index just past the leading path in {@code text}, or -1 when no complete path starts there. */
        int end(String text);
    }

    public static final String SCORE = "score", ENTITY = "entity", STORAGE = "storage";

    /**
     * Longest formatted expression: the longest keyword, a full objective and a full holder whose every character
     * needs an escape inside quotes. The text field must hold this so any saved watch can be edited unchanged.
     */
    public static final int MAX_LENGTH = WatchSpec.MAX_INPUT_LENGTH * 3 + 16;

    /**
     * @param slot   what the final token is
     * @param prefix the text of that token as typed
     * @param start  index in the input where the token starts, so a completion can replace it
     * @param spec   the saved definition when the whole text is valid, otherwise null
     * @param error  a {@code codon.watch.error.*} suffix explaining why {@code spec} is null; empty while the
     *               text is merely unfinished
     * @param id     the storage ID or objective already typed, for completion queries
     */
    public record Result(Slot slot, String prefix, int start, @Nullable WatchSpec spec, String error,
                         String id, @Nullable String path) {
        public boolean valid() { return spec != null; }
    }

    public static Result parse(String text, PathScanner paths) {
        int start = skipSpace(text, 0);
        int end = wordEnd(text, start);
        String keyword = text.substring(start, end);
        if (end == text.length()) {
            return new Result(Slot.KEYWORD, keyword, start, null, keyword.isEmpty() ? "" : "keyword", "", null);
        }
        int rest = skipSpace(text, end);
        return switch (keyword) {
            case SCORE -> score(text, rest);
            case ENTITY -> path(text, rest, WatchSpec.Kind.ENTITY_NBT, "", Slot.ENTITY_PATH, Slot.ENTITY_UUID, paths);
            case STORAGE -> storage(text, rest, paths);
            default -> new Result(Slot.KEYWORD, keyword, start, null, "keyword", "", null);
        };
    }

    private static Result score(String text, int from) {
        int end = wordEnd(text, from);
        String objective = text.substring(from, end);
        if (end == text.length()) {
            return new Result(Slot.OBJECTIVE, objective, from, build(WatchSpec.Kind.SCORE, objective, "", null, null),
                objective.isEmpty() ? "objective" : "", objective, null);
        }
        int holderStart = skipSpace(text, end);
        String holder = text.substring(holderStart).stripTrailing();
        if (holder.isEmpty()) {
            return new Result(Slot.HOLDER, "", holderStart, build(WatchSpec.Kind.SCORE, objective, "", null, null),
                "", objective, null);
        }
        UUID uuid = null;
        String literal = null;
        if (holder.startsWith("\"")) {
            int close = closingQuote(holder);
            if (close < 0) return failed(Slot.HOLDER, holder, holderStart, "", objective);
            if (close != holder.length() - 1) return failed(Slot.HOLDER, holder, holderStart, "score_holder", objective);
            literal = holder.substring(1, close).replaceAll("\\\\(.)", "$1");
        } else if (holder.chars().anyMatch(Character::isWhitespace)) {
            return failed(Slot.HOLDER, holder, holderStart, "score_holder", objective);
        } else {
            uuid = uuid(holder);
            if (uuid == null) literal = holder;
        }
        WatchSpec spec = build(WatchSpec.Kind.SCORE, objective, "", uuid, literal);
        return new Result(Slot.HOLDER, holder, holderStart, spec, spec == null ? "score_holder" : "", objective, null);
    }

    private static Result storage(String text, int from, PathScanner paths) {
        int end = wordEnd(text, from);
        String id = text.substring(from, end);
        if (end == text.length()) {
            return new Result(Slot.STORAGE_ID, id, from, null, id.isEmpty() ? "storage" : "", id, null);
        }
        return path(text, skipSpace(text, end), WatchSpec.Kind.STORAGE_NBT, id, Slot.STORAGE_PATH, null, paths);
    }

    private static Result path(String text, int from, WatchSpec.Kind kind, String target, Slot pathSlot,
                               @Nullable Slot tailSlot, PathScanner paths) {
        String rest = text.substring(from);
        int pathEnd = rest.isEmpty() ? -1 : paths.end(rest);
        if (pathEnd <= 0) {
            // The path is still being typed: offer completions rather than an error.
            return new Result(pathSlot, rest, from, null, rest.isBlank() ? "path_required" : "path", target, rest);
        }
        String path = rest.substring(0, pathEnd);
        String tail = rest.substring(pathEnd);
        if (tail.isEmpty()) {
            WatchSpec spec = build(kind, target, path, null, null);
            return new Result(pathSlot, path, from, spec, spec == null ? invalid(kind, target) : "", target, path);
        }
        if (tailSlot == null || !Character.isWhitespace(tail.charAt(0))) {
            return new Result(pathSlot, rest, from, null, "path", target, path);
        }
        int uuidStart = from + pathEnd + (tail.length() - tail.stripLeading().length());
        String entity = tail.strip();
        if (entity.isEmpty()) {
            return new Result(tailSlot, "", uuidStart, build(kind, target, path, null, null), "", target, path);
        }
        UUID uuid = uuid(entity);
        WatchSpec spec = uuid == null ? null : build(kind, target, path, uuid, null);
        return new Result(tailSlot, entity, uuidStart, spec, spec == null ? "entity" : "", target, path);
    }

    /** Index of the unescaped quote that closes {@code quoted}, or -1 while it is still open. */
    private static int closingQuote(String quoted) {
        for (int i = 1; i < quoted.length(); i++) {
            char c = quoted.charAt(i);
            if (c == '\\') i++;
            else if (c == '"') return i;
        }
        return -1;
    }

    /** Why a syntactically complete watch was still rejected by {@link WatchSpec}. */
    private static String invalid(WatchSpec.Kind kind, String target) {
        return kind == WatchSpec.Kind.STORAGE_NBT && !target.matches("([a-z0-9_.-]+:)?[a-z0-9_./-]+")
            ? "storage_id" : "characters";
    }

    private static Result failed(Slot slot, String prefix, int start, String error, String id) {
        return new Result(slot, prefix, start, null, error, id, null);
    }

    private static @Nullable WatchSpec build(WatchSpec.Kind kind, String target, String path,
                                             @Nullable UUID executor, @Nullable String holder) {
        try {
            return new WatchSpec(kind, target, path, executor, holder);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static @Nullable UUID uuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equalsIgnoreCase(value) ? id : null;
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
    }

    private static int skipSpace(String text, int from) {
        while (from < text.length() && Character.isWhitespace(text.charAt(from))) from++;
        return from;
    }

    private static int wordEnd(String text, int from) {
        while (from < text.length() && !Character.isWhitespace(text.charAt(from))) from++;
        return from;
    }

    /** The expression that parses back to {@code spec}; used when editing an existing watch. */
    public static String format(WatchSpec spec) {
        return switch (spec.kind()) {
            case SCORE -> SCORE + " " + spec.target() + (spec.scoreHolder() != null ? " " + holder(spec.scoreHolder())
                : spec.executor() != null ? " " + spec.executor() : "");
            case ENTITY_NBT -> ENTITY + " " + spec.path() + (spec.executor() != null ? " " + spec.executor() : "");
            case STORAGE_NBT -> STORAGE + " " + spec.target() + " " + spec.path();
        };
    }

    /** Quoting is required whenever the bare form would be read as a UUID, or would not stay one token. */
    public static String holder(String name) {
        if (!name.isEmpty() && name.matches("[A-Za-z0-9_.+#$-]+") && uuid(name) == null) return name;
        return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
