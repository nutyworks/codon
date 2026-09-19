package works.nuty.codon.adapter;

import net.minecraft.advancements.predicates.NbtPredicate;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.Objective;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.WatchChange;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Captures the values visible to command execution at consecutive debugger stops.
 * This is deliberately independent of saved watch definitions: it is the automatic
 * "what changed while continuing" view.
 */
public final class PauseWatchChanges {
    private static final String ROOT_PATH = "{}";
    private final Map<UUID, Map<WatchSpec, Observation>> entities = new HashMap<>();
    private final Map<WatchSpec, Observation> storage = new HashMap<>();
    private final Set<String> storageTargets = new HashSet<>();
    private boolean hasBaseline;
    private boolean storageBaseline;

    /** Must be called on the server thread while execution is parked. */
    public List<WatchChange> capture(MinecraftServer server, PauseSnapshot snapshot) {
        if (!server.isSameThread()) throw new IllegalStateException("Pause watch capture requires the server thread");

        Set<UUID> ids = new HashSet<>(entities.keySet());
        for (var source : snapshot.pauseSources()) if (source.entity() != null) ids.add(source.entity().uuid());

        List<WatchChange> changes = new ArrayList<>();
        for (UUID id : ids) captureEntity(server, id, changes);
        captureStorage(server, changes);
        boolean initial = !hasBaseline;
        hasBaseline = true;
        if (initial) return List.of();
        changes.sort(Comparator.comparing((WatchChange change) -> change.spec().kind().ordinal())
            .thenComparing(change -> change.spec().executor(), Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(change -> change.spec().target())
            .thenComparing(change -> change.spec().path()));
        return List.copyOf(changes);
    }

    /** Starts a new execution session; the next capture is an initial baseline. */
    public void reset() {
        entities.clear();
        storage.clear();
        storageTargets.clear();
        hasBaseline = false;
        storageBaseline = false;
    }

    private void captureEntity(MinecraftServer server, UUID id, List<WatchChange> changes) {
        Map<WatchSpec, Observation> before = entities.get(id);
        Entity entity;
        try {
            entity = findLoadedEntity(server, id);
        } catch (RuntimeException ignored) {
            return; // Do not corrupt the baseline when a live read failed.
        }
        if (entity == null) {
            if (before == null) return;
            Map<WatchSpec, Observation> after = missingTarget(before);
            compare(before, after, changes);
            entities.put(id, after);
            return;
        }

        Map<WatchSpec, Observation> after;
        try {
            after = entityValues(server, entity);
        } catch (RuntimeException ignored) {
            return; // A failed read is not evidence of a change.
        }
        if (before == null) {
            entities.put(id, after);
            return;
        }
        fillRemovedEntityValues(before, after, entity.getName().getString());
        fillCreatedEntityValues(before, after);
        compare(before, after, changes);
        entities.put(id, after);
    }

    private Map<WatchSpec, Observation> entityValues(MinecraftServer server, Entity entity) {
        UUID id = entity.getUUID();
        String key = "entity:" + id;
        String name = boundedName(entity.getName().getString());
        Map<WatchSpec, Observation> values = new LinkedHashMap<>();
        Collection<Objective> objectives = server.getScoreboard().getObjectives();
        for (Objective objective : objectives) {
            WatchSpec spec = new WatchSpec(WatchSpec.Kind.SCORE, objective.getName(), "", id);
            var score = server.getScoreboard().getPlayerScoreInfo(entity, objective);
            values.put(spec, score == null
                ? observation(WatchResult.Status.VALUE_MISSING, "", key, name, "")
                : observation(WatchResult.Status.VALUE, Integer.toString(score.value()), key, name,
                    Integer.toString(score.value())));
        }
        collectNbt(values, WatchSpec.Kind.ENTITY_NBT, "", id, NbtPredicate.getEntityTagToCompare(entity), key, name);
        return values;
    }

    private void fillRemovedEntityValues(Map<WatchSpec, Observation> before,
                                         Map<WatchSpec, Observation> after, String liveName) {
        String name = boundedName(liveName);
        for (WatchSpec spec : before.keySet()) {
            if (after.containsKey(spec)) continue;
            WatchResult previous = before.get(spec).result();
            WatchResult.Status status = spec.kind() == WatchSpec.Kind.SCORE
                ? WatchResult.Status.OBJECTIVE_MISSING : WatchResult.Status.VALUE_MISSING;
            after.put(spec, observation(status, "", previous.targetKey(), name, "", before.get(spec).visible()));
        }
    }

    private static void fillCreatedEntityValues(Map<WatchSpec, Observation> before,
                                                Map<WatchSpec, Observation> after) {
        for (var entry : after.entrySet()) {
            if (before.containsKey(entry.getKey())) continue;
            WatchResult value = entry.getValue().result();
            WatchResult.Status status = entry.getKey().kind() == WatchSpec.Kind.SCORE
                ? WatchResult.Status.OBJECTIVE_MISSING : WatchResult.Status.VALUE_MISSING;
            before.put(entry.getKey(), observation(status, "", value.targetKey(), value.targetName(), "", entry.getValue().visible()));
        }
    }

    private void captureStorage(MinecraftServer server, List<WatchChange> changes) {
        Map<WatchSpec, Observation> after = new LinkedHashMap<>();
        Set<Identifier> keys;
        try {
            keys = server.getCommandStorage().keys().collect(java.util.stream.Collectors.toSet());
        } catch (RuntimeException ignored) {
            return;
        }
        Set<String> currentTargets = new HashSet<>();
        for (Identifier id : keys) {
            currentTargets.add(id.toString());
            try {
                CompoundTag root = server.getCommandStorage().get(id).copy();
                collectNbt(after, WatchSpec.Kind.STORAGE_NBT, id.toString(), null, root,
                    "storage:" + id, "");
            } catch (RuntimeException ignored) {
                return; // Storage is one logical snapshot; keep its old baseline intact.
            }
        }
        if (!storageBaseline) {
            storage.clear();
            storage.putAll(after);
            storageTargets.clear();
            storageTargets.addAll(currentTargets);
            storageBaseline = true;
            return;
        }
        for (var entry : after.entrySet()) {
            if (storage.containsKey(entry.getKey())) continue;
            WatchResult value = entry.getValue().result();
            WatchResult.Status status = storageTargets.contains(entry.getKey().target())
                ? WatchResult.Status.VALUE_MISSING : WatchResult.Status.TARGET_MISSING;
            storage.put(entry.getKey(), observation(status, "", value.targetKey(), "", "", entry.getValue().visible()));
        }
        for (WatchSpec spec : storage.keySet()) {
            if (after.containsKey(spec)) continue;
            WatchResult previous = storage.get(spec).result();
            Identifier id = Identifier.tryParse(spec.target());
            WatchResult.Status status = id == null || !keys.contains(id)
                ? WatchResult.Status.TARGET_MISSING : WatchResult.Status.VALUE_MISSING;
            after.put(spec, observation(status, "", previous.targetKey(), "", "", storage.get(spec).visible()));
        }
        compare(storage, after, changes);
        storage.clear();
        storage.putAll(after);
        storageTargets.clear();
        storageTargets.addAll(currentTargets);
    }

    private static Map<WatchSpec, Observation> missingTarget(Map<WatchSpec, Observation> before) {
        Map<WatchSpec, Observation> result = new LinkedHashMap<>();
        for (var entry : before.entrySet()) {
            WatchResult value = entry.getValue().result();
            result.put(entry.getKey(), observation(WatchResult.Status.TARGET_MISSING, "", value.targetKey(), value.targetName(), "", entry.getValue().visible()));
        }
        return result;
    }

    private static void compare(Map<WatchSpec, Observation> before, Map<WatchSpec, Observation> after,
                                List<WatchChange> changes) {
        for (var entry : after.entrySet()) {
            Observation old = before.get(entry.getKey());
            Observation value = entry.getValue();
            if (old != null && (old.visible() || value.visible()) && !old.signature().equals(value.signature())) {
                changes.add(new WatchChange(entry.getKey(), old.result(), value.result()));
            }
        }
    }

    private static void collectNbt(Map<WatchSpec, Observation> values, WatchSpec.Kind kind, String target, UUID executor,
                                   Tag tag, String targetKey, String targetName) {
        collectNbt(values, kind, target, executor, tag, targetKey, targetName, "");
    }

    private static void collectNbt(Map<WatchSpec, Observation> values, WatchSpec.Kind kind, String target, UUID executor,
                                   Tag tag, String targetKey, String targetName, String path) {
        if (tag instanceof CompoundTag compound) {
            if (compound.isEmpty() && !path.isEmpty()) add(values, kind, target, executor, path, tag, targetKey, targetName);
            else if (compound.isEmpty()) add(values, kind, target, executor, ROOT_PATH, tag, targetKey, targetName);
            else add(values, kind, target, executor, path.isEmpty() ? ROOT_PATH : path, tag, targetKey, targetName, false);
            for (String child : compound.keySet().stream().sorted().toList()) {
                String next = path.isEmpty() ? quoted(child) : path + "." + quoted(child);
                addOrCollapse(values, kind, target, executor, compound, compound.get(child), targetKey, targetName, path, next);
            }
        } else if (tag instanceof CollectionTag collection) {
            if (collection.isEmpty()) add(values, kind, target, executor, path.isEmpty() ? ROOT_PATH : path, tag, targetKey, targetName);
            else add(values, kind, target, executor, path.isEmpty() ? ROOT_PATH : path, tag, targetKey, targetName, false);
            for (int index = 0; index < collection.size(); index++) {
                String next = path + "[" + index + "]";
                addOrCollapse(values, kind, target, executor, collection, collection.get(index), targetKey, targetName, path, next);
            }
        } else if (!path.isEmpty()) {
            add(values, kind, target, executor, path, tag, targetKey, targetName);
        }
    }

    private static void addOrCollapse(Map<WatchSpec, Observation> values, WatchSpec.Kind kind, String target, UUID executor,
                                      Tag parentTag, Tag child, String targetKey, String targetName, String parent, String next) {
        if (validPath(next)) collectNbt(values, kind, target, executor, child, targetKey, targetName, next);
        else add(values, kind, target, executor, validPath(parent) ? parent : ROOT_PATH, parentTag, targetKey, targetName);
    }

    private static void add(Map<WatchSpec, Observation> values, WatchSpec.Kind kind, String target, UUID executor,
                            String path, Tag tag, String targetKey, String targetName) {
        add(values, kind, target, executor, path, tag, targetKey, targetName, true);
    }

    private static void add(Map<WatchSpec, Observation> values, WatchSpec.Kind kind, String target, UUID executor,
                            String path, Tag tag, String targetKey, String targetName, boolean visible) {
        WatchSpec spec = new WatchSpec(kind, target, path, executor);
        String value = tag.toString();
        WatchResult.Status status = value.length() > WatchResult.MAX_VALUE_LENGTH ? WatchResult.Status.TOO_LARGE : WatchResult.Status.VALUE;
        values.put(spec, observation(status, status == WatchResult.Status.VALUE ? value : "", targetKey, targetName, value, visible));
    }

    private static boolean validPath(String path) {
        return !path.isEmpty() && path.length() <= WatchSpec.MAX_INPUT_LENGTH
            && path.chars().noneMatch(c -> c < 32 || c == 127 || c == 167);
    }

    private static String quoted(String key) {
        return "\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Observation observation(WatchResult.Status status, String value, String targetKey,
                                           String targetName, String signature) {
        return observation(status, value, targetKey, targetName, signature, true);
    }

    private static Observation observation(WatchResult.Status status, String value, String targetKey,
                                           String targetName, String signature, boolean visible) {
        return new Observation(new WatchResult(status, value, targetKey, targetName), status.name() + '\u0000' + signature, visible);
    }

    /** The signature stays server-local so equal wire placeholders never hide a real NBT mutation. */
    private record Observation(WatchResult result, String signature, boolean visible) { }

    private static Entity findLoadedEntity(MinecraftServer server, UUID uuid) {
        for (var level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity != null && !entity.isRemoved()) return entity;
        }
        return null;
    }

    private static String boundedName(String name) {
        if (name.length() <= WatchResult.MAX_TARGET_NAME_LENGTH) return name;
        int end = WatchResult.MAX_TARGET_NAME_LENGTH;
        if (Character.isHighSurrogate(name.charAt(end - 1))) end--;
        return name.substring(0, end);
    }
}
