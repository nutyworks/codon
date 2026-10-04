package works.nuty.codon.core.model;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** One bounded page of immediate children; values are previews, never comparison baselines. */
public record NbtPage(WatchResult.Status status, List<Node> children, int offset, int totalChildren) {
    public static final int PAGE_SIZE = 32;
    public static final int MAX_PATH_LENGTH = 512;
    public static final int MAX_NAME_LENGTH = 128;
    public static final int MAX_PREVIEW_LENGTH = 128;

    public NbtPage {
        Objects.requireNonNull(status);
        children = List.copyOf(children);
        if (children.size() > PAGE_SIZE || offset < 0 || totalChildren < 0
            || offset > totalChildren || children.size() > totalChildren - offset)
            throw new IllegalArgumentException("Invalid NBT page bounds");
        if (status != WatchResult.Status.VALUE && (!children.isEmpty() || offset != 0 || totalChildren != 0))
            throw new IllegalArgumentException("Error pages have no children");
    }

    public static NbtPage absent(WatchResult.Status status) { return new NbtPage(status, List.of(), 0, 0); }
    public boolean hasMore() { return offset + children.size() < totalChildren; }

    /**
     * Validates one immediate path step, independently of the shortened display name. The
     * returned key distinguishes collection indices from compound names and decodes quoting
     * solely for duplicate detection; the original navigable path is never rewritten.
     */
    public static @Nullable String childIdentity(String parent, String child) {
        if (child.isEmpty() || child.chars().anyMatch(c -> c < 32 || c == 127 || c == 167)) return null;
        String step;
        if (parent.isEmpty()) step = child;
        else if (child.startsWith(parent + ".")) {
            step = child.substring(parent.length() + 1);
            if (step.startsWith("[")) return null;
        }
        else if (child.startsWith(parent + "[")) step = child.substring(parent.length());
        else return null;
        if (step.isEmpty()) return null;
        if (step.charAt(0) == '[') {
            if (step.length() < 3 || step.charAt(step.length() - 1) != ']') return null;
            String index = step.substring(1, step.length() - 1);
            if (index.chars().anyMatch(c -> c < '0' || c > '9') || index.length() > 1 && index.charAt(0) == '0') return null;
            try { if (Integer.parseInt(index) < 0) return null; }
            catch (NumberFormatException invalid) { return null; }
            return "index:" + index;
        }
        char quote = step.charAt(0);
        if (quote == '"' || quote == '\'') {
            StringBuilder key = new StringBuilder();
            for (int i = 1; i < step.length(); i++) {
                char c = step.charAt(i);
                if (c == quote) return i == step.length() - 1 ? "key:" + key : null;
                if (c == '\\') {
                    if (++i >= step.length()) return null;
                    c = step.charAt(i);
                    if (c != quote && c != '\\') return null;
                }
                key.append(c);
            }
            return null;
        }
        // The tree reader quotes names; accept the equivalent ordinary unquoted path form too.
        if (step.chars().anyMatch(c -> Character.isWhitespace(c) || c == '.' || c == '[' || c == ']'
            || c == '{' || c == '}' || c == '"' || c == '\'' || c == '\\')) return null;
        return "key:" + step;
    }

    public record Node(String name, String path, String preview, boolean expandable) {
        public Node {
            Objects.requireNonNull(name);
            Objects.requireNonNull(path);
            Objects.requireNonNull(preview);
            if (name.length() > MAX_NAME_LENGTH || path.length() > MAX_PATH_LENGTH || preview.length() > MAX_PREVIEW_LENGTH)
                throw new IllegalArgumentException("NBT node text exceeds its wire bound");
            if (path.isEmpty() && expandable) throw new IllegalArgumentException("An inaccessible path cannot expand");
        }

        public boolean pinnable() {
            if (path.isEmpty()) return false;
            try { new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", path); return true; }
            catch (IllegalArgumentException invalid) { return false; }
        }
    }
}
