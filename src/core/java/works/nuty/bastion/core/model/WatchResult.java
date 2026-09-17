package works.nuty.bastion.core.model;

import java.util.Objects;

/** A bounded server observation; missing data is never represented by an invented value. */
public record WatchResult(Status status, String value, String targetKey, String targetName) {
    public static final int MAX_VALUE_LENGTH = 2048;
    public static final int MAX_TARGET_LENGTH = 256;
    public static final int MAX_TARGET_NAME_LENGTH = 128;
    public WatchResult(Status status, String value, String targetKey) { this(status, value, targetKey, ""); }

    public enum Status {
        VALUE, NO_EXECUTOR, TARGET_MISSING, OBJECTIVE_MISSING, VALUE_MISSING,
        INVALID_PATH, TOO_LARGE, ERROR, UNAVAILABLE
    }

    public WatchResult {
        Objects.requireNonNull(status);
        Objects.requireNonNull(value);
        Objects.requireNonNull(targetKey);
        Objects.requireNonNull(targetName);
        if (value.length() > MAX_VALUE_LENGTH || targetKey.length() > MAX_TARGET_LENGTH || targetName.length() > MAX_TARGET_NAME_LENGTH) {
            throw new IllegalArgumentException("Watch result exceeds wire limits");
        }
    }

    public static WatchResult absent(Status status, String targetKey) {
        return new WatchResult(status, "", targetKey);
    }
    public WatchResult withTargetName(String name) { return new WatchResult(status, value, targetKey, name); }
}
