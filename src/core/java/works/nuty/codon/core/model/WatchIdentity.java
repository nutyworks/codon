package works.nuty.codon.core.model;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Semantic identity for persisted watches.  The expression itself remains untouched: this only
 * removes optional quotes from an otherwise simple NBT path so equivalent UI affordances do not
 * create duplicate watches.
 */
public final class WatchIdentity {
    private WatchIdentity() {}

    /** Total ordering lets hash-map tree bins stay logarithmic even for chosen string hashes. */
    public record Key(WatchSpec.Kind kind, String target, String path, @Nullable UUID executor,
                      @Nullable String scoreHolder) implements Comparable<Key> {
        private static final java.util.Comparator<Key> ORDER = java.util.Comparator.comparing(Key::kind)
            .thenComparing(Key::target).thenComparing(Key::path)
            .thenComparing(Key::executor, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
            .thenComparing(Key::scoreHolder, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()));

        @Override public int compareTo(Key other) { return ORDER.compare(this, other); }
    }

    /** Exact identity for transport/persistence uniqueness; quoted aliases stay distinct here. */
    public static Key rawKey(WatchSpec spec) {
        return new Key(spec.kind(), spec.target(), spec.path(), spec.executor(), spec.scoreHolder());
    }

    public static Key key(WatchSpec spec) { return key(spec, spec.executor()); }

    public static Key key(WatchSpec spec, @Nullable UUID executor) {
        return new Key(spec.kind(), spec.target(), canonicalPath(spec.path()), executor, spec.scoreHolder());
    }

    public static boolean same(WatchSpec first, WatchSpec second) {
        return first.kind() == second.kind()
            && first.target().equals(second.target())
            && Objects.equals(first.executor(), second.executor())
            && Objects.equals(first.scoreHolder(), second.scoreHolder())
            && canonicalPath(first.path()).equals(canonicalPath(second.path()));
    }

    /** Same watched field, regardless of whether it follows selection or is bound to an entity. */
    public static boolean sameField(WatchSpec first, WatchSpec second) {
        return first.kind() == second.kind()
            && first.target().equals(second.target())
            && Objects.equals(first.scoreHolder(), second.scoreHolder())
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
