package works.nuty.codon.persistence;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.BreakpointCondition;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.FunctionId;
import works.nuty.codon.core.model.FunctionLocation;
import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.port.DebuggerEventSink;
import works.nuty.codon.core.service.BreakpointRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Server-thread adapter: saves both breakpoint kinds and forwards all presentation events. */
public final class WorldBreakpointPersistence implements DebuggerEventSink {
    public static final String FILE_NAME = "codon-breakpoints.json";
    private final BreakpointRegistry registry;
    private final DebuggerEventSink downstream;
    private final Consumer<Exception> onError;
    private @Nullable JsonFile file;
    private boolean dirty;

    public WorldBreakpointPersistence(BreakpointRegistry registry, DebuggerEventSink downstream,
                                      Consumer<Exception> onError) {
        this.registry = registry;
        this.downstream = downstream;
        this.onError = onError;
    }

    /** Restore before the first server tick or player join; never inherit another world's set. */
    public void openWorld(Path worldDirectory) {
        closeWorld();
        Path path = worldDirectory.resolve("data").resolve(FILE_NAME);
        JsonFile candidate = new JsonFile(path);
        try {
            var saved = candidate.read();
            if (saved.isPresent()) restore(saved.get());
            file = candidate;
        } catch (IOException | IllegalArgumentException | IllegalStateException invalid) {
            // Preserve unreadable/unsupported data, including on subsequent edits and shutdown.
            onError.accept(new IOException("Cannot load Codon breakpoints from " + path
                + "; persistence is disabled for this world session", invalid));
        }
    }

    public void closeWorld() {
        flush();
        file = null;
        dirty = false;
        registry.clear();
    }

    /** Retry a failed write on world saves/shutdown without doing disk I/O on every tick. */
    public void flush() {
        if (file == null || !dirty) return;
        try {
            file.write(snapshot());
            dirty = false;
        } catch (IOException failure) {
            onError.accept(failure);
        }
    }

    @Override
    public void breakpointsChanged(Set<BlockLocation> blockBreakpoints) {
        dirty = true;
        flush();
        downstream.breakpointsChanged(blockBreakpoints);
    }

    @Override
    public void paused(PauseSnapshot snapshot) { downstream.paused(snapshot); }

    @Override
    public void resumed() { downstream.resumed(); }

    @Override
    public void continued() { downstream.continued(); }

    @Override
    public void stepping() { downstream.stepping(); }

    @Override
    public void executionFlowsCompleted(List<ExecutionFlowTrace> flows) {
        downstream.executionFlowsCompleted(flows);
    }

    private void restore(JsonObject document) {
        int version = integer(document, "version");
        if (version == 2 || version == 3) {
            JsonArray entries = array(document, "breakpoints");
            if (entries.size() > BreakpointRegistry.MAX_DEFINITIONS)
                throw new IllegalArgumentException("Too many breakpoint definitions");
            Map<BreakpointTarget, BreakpointDefinition> loaded = new HashMap<>();
            for (JsonElement element : entries) {
                BreakpointDefinition definition = readDefinition(element.getAsJsonObject(), version);
                BreakpointDefinition previous = loaded.putIfAbsent(definition.target(), definition);
                if (previous != null && !previous.equals(definition))
                    throw new IllegalArgumentException("Conflicting duplicate breakpoint");
            }
            loaded.values().forEach(registry::put);
            return;
        }
        if (version != 1) throw new IllegalArgumentException("Unsupported breakpoint version");
        JsonArray blockEntries = array(document, "blocks");
        JsonArray functionEntries = array(document, "functions");
        if ((long) blockEntries.size() + functionEntries.size() > BreakpointRegistry.MAX_DEFINITIONS)
            throw new IllegalArgumentException("Too many breakpoint definitions");
        Set<BlockLocation> blocks = new HashSet<>();
        Set<FunctionLocation> functions = new HashSet<>();
        for (JsonElement entry : blockEntries) {
            JsonObject block = entry.getAsJsonObject();
            String dimension = identifier(string(block, "dimension"));
            blocks.add(new BlockLocation(integer(block, "x"), integer(block, "y"), integer(block, "z"), dimension));
        }
        for (JsonElement entry : functionEntries) {
            JsonObject function = entry.getAsJsonObject();
            String id = identifier(string(function, "function"));
            int separator = id.indexOf(':');
            int line = integer(function, "line");
            if (line < 1) throw new IllegalArgumentException("Function breakpoint lines start at 1");
            functions.add(new FunctionLocation(new FunctionId(id.substring(0, separator), id.substring(separator + 1)), line));
        }
        // Validate the entire document first, so a bad entry never leaves a partially loaded set.
        blocks.forEach(registry::toggleBlock);
        functions.forEach(registry::toggleFunction);
    }

    private JsonObject snapshot() {
        JsonObject document = new JsonObject();
        document.addProperty("version", 3);
        JsonArray definitions = new JsonArray();
        registry.definitions().stream().sorted(Comparator.comparing(definition -> definition.target().toString()))
            .forEach(definition -> definitions.add(writeDefinition(definition)));
        document.add("breakpoints", definitions);
        return document;
    }

    private static JsonObject writeDefinition(BreakpointDefinition definition) {
        JsonObject result = new JsonObject();
        BreakpointTarget target = definition.target();
        switch (target.location()) {
            case SourceLocation.Block block -> {
                result.addProperty("type", "block");
                result.addProperty("dimension", block.block().dimension());
                result.addProperty("x", block.block().x());
                result.addProperty("y", block.block().y());
                result.addProperty("z", block.block().z());
            }
            case SourceLocation.Function function -> {
                result.addProperty("type", "function");
                result.addProperty("function", function.location().function().toString());
                result.addProperty("line", function.location().line());
            }
            case SourceLocation.Player ignored -> throw new IllegalArgumentException("Player breakpoint cannot persist");
        }
        result.addProperty("stage", target.stageIndex());
        result.addProperty("fingerprint", target.commandFingerprint());
        result.addProperty("enabled", definition.enabled());
        result.addProperty("staleSource", definition.staleSource());
        result.addProperty("condition", definition.condition().kind().name());
        result.addProperty("comparison", definition.condition().comparison().name());
        result.addProperty("threshold", definition.condition().threshold());
        return result;
    }

    private static BreakpointDefinition readDefinition(JsonObject saved, int version) {
        SourceLocation location = switch (string(saved, "type")) {
            case "block" -> new SourceLocation.Block(new BlockLocation(integer(saved, "x"), integer(saved, "y"),
                integer(saved, "z"), identifier(string(saved, "dimension"))));
            case "function" -> {
                String id = identifier(string(saved, "function"));
                int line = integer(saved, "line");
                if (line < 1) throw new IllegalArgumentException("Function breakpoint lines start at 1");
                int separator = id.indexOf(':');
                yield new SourceLocation.Function(new FunctionLocation(
                    new FunctionId(id.substring(0, separator), id.substring(separator + 1)), line));
            }
            default -> throw new IllegalArgumentException("Unknown breakpoint type");
        };
        BreakpointTarget target = new BreakpointTarget(location, integer(saved, "stage"), string(saved, "fingerprint"));
        BreakpointCondition condition = new BreakpointCondition(
            BreakpointCondition.Kind.valueOf(string(saved, "condition")),
            BreakpointCondition.Comparison.valueOf(string(saved, "comparison")), integer(saved, "threshold"));
        return new BreakpointDefinition(target, bool(saved, "enabled"), condition,
            version >= 3 && bool(saved, "staleSource"));
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonArray()) throw new IllegalArgumentException("Expected array: " + key);
        return value.getAsJsonArray();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Expected string: " + key);
        }
        return value.getAsString();
    }

    private static int integer(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Expected integer: " + key);
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Expected integer: " + key, invalid);
        }
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Expected boolean: " + key);
        }
        return value.getAsBoolean();
    }

    private static String identifier(String value) {
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Invalid identifier: " + value);
        }
        return value;
    }
}
