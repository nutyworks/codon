package works.nuty.codon.core.model;

import java.util.Objects;

/**
 * Semantic identity for persisted watches.  The expression itself remains untouched: this only
 * removes optional quotes from an otherwise simple NBT path so equivalent UI affordances do not
 * create duplicate watches.
 */
public final class WatchIdentity {
    private WatchIdentity() {}

    public static boolean same(WatchSpec first, WatchSpec second) {
        return first.kind() == second.kind()
            && first.target().equals(second.target())
            && Objects.equals(first.executor(), second.executor())
            && canonicalPath(first.path()).equals(canonicalPath(second.path()));
    }

    /** Same watched field, regardless of whether it follows selection or is bound to an entity. */
    public static boolean sameField(WatchSpec first, WatchSpec second) {
        return first.kind() == second.kind()
            && first.target().equals(second.target())
            && canonicalPath(first.path()).equals(canonicalPath(second.path()));
    }

    /**
     * Canonicalizes only a complete, simple dotted/indexed path.  Filter syntax and quoted
     * literals are deliberately left verbatim: guessing their NBT-path meaning could merge
     * different watches.
     */
    public static String canonicalPath(String path) {
        String value = Objects.requireNonNull(path).trim();
        StringBuilder result = new StringBuilder(value.length());
        int index = 0;
        boolean needsComponent = true;
        while (index < value.length()) {
            if (!needsComponent && value.charAt(index) == '.') {
                result.append('.');
                index++;
                needsComponent = true;
                continue;
            }
            if (value.charAt(index) == '[') {
                int close = value.indexOf(']', index + 1);
                if (close < 0 || close == index + 1) return value;
                for (int i = index + 1; i < close; i++) {
                    if (!Character.isDigit(value.charAt(i))) return value;
                }
                result.append(value, index, close + 1);
                index = close + 1;
                needsComponent = false;
                continue;
            }
            int start = index;
            if (value.charAt(index) == '"') {
                index++;
                int keyStart = index;
                while (index < value.length() && simpleKeyChar(value.charAt(index))) index++;
                if (index == keyStart || index >= value.length() || value.charAt(index) != '"') return value;
                result.append(value, keyStart, index);
                index++;
            } else {
                while (index < value.length() && simpleKeyChar(value.charAt(index))) index++;
                if (index == start) return value;
                result.append(value, start, index);
            }
            needsComponent = false;
            if (index < value.length() && value.charAt(index) != '.' && value.charAt(index) != '[') return value;
        }
        return needsComponent ? value : result.toString();
    }

    private static boolean simpleKeyChar(char character) {
        return character >= 'a' && character <= 'z'
            || character >= 'A' && character <= 'Z'
            || character >= '0' && character <= '9'
            || character == '_' || character == '+' || character == '-';
    }
}
