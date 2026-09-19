package works.nuty.codon.client.state;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.WatchIdentity;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

/** Presentation-only grouping; changing modes never alters saved definitions or observations. */
public final class WatchGrouping {
    public enum Mode { CONTEXT, PATH, NONE }
    public record Key(String category, String value) { }
    public record Group(Key key, List<ClientWatchState.Entry> entries) { }
    private WatchGrouping() { }

    /** Initial/unchanged absence is quiet; disappearance and availability transitions remain prominent. */
    public static boolean isUnchangedMissing(ClientWatchState.Entry entry) {
        WatchResult result = entry.displayedResult();
        if (result == null || (result.status() != WatchResult.Status.VALUE_MISSING
            && result.status() != WatchResult.Status.NO_EXECUTOR)) return false;
        return entry.displayedChange() == ClientWatchState.Change.INITIAL
            || entry.displayedChange() == ClientWatchState.Change.UNCHANGED;
    }

    private static List<ClientWatchState.Entry> missingLast(List<ClientWatchState.Entry> entries) {
        return entries.stream().sorted(Comparator.comparing(WatchGrouping::isUnchangedMissing)).toList();
    }

    public static List<Group> groups(List<ClientWatchState.Entry> entries, Mode mode, @Nullable UUID currentEntity) {
        if (entries.isEmpty()) return List.of();
        if (mode == Mode.NONE) return List.of(new Group(new Key("none", ""), missingLast(entries)));
        var grouped = new LinkedHashMap<Key, List<ClientWatchState.Entry>>();
        for (var entry : entries) {
            WatchSpec spec = entry.spec();
            Key key;
            if (spec.kind() == WatchSpec.Kind.STORAGE_NBT) {
                key = new Key("storage", spec.target());
            } else if (mode == Mode.PATH) {
                key = new Key(spec.kind().name(), spec.target() + "\n" + WatchIdentity.canonicalPath(spec.path()));
            } else {
                UUID entity = entry.displayedExecutor();
                if (entity == null) entity = spec.executor() == null ? currentEntity : spec.executor();
                key = new Key("entity", entity == null ? "" : entity.toString());
            }
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
        }
        return grouped.entrySet().stream()
            .map(group -> new Group(group.getKey(), missingLast(group.getValue())))
            // Singletons have no heading, so their quiet entries belong at the bottom of Watches.
            .sorted(Comparator.comparing(group -> group.entries().size() == 1 && isUnchangedMissing(group.entries().getFirst())))
            .toList();
    }
}
