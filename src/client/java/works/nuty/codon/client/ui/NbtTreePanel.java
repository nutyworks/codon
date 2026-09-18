package works.nuty.codon.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientNbtState;
import works.nuty.codon.client.state.ClientWatchState;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import works.nuty.codon.core.model.EntityRef;
import works.nuty.codon.core.model.NbtPage;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.WatchSpec;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static works.nuty.codon.client.ui.DebuggerTheme.*;

/** Compact, lazy NBT-tree presentation for every entity source at the current debugger stop. */
public final class NbtTreePanel {
    private static final Bounds EMPTY = new Bounds(0, 0, 0, 0);
    private static final int HEADER_HEIGHT = 18;
    private static final int ROW_HEIGHT = 17;

    private final ClientDebuggerState state;
    private Bounds scrollBounds = EMPTY;
    private long boundsPause;
    private record DisplayRow(ClientNbtState.EntitySource source, ClientNbtState.@Nullable Row row) {}
    private int offset;

    public NbtTreePanel(ClientDebuggerState state) {
        this.state = state;
    }

    public int preferredHeight(int maximum) {
        if (state.nbt().entitySources().isEmpty() || maximum < HEADER_HEIGHT) return 0;
        if (!state.nbt().enabled()) return Math.min(maximum, HEADER_HEIGHT);
        return Math.min(maximum, 22 + displayRows().size() * ROW_HEIGHT);
    }

    private List<DisplayRow> displayRows() {
        List<DisplayRow> rows = new ArrayList<>();
        for (var source : state.nbt().entitySources()) {
            rows.add(new DisplayRow(source, null));
            if (state.nbt().sourceExpanded(source.executor().uuid())) {
                for (var row : state.nbt().rows(source.executor().uuid())) rows.add(new DisplayRow(source, row));
            }
        }
        return rows;
    }

    public void render(GuiGraphicsExtractor graphics, Bounds area, int mouseX, int mouseY, Controls controls) {
        if (state.nbt().entitySources().isEmpty() || area.width() <= 0 || area.height() <= 0) {
            clearBounds();
            return;
        }
        long pauseId = pauseId();
        if (boundsPause != pauseId) {
            offset = 0;
            boundsPause = pauseId;
        }
        graphics.fill(area.x(), area.y(), area.x() + area.width(), area.y() + area.height(), SURFACE);
        graphics.outline(area.x(), area.y(), area.width(), area.height(), BORDER);

        boolean enabled = state.nbt().enabled();
        Component title = Component.literal((enabled ? "▾ NBT · " : "▸ NBT · ") + state.nbt().entitySources().size());
        controls.button("nbt-header", new Bounds(area.x() + 2, area.y() + 1, Math.max(1, area.width() - 4), HEADER_HEIGHT - 2),
            title, true, enabled, () -> {
                if (pauseId > 0 && pauseId == pauseId()) state.nbt().setEnabled(!enabled);
            }).setTooltip(Tooltip.create(Component.translatable(enabled ? "codon.nbt.collapse" : "codon.nbt.expand")));
        if (!enabled) {
            clearBounds();
            return;
        }

        List<DisplayRow> rows = displayRows();
        int visibleRows = Math.max(0, (area.height() - HEADER_HEIGHT - 2) / ROW_HEIGHT);
        offset = Math.clamp(offset, 0, Math.max(0, rows.size() - visibleRows));
        scrollBounds = new Bounds(area.x(), area.y() + HEADER_HEIGHT, area.width(), Math.max(0, area.height() - HEADER_HEIGHT - 2));
        for (int index = 0; index < visibleRows && offset + index < rows.size(); index++) {
            DisplayRow display = rows.get(offset + index);
            Bounds bounds = new Bounds(area.x() + 3, area.y() + HEADER_HEIGHT + index * ROW_HEIGHT,
                Math.max(1, area.width() - 9), ROW_HEIGHT);
            String idPrefix = "nbt-" + display.source().index() + "-" + display.source().executor().uuid() + "-";
            if (display.row() == null) {
                var source = display.source();
                boolean expanded = state.nbt().sourceExpanded(source.executor().uuid());
                Component label = Component.literal((expanded ? "▾ #" : "▸ #") + (source.index() + 1) + " · " + source.executor().name());
                controls.button(idPrefix + "source", bounds, label, true, expanded, () -> {
                    if (!isCurrent(pauseId, source.executor().uuid())) return;
                    state.nbt().toggleSource(source.executor().uuid());
                }).setTooltip(Tooltip.create(Component.literal(source.executor().name() + "\n" + source.executor().uuid())));
            } else {
                renderRow(graphics, bounds, mouseX, mouseY, controls, display.row(), pauseId, display.source().executor(), idPrefix);
            }
        }
        if (visibleRows > 0 && rows.size() > visibleRows) {
            int height = visibleRows * ROW_HEIGHT;
            int thumb = Math.max(8, height * visibleRows / rows.size());
            int top = area.y() + HEADER_HEIGHT + (height - thumb) * offset / (rows.size() - visibleRows);
            graphics.fill(area.x() + area.width() - 3, area.y() + HEADER_HEIGHT,
                area.x() + area.width() - 1, area.y() + HEADER_HEIGHT + height, BORDER);
            graphics.fill(area.x() + area.width() - 3, top, area.x() + area.width() - 1, top + thumb, TEAL);
        }
    }

    public boolean scroll(double x, double y, double amount) {
        if (!scrollBounds.contains(x, y) || amount == 0 || !state.nbt().enabled()) return false;
        int rows = displayRows().size();
        int visibleRows = Math.max(0, scrollBounds.height() / ROW_HEIGHT);
        offset = Math.clamp(offset + (amount > 0 ? -1 : 1), 0, Math.max(0, rows - visibleRows));
        return true;
    }

    public void clearBounds() {
        scrollBounds = EMPTY;
    }

    private void renderRow(GuiGraphicsExtractor graphics, Bounds bounds, int mouseX, int mouseY, Controls controls,
                           ClientNbtState.Row row, long pauseId, EntityRef executor, String idPrefix) {
        int indent = Math.min(8, Math.max(0, row.depth())) * 8;
        int pinWidth = 18;
        int contentWidth = Math.max(1, bounds.width() - indent - pinWidth - 2);
        Bounds content = new Bounds(bounds.x() + indent, bounds.y(), contentWidth, bounds.height());
        switch (row.kind()) {
            case NODE -> renderNode(graphics, bounds, content, mouseX, mouseY, controls, row, pauseId, executor, idPrefix);
            case STATUS -> renderStatus(graphics, content, controls, row, pauseId, executor.uuid(), idPrefix);
            case EMPTY -> text(graphics, Component.translatable("codon.nbt.empty").getString(), content, MUTED);
            case PREVIOUS, NEXT -> {
                Component label = Component.translatable(row.kind() == ClientNbtState.Kind.PREVIOUS
                    ? "codon.nbt.previous" : "codon.nbt.next");
                controls.button(idPrefix + "page-" + stable(row.path()) + "-" + row.targetOffset(), content, label, true, false,
                    () -> { if (isCurrent(pauseId, executor.uuid())) state.nbt().page(executor.uuid(), row.path(), row.targetOffset()); });
            }
        }
    }

    private void renderNode(GuiGraphicsExtractor graphics, Bounds bounds, Bounds content, int mouseX, int mouseY,
                            Controls controls, ClientNbtState.Row row, long pauseId, EntityRef executor, String idPrefix) {
        NbtPage.Node node = row.node();
        if (node == null) return;
        String prefix = node.expandable() ? (row.expanded() ? "▾ " : "▸ ") : "  ";
        Component label = Component.literal(prefix + node.name() + ": " + node.preview());
        String nodeId = stable(node.path().isEmpty() ? node.name() + ":" + bounds.y() : node.path());
        controls.button(idPrefix + "node-" + nodeId, content, label, node.expandable(), row.expanded(),
            () -> { if (isCurrent(pauseId, executor.uuid())) state.nbt().toggle(executor.uuid(), node.path()); });
        if (bounds.contains(mouseX, mouseY)) {
            graphics.setComponentTooltipForNextFrame(Minecraft.getInstance().font,
                List.of(Component.literal(node.path()), Component.literal(node.preview())), mouseX, mouseY);
        }

        Bounds pinBounds = new Bounds(bounds.x() + bounds.width() - 18, bounds.y(), 18, 16);
        WatchSpec spec = pinnableSpec(node, executor.uuid());
        long existing = existingPin(spec);
        boolean present = existing >= 0;
        boolean active = spec != null;
        Component pinLabel = Component.translatable(present ? "codon.nbt.unpin" : "codon.nbt.pin", executor.name());
        DebuggerButton pin = controls.button(idPrefix + "pin-" + nodeId, pinBounds, pinLabel, active, present,
            () -> togglePin(pauseId, executor.uuid(), node.path()));
        pin.withIcon(DebuggerIcon.PIN).withSecondaryAction(() -> toggleAllPins(pauseId, executor.uuid(), node.path()));
        Component pinTooltip;
        if (spec == null) pinTooltip = Component.translatable("codon.nbt.path_unavailable");
        else {
            Component left = Component.translatable(present ? "codon.nbt.click_unpin" : "codon.nbt.click_pin", executor.name());
            List<WatchSpec> all = allSpecs(node.path());
            boolean allPinned = !all.isEmpty() && new HashSet<>(state.watches().definitions()).containsAll(all);
            Component right = Component.translatable(allPinned ? "codon.nbt.click_all_remove" : "codon.nbt.click_all", all.size());
            pinTooltip = left.copy().append("\n").append(right);
        }
        pin.setTooltip(Tooltip.create(pinTooltip.copy().append("\n").append(node.path())));
    }

    private void renderStatus(GuiGraphicsExtractor graphics, Bounds content, Controls controls, ClientNbtState.Row row,
                              long pauseId, UUID executor, String idPrefix) {
        Component label = row.status() == null ? Component.translatable("codon.nbt.pending") : WatchFormatting.status(row.status());
        text(graphics, label.getString(), new Bounds(content.x(), content.y(), Math.max(1, content.width() - 48), content.height()),
            row.status() == null ? MUTED : RED);
        Bounds refresh = new Bounds(content.x() + Math.max(0, content.width() - 44), content.y(), 44, content.height());
        controls.button(idPrefix + "refresh-" + stable(row.path()), refresh, Component.translatable("codon.nbt.refresh"), row.status() != null, false,
            () -> { if (isCurrent(pauseId, executor)) state.nbt().refresh(executor); });
    }

    private void togglePin(long pauseId, UUID executor, String path) {
        if (!isCurrent(pauseId, executor)) return;
        WatchSpec spec;
        try {
            spec = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", path, executor);
        } catch (IllegalArgumentException invalid) {
            return;
        }
        long present = existingPin(spec);
        if (present >= 0) {
            state.watches().remove(present);
            return;
        }
        if (state.watches().add(spec)) {
            PauseSnapshot snapshot = state.snapshot();
            if (snapshot != null) state.watches().rememberExecutors(snapshot.pauseSources());
        }
    }

    /** Right-click toggles the exact path across the current entity sources as one edit. */
    private void toggleAllPins(long pauseId, UUID executor, String path) {
        if (!isCurrent(pauseId, executor)) return;
        List<WatchSpec> specs = allSpecs(path);
        if (!specs.isEmpty()) {
            state.watches().toggleAll(specs);
            PauseSnapshot snapshot = state.snapshot();
            if (snapshot != null) state.watches().rememberExecutors(snapshot.pauseSources());
        }
    }

    private List<WatchSpec> allSpecs(String path) {
        try {
            return state.nbt().entitySources().stream().map(source ->
                new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", path, source.executor().uuid())).distinct().toList();
        } catch (IllegalArgumentException invalid) {
            return List.of();
        }
    }

    private long existingPin(@Nullable WatchSpec spec) {
        if (spec == null) return -1;
        return state.watches().entries().stream().filter(entry -> entry.spec().equals(spec)).mapToLong(ClientWatchState.Entry::id)
            .findFirst().orElse(-1);
    }

    private static @Nullable WatchSpec pinnableSpec(NbtPage.Node node, UUID executor) {
        if (!node.pinnable()) return null;
        try { return new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", node.path(), executor); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    private long pauseId() {
        PauseSnapshot snapshot = state.snapshot();
        return snapshot == null ? 0 : snapshot.pauseId();
    }

    private boolean isCurrent(long pauseId, UUID executor) {
        return pauseId > 0 && pauseId == pauseId() && state.nbt().contains(executor);
    }

    private static String stable(String path) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(path.getBytes(StandardCharsets.UTF_8));
    }

    private static void text(GuiGraphicsExtractor graphics, String value, Bounds bounds, int color) {
        var font = Minecraft.getInstance().font;
        String rendered = font.width(value) <= bounds.width() ? value
            : font.plainSubstrByWidth(value, Math.max(0, bounds.width() - font.width("…"))) + "…";
        graphics.enableScissor(bounds.x(), bounds.y(), bounds.x() + bounds.width(), bounds.y() + bounds.height());
        graphics.text(font, rendered, bounds.x() + 2, bounds.y() + 4, color, false);
        graphics.disableScissor();
    }

    @FunctionalInterface
    public interface Controls {
        DebuggerButton button(String id, Bounds bounds, Component label, boolean active, boolean selected, Runnable action);
    }
}
