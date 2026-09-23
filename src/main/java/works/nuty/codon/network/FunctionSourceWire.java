package works.nuty.codon.network;

import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import works.nuty.codon.core.model.FunctionId;

/** Shared strict bounds for the function-source protocol. */
final class FunctionSourceWire {
    static final int MAX_NAMESPACE_LENGTH = 64;
    static final int MAX_PATH_LENGTH = 256;
    static final int MAX_PROVIDER_LENGTH = 256;
    static final int MAX_REVISION_LENGTH = 64;
    static final int MAX_LINE_LENGTH = 16_384;

    private FunctionSourceWire() { }

    static void writeId(FriendlyByteBuf buf, FunctionId id) {
        validateId(id);
        buf.writeUtf(id.namespace(), MAX_NAMESPACE_LENGTH);
        buf.writeUtf(id.path(), MAX_PATH_LENGTH);
    }

    static FunctionId readId(FriendlyByteBuf buf) {
        FunctionId id = new FunctionId(buf.readUtf(MAX_NAMESPACE_LENGTH), buf.readUtf(MAX_PATH_LENGTH));
        validateId(id);
        return id;
    }

    static String bounded(String value, int maximum, String field) {
        value = Objects.requireNonNull(value, field);
        if (value.length() > maximum || value.chars().anyMatch(c -> c < 32 || c == 127))
            throw new IllegalArgumentException("invalid function source " + field);
        return value;
    }

    static void validateId(FunctionId id) {
        Objects.requireNonNull(id, "function");
        bounded(id.namespace(), MAX_NAMESPACE_LENGTH, "namespace");
        bounded(id.path(), MAX_PATH_LENGTH, "path");
    }
}
