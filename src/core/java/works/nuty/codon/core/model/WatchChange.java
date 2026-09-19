package works.nuty.codon.core.model;

import java.util.Objects;

/** A server-observed change between consecutive stops, independent of saved watch definitions. */
public record WatchChange(WatchSpec spec, WatchResult before, WatchResult after) {
    public WatchChange {
        Objects.requireNonNull(spec);
        Objects.requireNonNull(before);
        Objects.requireNonNull(after);
        if (!before.targetKey().equals(after.targetKey())) {
            throw new IllegalArgumentException("A watch change must describe the same target");
        }
    }
}
