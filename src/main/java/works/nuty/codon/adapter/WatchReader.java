package works.nuty.codon.adapter;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.advancements.predicates.NbtPredicate;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

import java.util.List;
import java.util.stream.Collectors;

import static works.nuty.codon.core.model.WatchResult.Status.*;

/** Server-thread reads only. Never creates scores, loads chunks, or applies NBT mutations. */
public final class WatchReader {
    private static final int MAX_ROOT_BYTES = 1_048_576;
    private static final int MAX_MATCHES = 32;

    private WatchReader() {}

    public static WatchResult read(MinecraftServer server, PauseSnapshot snapshot, int sourceIndex, WatchSpec spec) {
        if (!server.isSameThread()) throw new IllegalStateException("Watch reads require the server thread");
        if (spec.executor() != null) return readCapturedEntity(server, spec.executor(), spec);
        String key;
        CompoundTag nbt;
        if (spec.kind() == WatchSpec.Kind.STORAGE_NBT) {
            Identifier id = Identifier.tryParse(spec.target());
            if (id == null) return WatchResult.absent(INVALID_PATH, "storage:" + spec.target());
            key = "storage:" + id;
            nbt = server.getCommandStorage().get(id);
            // Empty storage entries are removed by vanilla, so this also detects absent keys.
            if (nbt.isEmpty()) return WatchResult.absent(TARGET_MISSING, key);
        } else {
            if (sourceIndex < 0 || sourceIndex >= snapshot.pauseSources().size()
                || snapshot.pauseSources().get(sourceIndex).entity() == null) {
                return WatchResult.absent(NO_EXECUTOR, "no-executor");
            }
            var source = snapshot.pauseSources().get(sourceIndex);
            WatchResult result = readCapturedEntity(server, source.entity().uuid(), spec);
            return result.targetName().isBlank() ? result.withTargetName(boundedName(source.entity().name())) : result;
        }
        return readPath(nbt, spec.path(), key);
    }

    public static WatchResult readCapturedEntity(MinecraftServer server, java.util.UUID uuid, WatchSpec spec) {
        if (!server.isSameThread()) throw new IllegalStateException("Watch reads require the server thread");
        if (spec.kind() == WatchSpec.Kind.STORAGE_NBT) return WatchResult.absent(INVALID_PATH, "entity:" + uuid);
        Entity entity = findLoadedEntity(server, uuid);
        String key = "entity:" + uuid;
        if (entity == null) return WatchResult.absent(TARGET_MISSING, key);
        String name = boundedName(entity.getName().getString());
        WatchResult result;
        if (spec.kind() == WatchSpec.Kind.SCORE) {
            var objective = server.getScoreboard().getObjective(spec.target());
            if (objective == null) result = WatchResult.absent(OBJECTIVE_MISSING, key);
            else {
                var score = server.getScoreboard().getPlayerScoreInfo(entity, objective);
                result = score == null ? WatchResult.absent(VALUE_MISSING, key)
                    : new WatchResult(VALUE, Integer.toString(score.value()), key);
            }
        } else {
            result = readPath(NbtPredicate.getEntityTagToCompare(entity), spec.path(), key);
        }
        return result.withTargetName(name);
    }

    private static String boundedName(String name) {
        if (name.length() <= WatchResult.MAX_TARGET_NAME_LENGTH) return name;
        int end = WatchResult.MAX_TARGET_NAME_LENGTH;
        if (Character.isHighSurrogate(name.charAt(end - 1))) end--;
        return name.substring(0, end);
    }

    private static Entity findLoadedEntity(MinecraftServer server, java.util.UUID uuid) {
        for (var level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity != null && !entity.isRemoved()) return entity;
        }
        return null;
    }

    /** Only serializable strings leave this method; no live storage tag escapes. */
    public static WatchResult readPath(CompoundTag nbt, String pathText, String targetKey) {
        NbtPathArgument.NbtPath path;
        try {
            StringReader reader = new StringReader(pathText);
            path = NbtPathArgument.nbtPath().parse(reader);
            if (reader.canRead()) return WatchResult.absent(INVALID_PATH, targetKey);
        } catch (CommandSyntaxException | RuntimeException e) {
            // Vanilla's parser can also throw index errors for unfinished input such as "foo[".
            return WatchResult.absent(INVALID_PATH, targetKey);
        }
        // Bound traversal/formatting work while the execution thread is parked.
        if (nbt.sizeInBytes() > MAX_ROOT_BYTES) return WatchResult.absent(TOO_LARGE, targetKey);
        List<Tag> values;
        try {
            values = path.get(nbt);
        } catch (CommandSyntaxException e) {
            return WatchResult.absent(VALUE_MISSING, targetKey);
        }
        if (values.isEmpty()) return WatchResult.absent(VALUE_MISSING, targetKey);
        if (values.size() > MAX_MATCHES
            || values.stream().mapToLong(Tag::sizeInBytes).sum() > WatchResult.MAX_VALUE_LENGTH * 4L) {
            return WatchResult.absent(TOO_LARGE, targetKey);
        }
        // Vanilla's StringTagVisitor sorts compound keys, preserving stable equality across saves.
        String value = values.size() == 1 ? values.getFirst().toString()
            : values.size() + " matches: [" + values.stream().map(Tag::toString).collect(Collectors.joining(",")) + "]";
        if (value.length() > WatchResult.MAX_VALUE_LENGTH) return WatchResult.absent(TOO_LARGE, targetKey);
        return new WatchResult(VALUE, value, targetKey);
    }
}
