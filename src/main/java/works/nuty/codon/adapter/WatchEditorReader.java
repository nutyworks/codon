package works.nuty.codon.adapter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.advancements.predicates.NbtPredicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import works.nuty.codon.core.model.NbtPage;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.WatchEditorPage;
import works.nuty.codon.core.model.WatchEditorQuery;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

/** Read-only server-thread data source for the Watch add/editor interface. */
public final class WatchEditorReader {
    private WatchEditorReader() {}

    public static WatchEditorPage read(MinecraftServer server, PauseSnapshot snapshot, int sourceIndex, WatchEditorQuery query) {
        if (!server.isSameThread()) throw new IllegalStateException("Watch editor reads require the server thread");
        try {
            return switch (query.mode()) {
                case OBJECTIVES -> choices(server.getScoreboard().getObjectives().stream().map(objective ->
                    new WatchEditorPage.Option(objective.getName(), objective.getName(), "", false)).toList(), query);
                case STORAGES -> choices(server.getCommandStorage().keys().map(Identifier::toString).sorted().map(id ->
                    new WatchEditorPage.Option(id, id, "", false)).toList(), query);
                case ENTITIES -> entities(server, snapshot, query);
                case NBT -> nbt(server, snapshot, sourceIndex, query);
                case PREVIEW -> preview(server, snapshot, sourceIndex, query);
            };
        } catch (RuntimeException e) {
            return WatchEditorPage.absent(WatchResult.Status.ERROR);
        }
    }

    private static WatchEditorPage entities(MinecraftServer server, PauseSnapshot snapshot, WatchEditorQuery query) {
        Set<UUID> priority = new HashSet<>();
        if (snapshot != null) snapshot.pauseSources().forEach(source -> { if (source.entity() != null) priority.add(source.entity().uuid()); });
        List<Entity> values = new ArrayList<>();
        for (var level : server.getAllLevels()) for (Entity entity : level.getAllEntities()) if (!entity.isRemoved()) values.add(entity);
        values.sort(Comparator.comparing((Entity entity) -> !priority.contains(entity.getUUID()))
            .thenComparing(entity -> entity.getName().getString(), String.CASE_INSENSITIVE_ORDER).thenComparing(Entity::getUUID));
        List<WatchEditorPage.Option> options = new ArrayList<>(values.stream().map(WatchEditorReader::entityOption).toList());
        if (query.kind() == WatchSpec.Kind.SCORE) {
            Set<String> loaded = new HashSet<>();
            values.forEach(entity -> loaded.add(entity.getScoreboardName()));
            server.getScoreboard().getTrackedPlayers().stream()
                .map(net.minecraft.world.scores.ScoreHolder::getScoreboardName)
                .filter(name -> !loaded.contains(name))
                .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()))
                .forEach(name -> {
                    try {
                        WatchSpec.scoreHolder(query.target().isEmpty() ? "_" : query.target(), name);
                        // Literal quoting distinguishes even UUID-shaped holders from loaded entities.
                        String literal = "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
                        options.add(new WatchEditorPage.Option(literal, name, "", false));
                    } catch (IllegalArgumentException ignored) { /* Not representable as a saved watch. */ }
                });
        }
        return choices(options, query);
    }

    private static WatchEditorPage.Option entityOption(Entity entity) {
        String uuid = entity.getUUID().toString();
        String name = entity.getName().getString();
        String label = compact(name.isBlank() ? uuid : name, WatchEditorPage.MAX_LABEL_LENGTH);
        String detail = compact(entity.getType().getDescriptionId() + " · " + entity.level().dimension().identifier()
            + " · #" + uuid.substring(0, 8),
            WatchEditorPage.MAX_DETAIL_LENGTH);
        return new WatchEditorPage.Option(uuid, label, detail, true);
    }

    private static WatchEditorPage nbt(MinecraftServer server, PauseSnapshot snapshot, int sourceIndex, WatchEditorQuery query) {
        CompoundTag root;
        if (query.kind() == WatchSpec.Kind.STORAGE_NBT) {
            Identifier id = Identifier.tryParse(query.target());
            if (id == null) return WatchEditorPage.absent(WatchResult.Status.INVALID_PATH);
            root = server.getCommandStorage().get(id);
            if (root.isEmpty()) return WatchEditorPage.absent(WatchResult.Status.TARGET_MISSING);
        } else {
            UUID id = query.executor();
            if (id == null && snapshot != null && sourceIndex >= 0 && sourceIndex < snapshot.pauseSources().size()
                && snapshot.pauseSources().get(sourceIndex).entity() != null) id = snapshot.pauseSources().get(sourceIndex).entity().uuid();
            if (id == null) return WatchEditorPage.absent(WatchResult.Status.NO_EXECUTOR);
            Entity entity = findLoaded(server, id);
            if (entity == null) return WatchEditorPage.absent(WatchResult.Status.TARGET_MISSING);
            root = NbtPredicate.getEntityTagToCompare(entity);
        }
        NbtPage page = NbtTreeReader.readPage(root, query.path(), query.offset());
        if (page.status() != WatchResult.Status.VALUE) return WatchEditorPage.absent(page.status());
        List<WatchEditorPage.Option> options = page.children().stream().map(node -> new WatchEditorPage.Option(
            node.path(), node.name(), node.preview(), node.expandable())).toList();
        return new WatchEditorPage(WatchResult.Status.VALUE, options, page.offset(),
            page.offset() + page.children().size() < page.totalChildren(), null);
    }

    private static WatchEditorPage preview(MinecraftServer server, PauseSnapshot snapshot, int sourceIndex, WatchEditorQuery query) {
        if (query.path().length() > WatchSpec.MAX_INPUT_LENGTH || query.target().length() > WatchSpec.MAX_INPUT_LENGTH)
            return WatchEditorPage.absent(WatchResult.Status.INVALID_PATH);
        try {
            WatchSpec spec = new WatchSpec(query.kind(), query.target(), query.path(), query.executor(), query.scoreHolder());
            if (spec.kind() != WatchSpec.Kind.STORAGE_NBT && !spec.isPinned() && snapshot == null)
                return WatchEditorPage.absent(WatchResult.Status.NO_EXECUTOR);
            WatchResult result = WatchReader.read(server, snapshot, sourceIndex, spec);
            return new WatchEditorPage(result.status(), List.of(), 0, false, result);
        } catch (IllegalArgumentException e) {
            return WatchEditorPage.absent(WatchResult.Status.INVALID_PATH);
        }
    }

    private static WatchEditorPage choices(List<WatchEditorPage.Option> values, WatchEditorQuery query) {
        String search = query.search().toLowerCase(Locale.ROOT);
        List<WatchEditorPage.Option> matching = values.stream().filter(option -> search.isEmpty()
            || option.value().toLowerCase(Locale.ROOT).contains(search)
            || option.label().toLowerCase(Locale.ROOT).contains(search)
            || option.detail().toLowerCase(Locale.ROOT).contains(search)).toList();
        if (query.offset() > matching.size()) return WatchEditorPage.absent(WatchResult.Status.INVALID_PATH);
        int end = Math.min(matching.size(), query.offset() + WatchEditorPage.PAGE_SIZE);
        return new WatchEditorPage(WatchResult.Status.VALUE, matching.subList(query.offset(), end), query.offset(), end < matching.size(), null);
    }

    private static Entity findLoaded(MinecraftServer server, UUID id) {
        for (var level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null && !entity.isRemoved()) return entity;
        }
        return null;
    }

    private static String compact(String text, int max) {
        if (text.length() <= max) return text;
        int end = max - 1;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "…";
    }
}
