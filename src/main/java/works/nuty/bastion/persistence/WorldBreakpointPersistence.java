package works.nuty.bastion.persistence;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.FunctionId;
import works.nuty.bastion.core.model.FunctionLocation;
import works.nuty.bastion.core.model.ExecutionFlowTrace;
import works.nuty.bastion.core.model.PauseSnapshot;
import works.nuty.bastion.core.port.DebuggerEventSink;
import works.nuty.bastion.core.service.BreakpointRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Server-thread adapter: saves both breakpoint kinds and forwards all presentation events. */
public final class WorldBreakpointPersistence implements DebuggerEventSink {
    public static final String FILE_NAME = "bastion-breakpoints.json";
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
            onError.accept(new IOException("Cannot load Bastion breakpoints from " + path
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
        if (integer(document, "version") != 1) throw new IllegalArgumentException("Unsupported breakpoint version");
        Set<BlockLocation> blocks = new HashSet<>();
        Set<FunctionLocation> functions = new HashSet<>();
        for (JsonElement entry : array(document, "blocks")) {
            JsonObject block = entry.getAsJsonObject();
            String dimension = identifier(string(block, "dimension"));
            blocks.add(new BlockLocation(integer(block, "x"), integer(block, "y"), integer(block, "z"), dimension));
        }
        for (JsonElement entry : array(document, "functions")) {
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
        document.addProperty("version", 1);
        JsonArray blocks = new JsonArray();
        registry.blocks().stream().sorted(Comparator.comparing(BlockLocation::dimension)
            .thenComparingInt(BlockLocation::x).thenComparingInt(BlockLocation::y).thenComparingInt(BlockLocation::z))
            .forEach(location -> {
                JsonObject block = new JsonObject();
                block.addProperty("dimension", location.dimension());
                block.addProperty("x", location.x());
                block.addProperty("y", location.y());
                block.addProperty("z", location.z());
                blocks.add(block);
            });
        document.add("blocks", blocks);
        JsonArray functions = new JsonArray();
        registry.functions().stream().sorted(Comparator.comparing((FunctionLocation f) -> f.function().toString())
            .thenComparingInt(FunctionLocation::line)).forEach(location -> {
                JsonObject function = new JsonObject();
                function.addProperty("function", location.function().toString());
                function.addProperty("line", location.line());
                functions.add(function);
            });
        document.add("functions", functions);
        return document;
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

    private static String identifier(String value) {
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Invalid identifier: " + value);
        }
        return value;
    }
}
