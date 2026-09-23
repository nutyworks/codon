package works.nuty.codon.core.model;

import java.util.List;
import java.util.Objects;

/**
 * A bounded, read-only view of the effective source for one loaded datapack function.
 *
 * <p>{@code provider} is the resource pack's public id when Minecraft exposes one, or empty
 * when it is unavailable. {@code revision} is the lowercase SHA-256 digest of the complete raw
 * resource, including text that did not fit in {@code lines}.
 */
public record FunctionSourceDocument(
    FunctionId id,
    String provider,
    String revision,
    List<String> lines,
    boolean truncated
) {
    public FunctionSourceDocument {
        id = Objects.requireNonNull(id, "id");
        provider = Objects.requireNonNull(provider, "provider");
        revision = Objects.requireNonNull(revision, "revision");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
    }
}
