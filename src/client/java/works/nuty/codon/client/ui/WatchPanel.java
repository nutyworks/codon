package works.nuty.codon.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientWatchState;
import works.nuty.codon.client.state.WatchGrouping;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import works.nuty.codon.client.ui.layout.WatchPanelLayout;
import works.nuty.codon.core.model.EntityRef;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static works.nuty.codon.client.ui.DebuggerTheme.*;
import static works.nuty.codon.client.ui.WatchUi.text;

/** Stable, directly manageable Watches window shared by the HUD and cursor screen. */
public final class WatchPanel {
    private static final Bounds EMPTY = new Bounds(0, 0, 0, 0);
    private static final int HEADER = 23;
    private static final int BOTTOM_PADDING = 3;
    private static final int ACTION_SIZE = 16;
    private static final int KIND_ICON_INSET = 11;
    private static final int DIVIDER_MARGIN = 2;
    private static final int DIVIDER_TOP_MARGIN = 1;
    private record Row(long key, ClientWatchState.@Nullable Entry entry, Component heading, boolean grouped,
                       @Nullable DebuggerIcon icon, boolean noExecutor, boolean muted) {
        boolean showScope() {
            return entry != null && !grouped && !noExecutor && entry.spec().kind() != WatchSpec.Kind.STORAGE_NBT;
        }
    }
    private final ClientDebuggerState state;
    private final Map<String, DebuggerButton> buttons = new HashMap<>();
    private final List<DebuggerButton> controls = new ArrayList<>();
    private final Set<String> used = new HashSet<>();
    private Bounds bounds = EMPTY;
    private Bounds scrollBounds = EMPTY;
    private int offset;
    private int maximum;
    private long selectedId;
    private long requestedId;
    private long revealRevision;
    private long highlightedUntil;
    private final Map<WatchGrouping.Key, Long> headingKeys = new HashMap<>();
    private long nextHeadingKey = Long.MIN_VALUE;
    private List<Long> previousKeys = List.of();
    private Component notice = Component.empty();
    private long noticeUntil;
    private ClientWatchState.@Nullable Removed removedWatch;
    private long undoUntil;
    /** A header-local menu leaves the watch viewport and its scroll anchor untouched. */
    private boolean groupingMenuOpen;
    private Bounds groupingTriggerBounds = EMPTY;
    private Bounds groupingMenuBounds = EMPTY;
    private final List<DebuggerButton> groupingChoices = new ArrayList<>();

    public WatchPanel(ClientDebuggerState state) { this.state = state; }
    public Bounds bounds() { return bounds; }
    public Bounds scrollBounds() { return scrollBounds; }
    public int offset() { return offset; }
    public void clearBounds() {
        bounds = scrollBounds = groupingTriggerBounds = groupingMenuBounds = EMPTY;
        groupingChoices.clear();
    }
    public boolean groupingMenuOpen() { return groupingMenuOpen && groupingMenuBounds.width() > 0; }
    public boolean groupingTriggerContains(double x, double y) { return groupingTriggerBounds.contains(x, y); }
    public boolean groupingMenuContains(double x, double y) { return groupingMenuBounds.contains(x, y); }
    public void closeGroupingMenu() { groupingMenuOpen = false; }
    public boolean isGroupingChoice(DebuggerButton button) { return groupingChoices.contains(button); }

    public @Nullable DebuggerButton groupingChoiceAt(double x, double y) {
        if (!groupingMenuOpen() || !groupingMenuBounds.contains(x, y)) return null;
        return groupingChoices.stream().filter(button -> button.isMouseOver(x, y)).findFirst().orElse(null);
    }

    public void paintGroupingMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                  float partialTick, boolean interactive) {
        if (!groupingMenuOpen()) return;
        graphics.fill(groupingMenuBounds.x(), groupingMenuBounds.y(),
            groupingMenuBounds.x() + groupingMenuBounds.width(),
            groupingMenuBounds.y() + groupingMenuBounds.height(), PANEL);
        graphics.outline(groupingMenuBounds.x(), groupingMenuBounds.y(), groupingMenuBounds.width(),
            groupingMenuBounds.height(), BORDER);
        for (DebuggerButton button : groupingChoices) {
            if (!interactive) button.setFocused(false);
            button.extractRenderState(graphics, interactive ? mouseX : -1, interactive ? mouseY : -1, partialTick);
        }
    }
    public void select(long id) { selectedId = requestedId = id; }
    public void notice(Component text) { notice = text; noticeUntil = System.nanoTime() + 4_000_000_000L; }

    public List<DebuggerButton> render(GuiGraphicsExtractor graphics, Bounds available, int mouseX, int mouseY,
                                       boolean interactive, InputManager input, DebuggerOverlay overlay,
                                       DebuggerNavigation navigation, ScrollbarInput scrollbars) {
        controls.clear(); used.clear(); clearBounds();
        var client = Minecraft.getInstance();
        var font = client.font;
        if (available.width() < 72 || available.height() < HEADER + BOTTOM_PADDING) return List.of();
        boolean undoAvailable = removedWatch != null && removedWatch.generation() == state.watches().generation()
            && System.nanoTime() < undoUntil;
        if (!undoAvailable) removedWatch = null;
        int bodyStart = HEADER + (undoAvailable ? 18 : 0);
        var entries = state.watches().displayedEntries();
        List<Row> rows = rows(entries);
        long firstUngroupedId = state.watches().grouping() == WatchGrouping.Mode.NONE ? 0
            : rows.stream().filter(row -> row.entry() != null && !row.grouped())
                .mapToLong(Row::key).findFirst().orElse(0);
        List<Integer> topMargins = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            boolean afterDivider = rows.get(index).key() == firstUngroupedId || rows.get(index).entry() == null;
            topMargins.add(afterDivider ? DIVIDER_TOP_MARGIN + DIVIDER_MARGIN : 0);
        }
        var rowLayout = new WatchPanelLayout.Rows(rows.stream().map(row -> !row.showScope()).toList(), topMargins);
        int viewportHeight = Math.max(0, available.height() - bodyStart - BOTTOM_PADDING);
        maximum = rowLayout.maximumOffset(viewportHeight);
        List<Long> keys = rows.stream().map(Row::key).toList();
        // Preserve a scrolled viewport, but keep the top visible when a quiet first row moves down.
        if (!keys.equals(previousKeys) && offset > 0 && offset < previousKeys.size()) {
            int anchor = keys.indexOf(previousKeys.get(offset));
            if (anchor >= 0) offset = anchor;
        }
        previousKeys = keys;
        offset = Math.clamp(offset, 0, maximum);
        boolean focusRequested = false;
        if (revealRevision != state.watches().revealRevision()) {
            revealRevision = state.watches().revealRevision();
            select(state.watches().revealId());
        }
        if (requestedId != 0) {
            int row = keys.indexOf(requestedId);
            if (row >= 0) {
                offset = Math.min(maximum, rowLayout.reveal(row, offset, viewportHeight));
                highlightedUntil = System.nanoTime() + 2_000_000_000L;
                focusRequested = true;
            }
            requestedId = 0;
        }
        int displayed = rowLayout.visibleEnd(offset, viewportHeight) - offset;
        int totalHeight = rowLayout.height(0, rows.size());
        int contentHeight = rows.isEmpty() ? 27 : Math.min(viewportHeight, totalHeight);
        int panelHeight = Math.min(available.height(), bodyStart + contentHeight + BOTTOM_PADDING);
        bounds = new Bounds(available.x(), available.y(), available.width(), panelHeight);
        scrollBounds = new Bounds(bounds.x() + 3, bounds.y() + bodyStart, bounds.width() - 6,
            Math.max(0, panelHeight - bodyStart - BOTTOM_PADDING));
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + bounds.width(), bounds.y() + bounds.height(), DebuggerTheme.color(PANEL));
        graphics.outline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), DebuggerTheme.color(BORDER));
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + 2, bounds.y() + HEADER, DebuggerTheme.color(TEAL));
        WatchUi.line(graphics, font, text("title").getString(),
            bounds.x() + 7, bounds.y() + (HEADER - font.lineHeight) / 2 + 1, Math.max(0, bounds.width() - 74), TEAL);
        button("watch-add", new Bounds(bounds.x() + bounds.width() - 23, bounds.y() + 3, 18, 17), Component.literal("+"),
            true, false, () -> client.gui.setScreen(new WatchScreen(input, state, overlay)), navigation, -4, 3)
            .withIcon(DebuggerIcon.WATCHES).withIconOffsetY(2).withSingleLineTooltip(text("keep"));
        groupingTriggerBounds = new Bounds(bounds.x() + bounds.width() - 44, bounds.y() + 3, 18, 17);
        button("watch-grouping", groupingTriggerBounds,
            text("grouping." + state.watches().grouping().name().toLowerCase(java.util.Locale.ROOT)), true, false,
            () -> groupingMenuOpen = !groupingMenuOpen, navigation, -4, 1).withIcon(DebuggerIcon.GIZMO_GROUPED).withIconOffsetY(2)
                .withSingleLineTooltip(text("grouping.tooltip." + state.watches().grouping().name().toLowerCase(java.util.Locale.ROOT)));
        if (groupingMenuOpen) {
            int menuWidth = Math.min(112, Math.max(76, bounds.width() - 12));
            int menuX = bounds.x() + bounds.width() - menuWidth - 5;
            int menuY = bounds.y() + HEADER - 1;
            int menuHeight = WatchGrouping.Mode.values().length * 18 + 4;
            groupingMenuBounds = new Bounds(menuX, menuY, menuWidth, menuHeight);
            for (WatchGrouping.Mode mode : WatchGrouping.Mode.values()) {
                int y = menuY + 2 + mode.ordinal() * 18;
                boolean selected = mode == state.watches().grouping();
                Component label = selected
                    ? Component.literal("• ").append(text("grouping." + mode.name().toLowerCase(java.util.Locale.ROOT)))
                    : text("grouping." + mode.name().toLowerCase(java.util.Locale.ROOT));
                groupingChoices.add(button("watch-grouping-" + mode.name(), new Bounds(menuX + 2, y, menuWidth - 4, 16), label, true, selected,
                    () -> {
                        state.watches().grouping(mode);
                        groupingMenuOpen = false;
                    }, navigation, -3 + mode.ordinal(), 0).withSingleLineTooltip(text("grouping.tooltip."
                    + mode.name().toLowerCase(java.util.Locale.ROOT))).withOpaqueColors());
            }
        }
        var save = state.watches().saveStatus();
        if (save == ClientWatchState.SaveStatus.FAILED) {
            button("watch-save-retry", new Bounds(bounds.x() + bounds.width() - 65, bounds.y() + 3, 18, 17), text("save.retry"),
                true, false, () -> state.watches().retrySave(), navigation, -4, 2).withIcon(DebuggerIcon.WARNING)
                .setTooltip(Tooltip.create(text("save.failed")));
        }
        if (undoAvailable) {
            button("watch-undo", new Bounds(bounds.x() + 4, bounds.y() + HEADER, bounds.width() - 8, 17),
                text("undo_deleted"), true, false, () -> {
                    ClientWatchState.Removed removed = removedWatch;
                    if (removed != null && state.watches().restore(removed)) select(removed.id());
                    removedWatch = null;
                }, navigation, -2, 0);
        }
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (row.entry() == null) continue;
            final int logicalRow = index;
            Runnable reveal = () -> offset = Math.min(maximum, rowLayout.reveal(logicalRow, offset, viewportHeight));
            var entry = row.entry();
            navigation.add("watch-row-" + entry.id(), DebuggerNavigation.Group.WATCH, index, 0, reveal);
            if (entry.automatic()) {
                navigation.add("watch-copy-" + entry.id(), DebuggerNavigation.Group.WATCH, index, 3, reveal);
                navigation.add("watch-keep-" + entry.id(), DebuggerNavigation.Group.WATCH, index, 4, reveal);
            }
            else {
                if (entry.spec().kind() != WatchSpec.Kind.STORAGE_NBT && (entry.spec().isPinned() || executor(entry) != null))
                    navigation.add("watch-pin-" + entry.id(), DebuggerNavigation.Group.WATCH, index, 1, reveal);
                navigation.add("watch-copy-" + entry.id(), DebuggerNavigation.Group.WATCH, index, 2, reveal);
                navigation.add("watch-edit-" + entry.id(), DebuggerNavigation.Group.WATCH, index, 3, reveal);
                navigation.add("watch-remove-" + entry.id(), DebuggerNavigation.Group.WATCH, index, 4, reveal);
            }
        }
        if (rows.isEmpty()) WatchUi.line(graphics, font, text("empty").getString(), bounds.x() + 7, bounds.y() + bodyStart + 7, bounds.width() - 14, MUTED);
        for (int index = 0; index < displayed; index++) {
            Row row = rows.get(offset + index);
            int topMargin = topMargins.get(offset + index);
            int y = bounds.y() + bodyStart + rowLayout.height(offset, offset + index) + topMargin;
            int rowHeight = rowLayout.height(offset + index, offset + index + 1) - topMargin;
            if (row.entry() == null) {
                int dividerY = y - topMargin + DIVIDER_TOP_MARGIN;
                graphics.fill(bounds.x() + 5, dividerY, bounds.x() + bounds.width() - 5, dividerY + 1, DebuggerTheme.color(BORDER));
                int headingY = y + (rowHeight - font.lineHeight) / 2 + 1;
                int headingColor = row.muted() ? MUTED : TEAL;
                int inset = row.icon() == null ? 0 : KIND_ICON_INSET;
                if (row.icon() != null) row.icon().drawSmall(graphics, bounds.x() + 7, headingY, DebuggerTheme.color(headingColor));
                WatchUi.line(graphics, font, row.heading().getString(), bounds.x() + 7 + inset,
                    headingY, bounds.width() - 14 - inset, headingColor);
                continue;
            }
            var entry = row.entry();
            int rowWidth = Math.max(1, bounds.width() - 86);
            if (entry.id() == selectedId && System.nanoTime() < highlightedUntil)
                graphics.fill(bounds.x() + 3, y, bounds.x() + bounds.width() - 6, y + rowHeight - 1, DebuggerTheme.color(TEAL_SURFACE));
            if (entry.id() == firstUngroupedId) {
                int dividerY = y - topMargin + DIVIDER_TOP_MARGIN;
                graphics.fill(bounds.x() + 5, dividerY, bounds.x() + bounds.width() - 5, dividerY + 1, DebuggerTheme.color(BORDER));
            }
            boolean changed = entry.displayedChange().isValueChange();
            int lineSpacing = font.lineHeight + 3;
            int textHeight = font.lineHeight + (row.showScope() ? lineSpacing : 0);
            // Match button text centering, including the font's baseline adjustment.
            int textY = y + (rowHeight - textHeight) / 2 + 1;
            int labelColor = row.muted() ? MUTED : TEXT;
            int kindInset = row.icon() == null ? 0 : KIND_ICON_INSET;
            if (row.icon() != null) row.icon().drawSmall(graphics, bounds.x() + 7, textY, DebuggerTheme.color(labelColor));
            WatchRowRenderer.render(graphics, font, entry, state.isPaused(), rowLabel(entry, row.grouped()),
                bounds.x() + 7 + kindInset, textY, rowWidth - 4 - kindInset,
                labelColor, row.muted() ? MUTED : changed ? AMBER : TEXT);
            if (row.showScope())
                WatchUi.line(graphics, font, scope(entry), bounds.x() + 7 + kindInset,
                    textY + lineSpacing, rowWidth - 4 - kindInset, MUTED);
            DebuggerButton inspect = button("watch-row-" + entry.id(), new Bounds(bounds.x() + 4, y, rowWidth + 3, rowHeight - 1),
                text("inspect", WatchFormatting.specification(entry.spec()).getString()), true, entry.id() == selectedId,
                () -> { selectedId = entry.id(); client.gui.setScreen(new WatchDetailsScreen(input, state, overlay, entry.id())); }, navigation, offset + index, 0)
                .asHitSurface();
            inspect.setTooltip(Tooltip.create(text("section." + entry.spec().kind().name().toLowerCase(java.util.Locale.ROOT))
                .copy().append(" · ").append(text("inspect", WatchFormatting.specification(entry.spec()).getString()))
                .append("\n").append(scope(entry))));
            int actionX = bounds.x() + bounds.width() - 76;
            int actionY = y + (rowHeight - ACTION_SIZE) / 2;
            if (entry.automatic()) {
                button("watch-copy-" + entry.id(), new Bounds(actionX + 34, actionY, ACTION_SIZE, ACTION_SIZE), text("details.copy_value"), true, false,
                    () -> copyValue(entry), navigation, offset + index, 3)
                    .withSmallIcon(DebuggerIcon.COPY_UUID).withSingleLineTooltip(text("details.copy_value"));
                button("watch-keep-" + entry.id(), new Bounds(actionX + 51, actionY, ACTION_SIZE, ACTION_SIZE), text("keep"), true, false,
                    () -> {
                        if (state.watches().pinChange(entry.id())) notice(text("feedback.added", WatchFormatting.specification(entry.spec()).getString()));
                    }, navigation, offset + index, 4).withIcon(DebuggerIcon.WATCHES)
                    .withSingleLineTooltip(text("keep"));
            } else {
                if (entry.spec().kind() != WatchSpec.Kind.STORAGE_NBT) {
                    boolean pinned = entry.spec().isPinned();
                    EntityRef target = executor(entry);
                    String targetLabel = target == null ? text("status.no_executor").getString()
                        : target.name() + " #" + target.uuid().toString().substring(0, 8);
                    button("watch-pin-" + entry.id(), new Bounds(actionX, actionY, ACTION_SIZE, ACTION_SIZE), text(pinned ? "unpin" : "pin"),
                        pinned || executor(entry) != null, pinned, () -> togglePin(entry.id()), navigation, offset + index, 1)
                        .withSmallIcon(DebuggerIcon.PIN).withSingleLineTooltip(pinned ? text("unpin")
                            : text("tooltip.pin_context", targetLabel));
                }
                button("watch-copy-" + entry.id(), new Bounds(actionX + 17, actionY, ACTION_SIZE, ACTION_SIZE), text("details.copy_value"), true, false,
                    () -> copyValue(entry), navigation, offset + index, 2)
                    .withSmallIcon(DebuggerIcon.COPY_UUID).withSingleLineTooltip(text("details.copy_value"));
                button("watch-edit-" + entry.id(), new Bounds(actionX + 34, actionY, ACTION_SIZE, ACTION_SIZE), text("edit"), true, false,
                    () -> client.gui.setScreen(WatchScreen.edit(input, state, overlay, entry.id())),
                    navigation, offset + index, 3).withSmallIcon(DebuggerIcon.EDIT).withSingleLineTooltip(text("edit"));
                button("watch-remove-" + entry.id(), new Bounds(actionX + 51, actionY, ACTION_SIZE, ACTION_SIZE), text("remove"), true, false,
                    () -> {
                        removedWatch = state.watches().removeForUndo(entry.id());
                        undoUntil = System.nanoTime() + 8_000_000_000L;
                        notice(text("feedback.removed", WatchFormatting.specification(entry.spec()).getString()));
                    },
                    navigation, offset + index, 4).withSmallIcon(DebuggerIcon.REMOVE).withSingleLineTooltip(text("remove"));
            }
        }
        if (focusRequested) navigation.requestFocus("watch-row-" + selectedId);
        if (maximum > 0 && scrollBounds.height() > 0 && displayed > 0) {
            int h = scrollBounds.height(), thumb = Math.min(h, Math.max(8, h * h / totalHeight));
            int x = bounds.x() + bounds.width() - 4;
            graphics.fill(x, scrollBounds.y(), x + 2, scrollBounds.y() + h, DebuggerTheme.color(BORDER));
            int y = scrollBounds.y() + (h - thumb) * offset / maximum;
            graphics.fill(x, y, x + 2, y + thumb, DebuggerTheme.color(TEAL));
            scrollbars.add("watch", false, x, scrollBounds.y(), h, 2, thumb, offset, maximum, value -> offset = value);
        }
        if (interactive && System.nanoTime() < noticeUntil && mouseX >= bounds.x()
            && mouseX < bounds.x() + bounds.width() - 48 && mouseY >= bounds.y() && mouseY < bounds.y() + HEADER)
            graphics.setTooltipForNextFrame(font, notice, mouseX, mouseY);
        buttons.keySet().retainAll(used);
        return List.copyOf(controls);
    }

    private List<Row> rows(List<ClientWatchState.Entry> entries) {
        List<Row> result = new ArrayList<>();
        EntityRef current = WatchUi.currentEntity(state);
        Set<WatchGrouping.Key> usedHeadings = new HashSet<>();
        for (var group : WatchGrouping.groups(entries, state.watches().grouping(), current == null ? null : current.uuid())) {
            boolean grouped = state.watches().grouping() != WatchGrouping.Mode.NONE && group.entries().size() > 1;
            if (grouped) {
                usedHeadings.add(group.key());
                long key = headingKeys.computeIfAbsent(group.key(), ignored -> nextHeadingKey++);
                Component heading;
                DebuggerIcon headingIcon = null;
                if (group.key().category().equals("storage")) {
                    heading = Component.literal(group.key().value());
                    headingIcon = DebuggerIcon.WATCH_STORAGE;
                } else if (state.watches().grouping() == WatchGrouping.Mode.PATH) {
                    heading = Component.literal(expression(group.entries().getFirst().spec(), false));
                    headingIcon = kindIcon(group.entries().getFirst().spec());
                } else if (group.key().category().equals("score-holder")) {
                    heading = Component.literal(group.key().value());
                } else if (group.key().value().isEmpty()) {
                    heading = text("status.no_executor");
                } else {
                    String name = group.entries().stream().map(ClientWatchState.Entry::executorName)
                        .filter(value -> !value.isBlank()).findFirst().orElse("");
                    if (name.isBlank() && current != null && current.uuid().toString().equals(group.key().value())) name = current.name();
                    heading = Component.literal((name.isBlank() ? "" : name + " #")
                        + (name.isBlank() ? group.key().value() : group.key().value().substring(0, 8)));
                }
                result.add(new Row(key, null, heading, true, headingIcon, false,
                    group.entries().stream().allMatch(WatchGrouping::isUnchangedMissing)));
            }
            group.entries().forEach(entry -> result.add(new Row(entry.id(), entry, Component.empty(), grouped,
                grouped && state.watches().grouping() == WatchGrouping.Mode.PATH
                    && entry.spec().kind() != WatchSpec.Kind.STORAGE_NBT ? null : kindIcon(entry.spec()),
                noExecutor(entry), WatchGrouping.isUnchangedMissing(entry))));
        }
        headingKeys.keySet().retainAll(usedHeadings);
        return result;
    }

    private String rowLabel(ClientWatchState.Entry entry, boolean grouped) {
        var mode = grouped ? state.watches().grouping() : WatchGrouping.Mode.NONE;
        if (grouped && entry.spec().kind() == WatchSpec.Kind.STORAGE_NBT) return entry.spec().path();
        if (mode == WatchGrouping.Mode.PATH) {
            String name = WatchFormatting.executorLabel(entry);
            EntityRef target = executor(entry);
            if (name.isEmpty() && target != null) name = target.name() + " #" + target.uuid().toString().substring(0, 8);
            return name.isEmpty() ? text("editor.no_entity").getString() : name;
        }
        String expression = expression(entry.spec(), mode == WatchGrouping.Mode.CONTEXT);
        return entry.spec().scoreHolder() != null && mode != WatchGrouping.Mode.CONTEXT
            ? entry.spec().scoreHolder() + " · " + expression : expression;
    }

    private String expression(WatchSpec spec, boolean omitStorageId) {
        return omitStorageId && spec.kind() == WatchSpec.Kind.STORAGE_NBT
            ? spec.path() : WatchFormatting.specification(spec).getString();
    }

    private static DebuggerIcon kindIcon(WatchSpec spec) {
        return switch (spec.kind()) {
            case ENTITY_NBT -> DebuggerIcon.WATCH_NBT;
            case STORAGE_NBT -> DebuggerIcon.WATCH_STORAGE;
            case SCORE -> DebuggerIcon.WATCH_SCORE;
        };
    }

    private boolean noExecutor(ClientWatchState.Entry entry) {
        return entry.spec().kind() != WatchSpec.Kind.STORAGE_NBT && entry.spec().scoreHolder() == null && (executor(entry) == null
            || entry.displayedResult() != null && entry.displayedResult().status() == WatchResult.Status.NO_EXECUTOR);
    }

    private String scope(ClientWatchState.Entry entry) {
        if (entry.spec().kind() == WatchSpec.Kind.STORAGE_NBT) return text("scope.storage").getString();
        String name = WatchFormatting.executorLabel(entry);
        if (name.isEmpty()) {
            EntityRef current = executor(entry);
            if (current == null) return text("editor.no_entity").getString();
            name = current.name() + " #" + current.uuid().toString().substring(0, 8);
        }
        String key = entry.completedStep() != null ? "scope.previous" : entry.automatic() ? "scope.changed"
            : entry.spec().isPinned() ? "scope.fixed" : "scope.context";
        return text(key, name).getString();
    }

    private @Nullable EntityRef executor(ClientWatchState.Entry entry) {
        if (entry.spec().scoreHolder() != null) return null;
        if (entry.displayedExecutor() != null) return new EntityRef(entry.displayedExecutor(), entry.executorName().isBlank()
            ? entry.displayedExecutor().toString() : entry.executorName());
        return WatchUi.currentEntity(state);
    }

    private void togglePin(long id) {
        var entry = state.watches().displayedEntries().stream().filter(row -> row.id() == id).findFirst().orElse(null);
        if (entry == null) return;
        EntityRef target = executor(entry);
        boolean changed = entry.spec().isPinned() ? state.watches().unpin(id) : target != null && state.watches().pin(id, target);
        if (!changed) notice(text("feedback.duplicate"));
    }

    private void copyValue(ClientWatchState.Entry entry) {
        Minecraft.getInstance().keyboardHandler.setClipboard(WatchFormatting.latestValue(entry, state.isPaused()).getString());
        notice(text("details.copy_value"));
    }

    private DebuggerButton button(String id, Bounds b, Component label, boolean active, boolean selected, Runnable action,
                                  DebuggerNavigation navigation, int row, int column) {
        DebuggerButton button = buttons.computeIfAbsent(id, ignored -> new DebuggerButton());
        button.configure(b.x(), b.y(), b.width(), b.height(), label, active, selected, false, false, action);
        button.withoutChrome();
        if (selected) button.withStatusColor(TEAL, TEAL_SURFACE);
        used.add(id); controls.add(button);
        // Logical rows were registered for hidden entries; bind only the controls in the viewport.
        if (row < 0) navigation.add(id, DebuggerNavigation.Group.WATCH, row, column, () -> { });
        navigation.bind(id, DebuggerNavigation.Group.WATCH, button);
        return button;
    }

    public boolean scroll(double x, double y, double amount) {
        if (!scrollBounds.contains(x, y) || amount == 0) return false;
        offset = Math.clamp(offset + (amount > 0 ? -1 : 1), 0, maximum);
        return true;
    }
}
