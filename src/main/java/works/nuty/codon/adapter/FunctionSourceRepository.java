package works.nuty.codon.adapter;

import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import works.nuty.codon.core.model.FunctionId;
import works.nuty.codon.core.model.FunctionSourceDocument;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Reads effective datapack function sources from Minecraft's current resource manager.
 *
 * <p>This repository deliberately keeps no cache. Minecraft replaces its resource manager on
 * reload, so the next request reflects the reloaded datapacks without stale source text.
 */
public final class FunctionSourceRepository {
    private static final FileToIdConverter FUNCTION_FILES = new FileToIdConverter("function", ".mcfunction");
    /** Keep source responses comfortably under the custom-payload limit. */
    private static final int MAX_LINES = 20_000;
    private static final int MAX_TOTAL_CHARS = 700_000;
    /** Fits a single source line into one bounded browse packet field. */
    private static final int MAX_LINE_CHARS = 16_384;

    private FunctionSourceRepository() {
    }

    /** Returns the loaded, effective function ids in stable namespace/path order. */
    public static List<FunctionId> list(MinecraftServer server) {
        List<FunctionId> result = new ArrayList<>();
        for (Identifier id : server.getFunctions().getFunctionNames()) {
            result.add(new FunctionId(id.getNamespace(), id.getPath()));
        }
        result.sort(Comparator.comparing(FunctionId::namespace).thenComparing(FunctionId::path));
        return List.copyOf(result);
    }

    /**
     * Reads a loaded function's raw source lines and revision, or returns empty when the id is
     * invalid, no longer loaded, absent from the active resources, or cannot be read.
     */
    public static Optional<FunctionSourceDocument> read(MinecraftServer server, FunctionId id) {
        Identifier functionId;
        try {
            functionId = Identifier.fromNamespaceAndPath(id.namespace(), id.path());
        } catch (RuntimeException invalidId) {
            return Optional.empty();
        }
        if (server.getFunctions().get(functionId).isEmpty()) {
            return Optional.empty();
        }

        Identifier file = FUNCTION_FILES.idToFile(functionId);
        Optional<Resource> resource = server.getResourceManager().getResource(file);
        if (resource.isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.of(readResource(id, resource.get()));
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    private static FunctionSourceDocument readResource(FunctionId id, Resource resource) throws IOException {
        MessageDigest digest = sha256();
        List<String> lines = new ArrayList<>();
        int totalChars = 0;
        boolean truncated = false;

        try (InputStream input = resource.open();
             DigestInputStream digested = new DigestInputStream(input, digest);
             BufferedReader reader = new BufferedReader(new InputStreamReader(digested, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.size() >= MAX_LINES || line.length() > MAX_LINE_CHARS
                    || line.length() > MAX_TOTAL_CHARS - totalChars) {
                    truncated = true;
                    reader.transferTo(java.io.Writer.nullWriter());
                    break;
                }
                lines.add(line);
                totalChars += line.length();
            }
        }

        return new FunctionSourceDocument(
            id,
            safeProvider(resource.sourcePackId()),
            HexFormat.of().formatHex(digest.digest()),
            lines,
            truncated
        );
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    /** Prevent a resource implementation from exposing an absolute local path to clients. */
    private static String safeProvider(String provider) {
        if (provider == null || provider.startsWith("/") || provider.startsWith("\\")
            || provider.matches("(?i)^[a-z]:[\\\\/].*")) {
            return "";
        }
        return provider;
    }
}
