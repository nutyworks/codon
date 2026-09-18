package works.nuty.codon.core.model;

import java.util.List;
import java.util.Objects;

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
