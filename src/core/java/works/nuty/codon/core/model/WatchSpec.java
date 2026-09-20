package works.nuty.codon.core.model;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A read-only query, optionally bound to an executor UUID or score-holder name. */
public record WatchSpec(Kind kind, String target, String path, @Nullable UUID executor,
                        @Nullable String scoreHolder) {
    public static final int MAX_INPUT_LENGTH = 128;

    public enum Kind { SCORE, ENTITY_NBT, STORAGE_NBT }

    public WatchSpec(Kind kind, String target, String path) { this(kind, target, path, null, null); }
    public WatchSpec(Kind kind, String target, String path, @Nullable UUID executor) {
        this(kind, target, path, executor, null);
    }

    public static WatchSpec scoreHolder(String objective, String name) {
        return new WatchSpec(Kind.SCORE, objective, "", null, name);
    }

    public boolean isPinned() { return executor != null || scoreHolder != null; }
    public WatchSpec withExecutor(@Nullable UUID executor) {
        return new WatchSpec(kind, target, path, executor, null);
    }

    public WatchSpec {
        Objects.requireNonNull(kind);
        target = Objects.requireNonNull(target).trim();
        path = Objects.requireNonNull(path).trim();
        if (scoreHolder != null) {
            // Scoreboard keys are literal: whitespace inside a quoted name is significant.
            if (scoreHolder.isEmpty() || scoreHolder.length() > MAX_INPUT_LENGTH
                || scoreHolder.chars().anyMatch(c -> c < 32 || c == 127 || c == 167)) {
                throw new IllegalArgumentException("Invalid score holder");
            }
            if (kind != Kind.SCORE || executor != null) {
                throw new IllegalArgumentException("Score holder requires an unbound score watch");
            }
        }
        if (kind == Kind.STORAGE_NBT) {
            if (executor != null) throw new IllegalArgumentException("Storage has no executor");
            if (!target.matches("([a-z0-9_.-]+:)?[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("Invalid storage ID");
            }
            if (!target.contains(":")) target = "minecraft:" + target;
        }
        if (target.length() > MAX_INPUT_LENGTH || path.length() > MAX_INPUT_LENGTH
            || target.chars().anyMatch(c -> c < 32 || c == 127 || c == 167)
            || path.chars().anyMatch(c -> c < 32 || c == 127 || c == 167)) {
            throw new IllegalArgumentException("Watch input is too long or contains control characters");
        }
        if (kind == Kind.SCORE ? target.isEmpty() || !path.isEmpty()
            : path.isEmpty() || (kind == Kind.STORAGE_NBT ? target.isEmpty() : !target.isEmpty())) {
            throw new IllegalArgumentException("Watch target/path does not match its kind");
        }
    }
}
