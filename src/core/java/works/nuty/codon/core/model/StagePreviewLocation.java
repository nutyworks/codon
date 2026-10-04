package works.nuty.codon.core.model;

import org.jspecify.annotations.Nullable;

/** Whether an observed source can be represented by the optional saved-stage preview protocol. */
public final class StagePreviewLocation {
    public static final int MAX_DIMENSION_LENGTH = 128;
    public static final int MAX_NAMESPACE_LENGTH = 64;
    public static final int MAX_PATH_LENGTH = 256;

    private StagePreviewLocation() { }

    public static boolean supported(@Nullable SourceLocation location) {
        return switch (location) {
            case SourceLocation.Block block -> block.block() != null
                && bounded(block.block().dimension(), MAX_DIMENSION_LENGTH);
            case SourceLocation.Function function -> function.location() != null
                && function.location().function() != null && function.location().line() >= 1
                && bounded(function.location().function().namespace(), MAX_NAMESPACE_LENGTH)
                && bounded(function.location().function().path(), MAX_PATH_LENGTH);
            case null, default -> false;
        };
    }

    private static boolean bounded(@Nullable String value, int maximum) {
        return value != null && value.length() <= maximum && value.chars().noneMatch(c -> c < 32 || c == 127);
    }
}
