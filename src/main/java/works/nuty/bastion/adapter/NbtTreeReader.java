package works.nuty.bastion.adapter;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.advancements.predicates.NbtPredicate;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import works.nuty.bastion.core.model.NbtPage;
import works.nuty.bastion.core.model.PauseSnapshot;
import works.nuty.bastion.core.model.WatchResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class NbtTreeReader {
    private static final int MAX_ROOT_BYTES = 1_048_576;
    private NbtTreeReader() {}

    public static NbtPage read(MinecraftServer server, PauseSnapshot snapshot, int sourceIndex, String path, int offset) {
        if (!server.isSameThread()) throw new IllegalStateException("NBT reads require the server thread");
        if (offset < 0) return NbtPage.absent(WatchResult.Status.INVALID_PATH);
        if (sourceIndex < 0 || sourceIndex >= snapshot.pauseSources().size()
            || snapshot.pauseSources().get(sourceIndex).entity() == null) return NbtPage.absent(WatchResult.Status.NO_EXECUTOR);
        UUID uuid = snapshot.pauseSources().get(sourceIndex).entity().uuid();
        Entity entity = null;
        for (var level : server.getAllLevels()) { entity = level.getEntity(uuid); if (entity != null && !entity.isRemoved()) break; }
        if (entity == null || entity.isRemoved()) return NbtPage.absent(WatchResult.Status.TARGET_MISSING);
        CompoundTag root = NbtPredicate.getEntityTagToCompare(entity);
        return readPage(root, path, offset);
    }

    public static NbtPage readPage(CompoundTag root, String path, int offset) {
        if (root == null || path == null || path.length() > NbtPage.MAX_PATH_LENGTH || offset < 0)
            return NbtPage.absent(WatchResult.Status.INVALID_PATH);
        if (root.sizeInBytes() > MAX_ROOT_BYTES) return NbtPage.absent(WatchResult.Status.TOO_LARGE);
        Tag value = root;
        if (!path.isEmpty()) {
            NbtPathArgument.NbtPath parsed;
            try {
                StringReader reader = new StringReader(path);
                parsed = NbtPathArgument.nbtPath().parse(reader);
                if (reader.canRead()) return NbtPage.absent(WatchResult.Status.INVALID_PATH);
            } catch (CommandSyntaxException | RuntimeException e) { return NbtPage.absent(WatchResult.Status.INVALID_PATH); }
            try {
                List<Tag> matches = parsed.get(root);
                if (matches.size() != 1) return NbtPage.absent(matches.isEmpty() ? WatchResult.Status.VALUE_MISSING : WatchResult.Status.INVALID_PATH);
                value = matches.getFirst();
            } catch (CommandSyntaxException e) { return NbtPage.absent(WatchResult.Status.VALUE_MISSING); }
        }
        int total = childCount(value);
        if (offset > total) return NbtPage.absent(WatchResult.Status.INVALID_PATH);
        int end = (int) Math.min(total, (long) offset + NbtPage.PAGE_SIZE);
        List<String> keys = value instanceof CompoundTag compound
            ? compound.entrySet().stream().map(java.util.Map.Entry::getKey).sorted().toList() : List.of();
        List<NbtPage.Node> page = new ArrayList<>();
        for (int i = offset; i < end; i++) {
            String name;
            String childPath;
            Tag child;
            if (value instanceof CompoundTag compound) {
                name = keys.get(i);
                child = compound.get(name);
                childPath = (path.isEmpty() ? "" : path + ".") + quotedKey(name);
            } else {
                name = "[" + i + "]";
                child = ((CollectionTag) value).get(i);
                childPath = path + name;
            }
            if (childPath.length() > NbtPage.MAX_PATH_LENGTH
                || childPath.chars().anyMatch(c -> c < 32 || c == 127 || c == 167)) childPath = "";
            page.add(new NbtPage.Node(compact(name, NbtPage.MAX_NAME_LENGTH), childPath,
                preview(child), childCount(child) > 0 && !childPath.isEmpty()));
        }
        return new NbtPage(WatchResult.Status.VALUE, page, offset, total);
    }

    private static int childCount(Tag value) {
        if (value instanceof CompoundTag compound) return compound.size();
        return value instanceof CollectionTag collection ? collection.size() : 0;
    }

    /** Brigadier NBT paths use quoted compound names joined by dots, then list/array indices. */
    private static String quotedKey(String key) {
        return "\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String preview(Tag value) {
        if (value instanceof CompoundTag compound) return "{" + compound.size() + "}";
        if (value instanceof CollectionTag collection) return "[" + collection.size() + "]";
        return compact(value.toString(), NbtPage.MAX_PREVIEW_LENGTH);
    }

    private static String compact(String text, int max) {
        text = text.replaceAll("[\\p{Cntrl}§]", " ");
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
