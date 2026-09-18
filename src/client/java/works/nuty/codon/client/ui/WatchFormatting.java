package works.nuty.codon.client.ui;

import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.state.ClientWatchState;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Presentation-only labels for server-synchronised watch observations. */
public final class WatchFormatting {
    private WatchFormatting() { }

    public static Component specification(WatchSpec spec) {
        return switch (spec.kind()) {
            case SCORE -> Component.translatable("codon.watch.spec.score", spec.target());
            case ENTITY_NBT -> Component.translatable("codon.watch.spec.entity", spec.path());
            case STORAGE_NBT -> Component.translatable("codon.watch.spec.storage", spec.target(), spec.path());
        };
    }

    /** Labels name the entity whose value is displayed, including a completed previous-executor read. */
    public static Component specification(ClientWatchState.Entry entry) {
        Component name = specification(entry.spec());
        if (entry.displayedExecutor() == null) return name;
        return Component.literal(executorLabel(entry) + " · " + name.getString());
    }

    public static Component line(ClientWatchState.Entry entry, boolean paused) {
        return specification(entry).copy().append(": ").append(value(entry, paused));
    }

    public static String executorLabel(ClientWatchState.Entry entry) {
        UUID executor = entry.displayedExecutor();
        if (executor == null) return "";
        String name = entry.executorName().isBlank() ? "" : entry.executorName() + " ";
        return name + "#" + executor.toString().substring(0, 8);
    }

    public static Component status(WatchResult.Status status) {
        return Component.translatable("codon.watch.status." + status.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static Component status(WatchResult.Status status, boolean compact) {
        return compact ? Component.translatable("codon.watch.short." + status.name().toLowerCase(java.util.Locale.ROOT))
            : status(status);
    }

    /** Same-target creation/removal shows the absence label instead of inventing a numeric value. */
    public static Component value(ClientWatchState.Entry entry, boolean paused) {
        return value(entry.displayedResult(), entry.displayedChange(), entry.displayedPreviousValue(), paused, true);
    }

    public static Component fullValue(ClientWatchState.Entry entry, boolean paused) {
        return value(entry.displayedResult(), entry.displayedChange(), entry.displayedPreviousValue(), paused, false);
    }

    public static Component currentValue(ClientWatchState.Entry entry, boolean paused) {
        return value(entry.result(), entry.change(), entry.previousValue(), paused, false);
    }

    private static Component value(@Nullable WatchResult result, ClientWatchState.Change change, String previousValue,
                                   boolean paused, boolean compact) {
        if (result == null) return Component.translatable(paused ? "codon.watch.pending" : "codon.watch.running");
        if (change == ClientWatchState.Change.VALUE_APPEARED) {
            return Component.translatable("codon.watch.changed", status(WatchResult.Status.VALUE_MISSING, compact), result.value());
        }
        if (change == ClientWatchState.Change.VALUE_DISAPPEARED) {
            return Component.translatable("codon.watch.changed", previousValue, status(WatchResult.Status.VALUE_MISSING, compact));
        }
        if (result.status() != WatchResult.Status.VALUE) return status(result.status(), compact);
        if (change == ClientWatchState.Change.VALUE_CHANGED) {
            return Component.translatable("codon.watch.changed", previousValue, result.value());
        }
        return Component.literal(result.value());
    }

    public static Component changeBadge(ClientWatchState.Entry entry) {
        if (entry.completedStep() != null) return Component.empty();
        return changeBadge(entry.change());
    }

    public static List<Component> tooltip(ClientWatchState.Entry entry, boolean paused) {
        List<Component> lines = new ArrayList<>(List.of(specification(entry), fullValue(entry, paused)));
        UUID executor = entry.spec().executor();
        if (entry.spec().kind() != WatchSpec.Kind.STORAGE_NBT) {
            lines.add(executor == null
                ? Component.translatable("codon.watch.tooltip.follows")
                : Component.translatable("codon.watch.tooltip.pinned", executor.toString()));
        }
        if (entry.completedStep() != null) {
            lines.add(Component.translatable("codon.watch.tooltip.previous_executor", executorLabel(entry)));
            lines.add(Component.translatable("codon.watch.tooltip.current", currentValue(entry, paused)));
        } else if (entry.change() == ClientWatchState.Change.TARGET_CHANGED) {
            lines.add(Component.translatable("codon.watch.tooltip.executor_changed"));
        } else if (entry.change() == ClientWatchState.Change.AVAILABILITY_CHANGED) {
            lines.add(Component.translatable("codon.watch.tooltip.availability_changed"));
        }
        if (entry.displayedResult() != null && !entry.displayedResult().targetKey().isEmpty()) {
            lines.add(Component.translatable("codon.watch.tooltip.target", entry.displayedResult().targetKey()));
        }
        return List.copyOf(lines);
    }

    public static Component changeBadge(ClientWatchState.Change change) {
        return switch (change) {
            case INITIAL, UNCHANGED, VALUE_CHANGED, VALUE_APPEARED, VALUE_DISAPPEARED -> Component.empty();
            case TARGET_CHANGED -> Component.empty();
            case AVAILABILITY_CHANGED -> Component.translatable("codon.watch.change.availability");
        };
    }
}
