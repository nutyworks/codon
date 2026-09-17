package works.nuty.bastion.persistence;

import com.google.gson.JsonObject;
import works.nuty.bastion.core.model.WatchSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

public final class WorldWatchPersistence {
    private static final int VERSION = 2;
    private final Consumer<Exception> onError;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Map<UUID, WatchDefinitionTransfer> stagedTransfers = new HashMap<>();
    private Path worldDir;
    private @Nullable UUID currentSingleplayerOwner;
    private @Nullable UUID previousSingleplayerOwner;

    public WorldWatchPersistence(Consumer<Exception> onError) { this.onError = onError; }

    public void openWorld(Path worldDir) {
        openWorld(worldDir, null, null);
    }

    public void openWorld(Path worldDir, @Nullable UUID currentSingleplayerOwner,
                          @Nullable UUID previousSingleplayerOwner) {
        closeWorld();
        this.worldDir = worldDir.toAbsolutePath();
        this.currentSingleplayerOwner = currentSingleplayerOwner;
        this.previousSingleplayerOwner = previousSingleplayerOwner;
    }

    public void closeWorld() {
        flush();
        entries.clear();
        stagedTransfers.clear();
        worldDir = null;
        currentSingleplayerOwner = null;
        previousSingleplayerOwner = null;
    }

    public void flush() {
        if (worldDir == null) return;
        for (Map.Entry<UUID, Entry> item : entries.entrySet()) {
            Entry entry = item.getValue();
            if (!entry.dirty || entry.disabled) continue;
            try { write(item.getKey(), entry.specs); entry.dirty = false; }
            catch (Exception e) { report(e); }
        }
    }

    public List<WatchSpec> get(UUID player) {
        Entry entry = entries.computeIfAbsent(player, ignored -> load(player));
        return entry.specs;
    }

    /** Keep session edits available, but report whether they actually reached the world save. */
    public boolean save(UUID player, List<WatchSpec> specs) {
        List<WatchSpec> checked = WatchDefinitions.decode(WatchDefinitions.encode(specs));
        Entry entry = entries.computeIfAbsent(player, ignored -> load(player));
        entry.specs = checked;
        entry.dirty = true;
        flush();
        return worldDir != null && !entry.disabled && !entry.dirty;
    }

    /**
     * Accepts one client command page. The saved definitions change only when the final page is
     * valid and complete; intermediate pages only update the authenticated player's staging area.
     */
    public ChunkSaveResult saveChunk(UUID player, long transferId, int offset, boolean last, List<WatchSpec> specs) {
        WatchDefinitionTransfer transfer = stagedTransfers.computeIfAbsent(player, ignored -> new WatchDefinitionTransfer());
        var complete = transfer.accept(transferId, offset, last, specs);
        if (complete.isPresent()) {
            stagedTransfers.remove(player);
            return save(player, complete.get()) ? ChunkSaveResult.ACCEPTED : ChunkSaveResult.SAVE_FAILED;
        }
        if (!transfer.isActive()) stagedTransfers.remove(player);
        return transfer.isActive() ? ChunkSaveResult.ACCEPTED : ChunkSaveResult.INVALID;
    }

    /** Removes a disconnected player's incomplete upload without touching their saved definitions. */
    public void resetTransfer(UUID player) { stagedTransfers.remove(player); }

    public enum ChunkSaveResult { ACCEPTED, INVALID, SAVE_FAILED }

    private Entry load(UUID player) {
        if (worldDir == null) return new Entry(List.of(), false, false);
        boolean canonical = isCanonicalOwner(player);
        try {
            var document = fileFor(player).read();
            if (document.isPresent()) return new Entry(decode(document.get()), false, false);
            if (!canonical) return new Entry(List.of(), false, false);

            var legacy = file(player).read();
            if (legacy.isEmpty() && previousSingleplayerOwner != null && !previousSingleplayerOwner.equals(player)) {
                legacy = file(previousSingleplayerOwner).read();
            }
            if (legacy.isEmpty()) return new Entry(List.of(), false, false);
            List<WatchSpec> specs = decode(legacy.get());
            Entry migrated = new Entry(specs, true, false);
            try { writeCanonical(specs); migrated.dirty = false; }
            catch (Exception e) { report(e); }
            return migrated;
        } catch (Exception e) {
            report(e);
            return new Entry(List.of(), false, true);
        }
    }

    private List<WatchSpec> decode(JsonObject object) throws IOException {
        if (!object.has("version") || !object.get("version").isJsonPrimitive()
            || !object.getAsJsonPrimitive("version").isNumber() || !object.has("watches"))
            throw new IOException("Unsupported watch file version");
        int version = object.getAsJsonPrimitive("version").getAsBigDecimal().intValueExact();
        if (version != 1 && version != VERSION) throw new IOException("Unsupported watch file version");
        return WatchDefinitions.decode(object.get("watches"));
    }

    private void write(UUID player, List<WatchSpec> specs) throws IOException {
        if (isCanonicalOwner(player)) writeCanonical(specs);
        else writeFile(file(player), specs);
    }

    private void writeCanonical(List<WatchSpec> specs) throws IOException {
        writeFile(fileForCanonical(), specs);
    }

    private void writeFile(JsonFile file, List<WatchSpec> specs) throws IOException {
        JsonObject object = new JsonObject();
        object.addProperty("version", VERSION);
        object.add("watches", WatchDefinitions.encode(specs));
        file.write(object);
    }

    private boolean isCanonicalOwner(UUID player) { return currentSingleplayerOwner != null && currentSingleplayerOwner.equals(player); }
    private JsonFile fileFor(UUID player) { return isCanonicalOwner(player) ? fileForCanonical() : file(player); }
    private JsonFile fileForCanonical() { return new JsonFile(worldDir.resolve("data/bastion-watches/singleplayer.json")); }
    private JsonFile file(UUID player) { return new JsonFile(worldDir.resolve("data/bastion-watches").resolve(player + ".json")); }
    private void report(Exception e) { if (onError != null) onError.accept(e); }
    private static final class Entry {
        private List<WatchSpec> specs;
        private boolean dirty;
        private final boolean disabled;
        private Entry(List<WatchSpec> specs, boolean dirty, boolean disabled) { this.specs = specs; this.dirty = dirty; this.disabled = disabled; }
    }
}
