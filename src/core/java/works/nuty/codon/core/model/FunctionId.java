package works.nuty.codon.core.model;

/**
 * Identifies a datapack function by namespace and path (e.g. {@code mypack:tick}).
 * The Minecraft-free analogue of {@code net.minecraft.resources.Identifier} for functions.
 */
public record FunctionId(String namespace, String path) {
    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
