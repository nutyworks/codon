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
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static works.nuty.codon.client.ui.DebuggerTheme.*;

/** Compact, lazy NBT-tree presentation for the selected live entity source. */
public final class NbtTreePanel {
    private static final Bounds EMPTY = new Bounds(0, 0, 0, 0);
    private static final int HEADER_HEIGHT = 18;
    private static final int ROW_HEIGHT = 17;

    private final ClientDebuggerState state;
    private Bounds scrollBounds = EMPTY;
    private ClientNbtState.@Nullable EntitySource boundsSource;
    private int offset;
    private @Nullable String anchorPath;
    private int anchorRow;
    private Bounds anchorViewport = EMPTY;

    public NbtTreePanel(ClientDebuggerState state) {
        this.state = state;
    }

    /** The overlay reserves this panel from the inspector viewport, independent of tree content. */
    public boolean hasSelectedSource() { return selectedSource() != null; }

    private List<ClientNbtState.Row> displayRows(ClientNbtState.EntitySource source) {
        return state.nbt().displayedRows(source.executor().uuid());
    }

    private ClientNbtState.@Nullable EntitySource selectedSource() {
        int index = state.selectedPauseSourceIndex();
        return state.nbt().entitySources().stream().filter(source -> source.index() == index).findFirst().orElse(null);
    }

    public void render(GuiGraphicsExtractor graphics, Bounds area, int mouseX, int mouseY,
                       DebuggerNavigation navigation, ScrollbarInput scrollbars, Controls controls) {
        var selectedSource = selectedSource();
        if (selectedSource == null || area.width() <= 0 || area.height() <= 0) {
            clearBounds();
            return;
        }
        long pauseId = pauseId();
        if (boundsSource == null || !selectedSource.executor().uuid().equals(boundsSource.executor().uuid())) {
            offset = 0;
            anchorPath = null;
            anchorViewport = EMPTY;
        }
        boundsSource = selectedSource;
        graphics.fill(area.x(), area.y(), area.x() + area.width(), area.y() + area.height(), DebuggerTheme.color(SURFACE));
        graphics.outline(area.x(), area.y(), area.width(), area.height(), DebuggerTheme.color(BORDER));

        // This is an inert heading, registered only so the screen can retain its stable bounds.
        controls.button("nbt-heading", new Bounds(area.x() + 2, area.y() + 1, Math.max(1, area.width() - 4), HEADER_HEIGHT - 2),
            Component.literal("NBT"), false, false, () -> { }).withoutChrome();

        List<ClientNbtState.Row> rows = displayRows(selectedSource);
        List<ClientNbtState.Row> currentRows = state.nbt().rows(selectedSource.executor().uuid());
        var currentPaths = new HashSet<String>();
        for (int index : ClientNbtState.loadedIndices(currentRows)) {
            var row = currentRows.get(index);
            if (row.kind() == ClientNbtState.Kind.NODE) currentPaths.add(row.path());
        }
        int visibleRows = Math.max(0, (area.height() - HEADER_HEIGHT - 2) / ROW_HEIGHT);
        scrollBounds = new Bounds(area.x(), area.y() + HEADER_HEIGHT, area.width(), Math.max(0, area.height() - HEADER_HEIGHT - 2));
        applyAnchor(rows, visibleRows);
        offset = Math.clamp(offset, 0, maximumOffset(rows.size(), visibleRows));
        String idPrefix = "nbt-" + selectedSource.executor().uuid() + "-";
        if (visibleRows > 0) {
            for (int index : ClientNbtState.loadedIndices(rows)) {
                int logicalRow = index;
                registerRow(navigation, rows.get(index), idPrefix, selectedSource.executor(), index, () -> {
                    if (logicalRow < offset || logicalRow >= offset + visibleRows) {
                        anchorPath = null;
                        anchorViewport = EMPTY;
                        offset = DebuggerOverlay.revealRow(logicalRow, offset, visibleRows, Math.max(0, rows.size() - visibleRows));
                    }
                });
            }
        }
        navigation.revealFocus(DebuggerNavigation.Group.NBT);
        // Prefetch the next page so keyboard navigation and ordinary wheel scrolling stay continuous.
        if (visibleRows > 0) state.nbt().requestVisible(selectedSource.executor().uuid(), offset,
            visibleRows + NbtPage.PAGE_SIZE);
        for (int index = 0; index < visibleRows && offset + index < rows.size(); index++) {
            Bounds bounds = new Bounds(area.x() + 3, area.y() + HEADER_HEIGHT + index * ROW_HEIGHT,
                Math.max(1, area.width() - 9), ROW_HEIGHT);
            ClientNbtState.Row row = rows.get(offset + index);
            int hidden = ClientNbtState.hiddenCount(rows, offset + index);
            if (row.kind() == ClientNbtState.Kind.PLACEHOLDER && index > 0) {
                ClientNbtState.Row previous = rows.get(offset + index - 1);
                if (previous.kind() == row.kind() && previous.path().equals(row.path())
                    && previous.depth() == row.depth() && previous.status() == row.status()) continue;
            }
            renderRow(graphics, bounds, mouseX, mouseY, controls, row, pauseId, selectedSource.executor(), idPrefix, hidden,
                currentPaths.contains(row.path()));
        }
        int maximumOffset = maximumOffset(rows.size(), visibleRows);
        if (visibleRows > 0 && maximumOffset > 0) {
            int height = visibleRows * ROW_HEIGHT;
            int thumb = (int) Math.max(8, (long) height * visibleRows / ((long) visibleRows + maximumOffset));
            int top = area.y() + HEADER_HEIGHT + (int) ((long) (height - thumb) * offset / maximumOffset);
            scrollbars.add(idPrefix + "scroll", false, area.x() + area.width() - 3,
                area.y() + HEADER_HEIGHT, height, 2, thumb, offset, maximumOffset, value -> {
                    // A branch's click anchor must not undo explicit pointer scrolling next frame.
                    anchorPath = null;
                    anchorViewport = EMPTY;
                    offset = Math.clamp(value, 0, Math.max(0, rows.size() - visibleRows));
                });
            graphics.fill(area.x() + area.width() - 3, area.y() + HEADER_HEIGHT,
                area.x() + area.width() - 1, area.y() + HEADER_HEIGHT + height, DebuggerTheme.color(BORDER));
            graphics.fill(area.x() + area.width() - 3, top, area.x() + area.width() - 1, top + thumb, DebuggerTheme.color(TEAL));
        }
    }

    private void registerRow(DebuggerNavigation navigation, ClientNbtState.Row row, String prefix,
                             EntityRef executor, int index, Runnable reveal) {
        switch (row.kind()) {
            case NODE -> {
                NbtPage.Node node = row.node();
                if (node == null) return;
                String id = nodeId(node);
                if (node.expandable()) navigation.add(prefix + "node-" + id, DebuggerNavigation.Group.NBT, index, 0, reveal);
                if (pinnableSpec(node, executor.uuid()) != null)
                    navigation.add(prefix + "pin-" + id, DebuggerNavigation.Group.NBT, index, 1, reveal);
            }
            case STATUS -> {
                if (row.status() != null)
                    navigation.add(prefix + "refresh-" + stable(row.path()),
                    DebuggerNavigation.Group.NBT, index, 0, reveal);
            }
            case EMPTY, PLACEHOLDER -> { }
        }
    }

    private static String nodeId(NbtPage.Node node) {
        return stable(node.path().isEmpty() ? node.name() : node.path());
    }

    public boolean scroll(double x, double y, double amount) {
        if (!scrollBounds.contains(x, y) || amount == 0) return false;
        var source = selectedSource();
        if (source == null) return false;
        int rows = displayRows(source).size();
        int visibleRows = Math.max(0, scrollBounds.height() / ROW_HEIGHT);
        anchorPath = null;
        anchorViewport = EMPTY;
        offset = Math.clamp(offset + (amount > 0 ? -1 : 1), 0, Math.max(0, rows - visibleRows));
        return true;
    }

    public void clearBounds() {
        scrollBounds = EMPTY;
    }

    private void applyAnchor(List<ClientNbtState.Row> rows, int visibleRows) {
        if (anchorPath == null) return;
        if (anchorViewport.width() != scrollBounds.width() || anchorViewport.height() != scrollBounds.height()) {
            anchorPath = null;
            anchorViewport = EMPTY;
            return;
        }
        int index = -1;
        for (int row : ClientNbtState.loadedIndices(rows)) {
            if (anchorPath.equals(rows.get(row).path())) {
                index = row;
                break;
            }
        }
        if (index >= 0) offset = Math.max(0, index - anchorRow);
        else {
            anchorPath = null;
            anchorViewport = EMPTY;
        }
        offset = Math.clamp(offset, 0, maximumOffset(rows.size(), visibleRows));
    }

    private int maximumOffset(int rowCount, int visibleRows) {
        // An anchored branch may keep a short trailing viewport after collapse so its click target
        // remains at the same row. Ordinary wheel scrolling still stops at the last full page.
        int fullPageMaximum = Math.max(0, rowCount - visibleRows);
        return anchorPath == null ? fullPageMaximum
            : Math.max(fullPageMaximum, Math.clamp(offset, 0, Math.max(0, rowCount - 1)));
    }

    private void renderRow(GuiGraphicsExtractor graphics, Bounds bounds, int mouseX, int mouseY, Controls controls,
                           ClientNbtState.Row row, long pauseId, EntityRef executor, String idPrefix, int hidden, boolean current) {
        int indent = Math.min(8, Math.max(0, row.depth())) * 8;
        int pinWidth = 18;
        int contentWidth = Math.max(1, bounds.width() - indent - pinWidth - 2);
        Bounds content = new Bounds(bounds.x() + indent, bounds.y(), contentWidth, bounds.height());
        switch (row.kind()) {
            case NODE -> renderNode(graphics, bounds, content, mouseX, mouseY, controls, row, pauseId, executor, idPrefix, current);
            case STATUS -> renderStatus(graphics, content, controls, row, pauseId, executor.uuid(), idPrefix, 0);
            case PLACEHOLDER -> renderStatus(graphics, content, controls, row, pauseId, executor.uuid(),
                idPrefix + "slot-" + bounds.y() + "-", hidden);
            case EMPTY -> text(graphics, Component.translatable("codon.nbt.empty").getString(), content, MUTED);
        }
    }

    private void renderNode(GuiGraphicsExtractor graphics, Bounds bounds, Bounds content, int mouseX, int mouseY,
                            Controls controls, ClientNbtState.Row row, long pauseId, EntityRef executor, String idPrefix, boolean current) {
        NbtPage.Node node = row.node();
        if (node == null) return;
        String prefix = node.expandable() ? (row.expanded() ? "▾ " : "▸ ") : "  ";
        Component label = Component.literal(prefix + node.name() + ": " + node.preview());
        String nodeId = nodeId(node);
        controls.button(idPrefix + "node-" + nodeId, content, label, node.expandable(), false,
            () -> toggleNode(pauseId, executor.uuid(), node.path())).withInputBlocked(!current);
        if (content.contains(mouseX, mouseY)) {
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
            () -> togglePin(pauseId, executor.uuid(), node.path())).withInputBlocked(!current);
        pin.withIcon(DebuggerIcon.PIN).withSecondaryAction(() -> toggleAllPins(pauseId, executor.uuid(), node.path()));
        Component pinTooltip;
        if (spec == null) pinTooltip = Component.translatable("codon.nbt.path_unavailable");
        else {
            Component left = Component.translatable(present ? "codon.nbt.click_unpin" : "codon.nbt.click_pin", executor.name());
            List<WatchSpec> all = allSpecs(node.path());
            boolean allPinned = !all.isEmpty() && all.stream().allMatch(specification -> state.watches().findId(specification) > 0);
            Component right = Component.translatable(allPinned ? "codon.nbt.click_all_remove" : "codon.nbt.click_all", all.size());
            pinTooltip = left.copy().append("\n").append(right);
        }
        pin.setTooltip(Tooltip.create(pinTooltip.copy().append("\n").append(node.path())));
    }

    private void toggleNode(long pauseId, UUID executor, String path) {
        if (currentNode(pauseId, executor, path) == null) return;
        List<ClientNbtState.Row> rows = state.nbt().rows(executor);
        for (int index : ClientNbtState.loadedIndices(rows)) {
            if (path.equals(rows.get(index).path())) {
                anchorPath = path;
                anchorRow = index - offset;
                anchorViewport = scrollBounds;
                break;
            }
        }
        state.nbt().toggle(executor, path);
    }

    private void renderStatus(GuiGraphicsExtractor graphics, Bounds content, Controls controls, ClientNbtState.Row row,
                              long pauseId, UUID executor, String idPrefix, int hidden) {
        Component label = row.status() == null ? Component.translatable("codon.nbt.pending") : WatchFormatting.status(row.status());
        if (hidden > 0) label = Component.literal("+" + hidden)
            .append(row.status() == null ? Component.empty() : Component.literal(" · ").append(label));
        text(graphics, label.getString(), new Bounds(content.x(), content.y(), Math.max(1, content.width() - (row.status() == null ? 0 : 48)), content.height()),
            row.status() == null ? MUTED : RED);
        if (row.status() == null) return;
        Bounds refresh = new Bounds(content.x() + Math.max(0, content.width() - 44), content.y(), 44, content.height());
        controls.button(idPrefix + "refresh-" + stable(row.path()), refresh, Component.translatable("codon.nbt.refresh"),
            true, false,
            () -> {
                if (isCurrent(pauseId, executor) && state.nbt().canRefresh(executor, row.path(), row.targetOffset()))
                    state.nbt().refresh(executor);
            }).withInputBlocked(!state.nbt().canRefresh(executor, row.path(), row.targetOffset()));
    }

    private void togglePin(long pauseId, UUID executor, String path) {
        NbtPage.Node node = currentNode(pauseId, executor, path);
        WatchSpec spec = node == null ? null : pinnableSpec(node, executor);
        if (spec == null) return;
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
        NbtPage.Node node = currentNode(pauseId, executor, path);
        if (node == null || pinnableSpec(node, executor) == null) return;
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
        return state.watches().findId(spec);
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
        var source = selectedSource();
        return pauseId > 0 && pauseId == pauseId() && source != null && source.executor().uuid().equals(executor);
    }

    private NbtPage.@Nullable Node currentNode(long pauseId, UUID executor, String path) {
        if (!isCurrent(pauseId, executor)) return null;
        List<ClientNbtState.Row> rows = state.nbt().rows(executor);
        for (int index : ClientNbtState.loadedIndices(rows)) {
            var row = rows.get(index);
            if (row.kind() == ClientNbtState.Kind.NODE && row.path().equals(path)) return row.node();
        }
        return null;
    }

    private static String stable(String path) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(path.getBytes(StandardCharsets.UTF_8));
    }

    private static void text(GuiGraphicsExtractor graphics, String value, Bounds bounds, int color) {
        var font = Minecraft.getInstance().font;
        String rendered = font.width(value) <= bounds.width() ? value
            : font.plainSubstrByWidth(value, Math.max(0, bounds.width() - font.width("…"))) + "…";
        graphics.enableScissor(bounds.x(), bounds.y(), bounds.x() + bounds.width(), bounds.y() + bounds.height());
        graphics.text(font, rendered, bounds.x() + 2, bounds.y() + 4, DebuggerTheme.color(color), false);
        graphics.disableScissor();
    }

    @FunctionalInterface
    public interface Controls {
        DebuggerButton button(String id, Bounds bounds, Component label, boolean active, boolean selected, Runnable action);
    }
}
