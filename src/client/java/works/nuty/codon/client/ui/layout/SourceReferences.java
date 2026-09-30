package works.nuty.codon.client.ui.layout;

import java.util.List;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.FunctionId;

/** Loaded source links use the same identifier parser as Minecraft commands. */
public final class SourceReferences {
    private SourceReferences() { }

    public static @Nullable FunctionId loadedFunction(String token, List<FunctionId> functions) {
        // Keep tags and invalid tokens outside the existing single-function link path.
        if (!token.matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+")) return null;
        Identifier parsed = Identifier.tryParse(token);
        if (parsed == null) return null;
        FunctionId id = new FunctionId(parsed.getNamespace(), parsed.getPath());
        return functions.contains(id) ? id : null;
    }
}
