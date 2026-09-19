package works.nuty.codon.core.model;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A bounded, read-only request used to populate the Watch editor. */
public record WatchEditorQuery(Mode mode, WatchSpec.Kind kind, String target, String path,
                               @Nullable UUID executor, String search, int offset) {
    public static final int MAX_TARGET_LENGTH = 128;
    public static final int MAX_PATH_LENGTH = 512;
    public static final int MAX_SEARCH_LENGTH = 128;

    public enum Mode { OBJECTIVES, ENTITIES, STORAGES, NBT, PREVIEW }

    public WatchEditorQuery {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(kind);
        target = bounded(target, MAX_TARGET_LENGTH, "target");
        path = bounded(path, MAX_PATH_LENGTH, "path");
        search = bounded(search, MAX_SEARCH_LENGTH, "search");
        if (offset < 0) throw new IllegalArgumentException("negative offset");
    }

    private static String bounded(String value, int max, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (value.length() > max || value.chars().anyMatch(c -> c < 32 || c == 127 || c == 167))
            throw new IllegalArgumentException("invalid editor " + field);
        return value;
    }
}
