package works.nuty.codon.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientWatchState;
import works.nuty.codon.client.state.WatchGrouping;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import works.nuty.codon.client.ui.layout.WatchExpansionLayout;
import works.nuty.codon.client.ui.layout.WatchPanelLayout;
import works.nuty.codon.core.model.EntityRef;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static works.nuty.codon.client.ui.DebuggerTheme.*;
import static works.nuty.codon.client.ui.WatchUi.text;

/** Stable, directly manageable Watches window shared by the HUD and cursor screen. */
public final class WatchPanel {
    private static final Bounds EMPTY = new Bounds(0, 0, 0, 0);
    private static final int HEADER = 23;
    private static final int BOTTOM_PADDING = 3;
    private static final int LABEL_HEIGHT = 16;
    private static final int KIND_ICON_INSET = 11;
    private static final int DIVIDER_MARGIN = 2;
    private static final int DIVIDER_TOP_MARGIN = 1;
    private record Row(long key, ClientWatchState.@Nullable Entry entry, Component heading, boolean grouped,
                       @Nullable DebuggerIcon icon, boolean noExecutor, boolean muted, int groupSize, int changedCount, int issueCount) {
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
    private final Map<Long, Bounds> rowBounds = new LinkedHashMap<>();
    private long contextMenuId;
    private int offset;
    private int maximum;
    private long selectedId;
    private long requestedId;
    private long revealRevision;
    private long highlightedUntil;
    private final Map<WatchGrouping.Key, Long> headingKeys = new HashMap<>();
    private final Set<Long> collapsedHeadings = new HashSet<>();
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
    /** At most one row shows its details in place; the list scrolls them with that row. */
    private long expandedId;
    private boolean revealExpanded;
    private int expansionScroll;
    private int expansionVisibleLines;
    private int expansionMaxScroll;
    private Bounds expansionTextBounds = EMPTY;
    private record Action(String id, Component label, boolean active, Runnable run) { }

    private final WatchComposer composer;

    public WatchPanel(ClientDebuggerState state) {
        this.state = state;
        composer = new WatchComposer(state);
    }

    @Nullable EditBox composerBox() { return composer.box(); }
    boolean composerOpen() { return composer.open(); }
    boolean composerFocused() { return composer.focused(); }
    void refocusComposer() { composer.requestFocus(); }
    boolean takeComposerFocusRequest() { return composer.takeFocusRequest(); }
    boolean composerKey(KeyEvent event) { return composer.keyPressed(event, this); }

    /** Page Up/Down and Home/End scroll the expanded row's details while its row or an action has focus. */
    boolean expansionKey(KeyEvent event, net.minecraft.client.gui.components.events.@Nullable GuiEventListener focused) {
        if (expandedId == 0 || expansionMaxScroll == 0 || focused == null) return false;
        boolean owned = buttons.get("watch-row-" + expandedId) == focused || buttons.get("watch-value-" + expandedId) == focused
            || buttons.entrySet().stream().anyMatch(e -> e.getKey().startsWith("watch-details-") && e.getValue() == focused);
        if (!owned) return false;
        int page = Math.max(1, expansionVisibleLines - 1);
        int next;
        switch (event.key()) {
            case InputConstants.KEY_PAGEUP -> next = expansionScroll - page;
            case InputConstants.KEY_PAGEDOWN -> next = expansionScroll + page;
            case InputConstants.KEY_HOME -> next = 0;
            case InputConstants.KEY_END -> next = expansionMaxScroll;
            default -> { return false; }
        }
        expansionScroll = Math.clamp(next, 0, expansionMaxScroll);
        return true;
    }
    void closeComposer() { composer.close(); }

    /** Add (id &lt; 0) or edit a watch in the row under the header; cursor mode owns its text input. */
    public void beginComposer(long id, InputManager input, DebuggerOverlay overlay) {
        var client = Minecraft.getInstance();
        if (!(client.gui.screen() instanceof CodonScreen)) client.gui.setScreen(new CodonScreen(input, overlay));
        closeGroupingMenu();
        overlay.revealWatches();
        composer.begin(id);
    }
    /** Header plus the empty-state row, so a docked section is never reduced to a title bar. */
    static final int MINIMUM_HEIGHT = HEADER + 27 + BOTTOM_PADDING;

    /** Approximate height showing every Watch row, for the dock to clamp to its space. */
    int preferredHeight() {
        int rows = Math.max(1, state.watches().displayedEntries().size());
        return HEADER + (composer.open() ? 45 : 0) + rows * 28 + (expandedId != 0 ? 80 : 0) + BOTTOM_PADDING;
    }

    public Bounds bounds() { return bounds; }
    public Bounds scrollBounds() { return scrollBounds; }
    public int offset() { return offset; }
    public void clearBounds() {
        bounds = scrollBounds = groupingTriggerBounds = groupingMenuBounds = expansionTextBounds = EMPTY;
        groupingChoices.clear();
        rowBounds.clear();
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
    public long expandedId() { return expandedId; }

    /** Selecting a row shows its details under it; selecting it again collapses them. */
    public int expansionScroll() { return expansionScroll; }
    public int expansionMaxScroll() { return expansionMaxScroll; }

    public void toggleExpanded(long id) {
        expandedId = expandedId == id ? 0 : id;
        expansionScroll = 0;
        revealExpanded = expandedId != 0;
    }
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
        boolean changesRejected = state.watches().changesRejected();
        int composerTop = HEADER + (undoAvailable ? 18 : 0) + (changesRejected ? 18 : 0);
        int composerHeight = interactive ? composer.prepare(Math.max(0, available.height() - composerTop - BOTTOM_PADDING - 27)) : 0;
        int bodyStart = composerTop + composerHeight;
        if (revealRevision != state.watches().revealRevision()) {
            revealRevision = state.watches().revealRevision();
            select(state.watches().revealId());
        }
        var entries = state.watches().displayedEntries();
        List<Row> rows = rows(entries);
        long firstUngroupedId = state.watches().grouping() == WatchGrouping.Mode.NONE ? 0
            : rows.stream().filter(row -> row.entry() != null && !row.grouped())
                .mapToLong(Row::key).findFirst().orElse(0);
        List<Integer> topMargins = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            topMargins.add(row.entry() == null ? (index == 0 ? 2 : 6)
                : row.key() == firstUngroupedId ? DIVIDER_TOP_MARGIN + DIVIDER_MARGIN : 0);
        }
        boolean stackedValues = WatchPanelLayout.stackedValues(available.width());
        int viewportHeight = Math.max(0, available.height() - bodyStart - BOTTOM_PADDING);
        if (entries.stream().noneMatch(entry -> entry.id() == expandedId)) expandedId = 0;
        int expandedIndex = -1;
        for (int index = 0; index < rows.size(); index++)
            if (rows.get(index).entry() != null && rows.get(index).key() == expandedId) expandedIndex = index;
        int detailWidth = Math.max(1, available.width() - 24);
        List<FormattedCharSequence> detailLines = List.of();
        List<Action> actions = List.of();
        int expansionLimit = 0;
        WatchExpansionLayout expansion = null;
        List<Integer> expansions = new ArrayList<>(java.util.Collections.nCopies(rows.size(), 0));
        if (expandedIndex >= 0) {
            Row row = rows.get(expandedIndex);
            detailLines = details(row.entry()).stream().flatMap(line -> font.split(line, detailWidth).stream()).toList();
            actions = actions(row.entry(), input, overlay);
            expansionLimit = viewportHeight - topMargins.get(expandedIndex)
                - WatchPanelLayout.Rows.baseHeight(!row.showScope(), stackedValues);
            expansion = WatchExpansionLayout.create(0, 0, detailWidth, detailLines.size(), actionWidths(actions), expansionLimit);
            expansions.set(expandedIndex, expansion.height());
            if (expansion.height() == 0) {
                // Nothing fits under the row: register no action targets, so Down skips to the next Watch.
                actions = List.of();
                expansion = null;
                expansionMaxScroll = 0;
                expansionTextBounds = EMPTY;
            }
        }
        var rowLayout = new WatchPanelLayout.Rows(rows.stream().map(row -> !row.showScope()).toList(), topMargins,
            rows.stream().map(row -> stackedValues && row.entry() != null).toList(), expansions);
        maximum = rowLayout.maximumOffset(viewportHeight);
        List<Long> keys = rows.stream().map(Row::key).toList();
        // Preserve a scrolled viewport, but keep the top visible when a quiet first row moves down.
        if (!keys.equals(previousKeys) && offset > 0 && offset < previousKeys.size()) {
            int anchor = keys.indexOf(previousKeys.get(offset));
            if (anchor >= 0) offset = anchor;
        }
        previousKeys = keys;
        offset = Math.clamp(offset, 0, maximum);
        if (revealExpanded && expandedIndex >= 0) offset = Math.min(maximum, rowLayout.reveal(expandedIndex, offset, viewportHeight));
        revealExpanded = false;
        boolean focusRequested = false;
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
        // Reuse the header footprint so feedback never moves a scrolled Watch row.
        boolean noticeVisible = System.nanoTime() < noticeUntil;
        WatchUi.line(graphics, font, (noticeVisible ? notice : text("title")).getString(),
            bounds.x() + 7, bounds.y() + (HEADER - font.lineHeight) / 2 + 1,
            Math.max(0, bounds.width() - 74), noticeVisible ? TEAL : TEXT);
        button("watch-add", new Bounds(bounds.x() + bounds.width() - 23, bounds.y() + 3, 18, 17), Component.literal("+"),
            true, false, () -> {
                navigation.requestFocus("watch-add");
                beginComposer(-1, input, overlay);
            }, navigation, -4, 3)
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
                    }, navigation, -3 + mode.ordinal(), 0).withOpaqueColors());
            }
        }
        if (groupingMenuContains(mouseX, mouseY)) mouseX = mouseY = -1;
        var save = state.watches().saveStatus();
        if (save == ClientWatchState.SaveStatus.FAILED) {
            button("watch-save-retry", new Bounds(bounds.x() + bounds.width() - 65, bounds.y() + 3, 18, 17), text("save.retry"),
                true, false, () -> state.watches().retrySave(), navigation, -4, 2).withIcon(DebuggerIcon.WARNING)
                .setTooltip(Tooltip.create(text("save.failed")));
        } else if (save == ClientWatchState.SaveStatus.RESTORE_FAILED) {
            // Vanilla styles inactive labels gray; retain that style across toolbar reconfiguration
            // so the hover timer does not restart every frame.
            button("watch-restore-failed", new Bounds(bounds.x() + bounds.width() - 65, bounds.y() + 3, 18, 17),
                text("restore.failed").copy().withStyle(net.minecraft.ChatFormatting.GRAY), false, false, () -> {}, navigation, -4, 2)
                .withIcon(DebuggerIcon.WARNING)
                .setTooltip(Tooltip.create(text("restore.failed")));
        }
        if (undoAvailable) {
            button("watch-undo", new Bounds(bounds.x() + 4, bounds.y() + HEADER, bounds.width() - 8, 17),
                text("undo_deleted"), true, false, () -> {
                    ClientWatchState.Removed removed = removedWatch;
                    if (removed != null && state.watches().restore(removed)) select(removed.id());
                    removedWatch = null;
                }, navigation, -2, 0);
        }
        if (changesRejected) {
            String warning = text(state.watches().changesTooLarge() ? "changes.too_large" : "changes.unavailable").getString();
            WatchUi.line(graphics, font, warning, bounds.x() + 7,
                bounds.y() + HEADER + (undoAvailable ? 18 : 0) + 4, bounds.width() - 14, AMBER);
        }
        if (composerHeight > 0) {
            composer.paint(graphics, font, new Bounds(bounds.x(), bounds.y() + composerTop, bounds.width(), composerHeight),
                mouseX, mouseY, interactive);
            controls.addAll(composer.buttons());
        }
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            final int logicalRow = index;
            Runnable reveal = () -> offset = Math.min(maximum, rowLayout.reveal(logicalRow, offset, viewportHeight));
            if (row.entry() == null) {
                navigation.add("watch-header-" + row.key(), DebuggerNavigation.Group.WATCH, 2 * index, 0, reveal);
                continue;
            }
            var entry = row.entry();
            navigation.add("watch-row-" + entry.id(), DebuggerNavigation.Group.WATCH, 2 * index, 0, reveal);
            if (stackedValues)
                navigation.add("watch-value-" + entry.id(), DebuggerNavigation.Group.WATCH, 2 * index, 0, reveal);
            if (index == expandedIndex) for (int column = 0; column < actions.size(); column++)
                navigation.add(actions.get(column).id(), DebuggerNavigation.Group.WATCH, 2 * index + 1, column, reveal);
        }
        if (rows.isEmpty()) WatchUi.line(graphics, font, text("empty").getString(), bounds.x() + 7, bounds.y() + bodyStart + 7, bounds.width() - 14, MUTED);
        for (int index = 0; index < displayed; index++) {
            Row row = rows.get(offset + index);
            int topMargin = topMargins.get(offset + index);
            int y = bounds.y() + bodyStart + rowLayout.height(offset, offset + index) + topMargin;
            int extra = offset + index == expandedIndex ? expansions.get(expandedIndex) : 0;
            int rowHeight = rowLayout.height(offset + index, offset + index + 1) - topMargin - extra;
            if (row.entry() == null) {
                String id = "watch-header-" + row.key();
                boolean collapsed = collapsedHeadings.contains(row.key());
                Bounds header = new Bounds(bounds.x() + 4, y, bounds.width() - 10, rowHeight - 1);
                boolean hovered = interactive && header.contains(mouseX, mouseY);
                graphics.fill(header.x(), header.y(), header.x() + header.width(), header.y() + header.height(),
                    DebuggerTheme.color(keyboardFocused(id, interactive) ? DIVIDER : hovered ? BORDER : ROW_HOVER));
                int headingY = y + (rowHeight - font.lineHeight) / 2 + 1;
                int headingColor = row.muted() ? MUTED : TEXT;
                int inset = row.icon() == null ? 0 : KIND_ICON_INSET;
                WatchUi.line(graphics, font, collapsed ? "▸" : "▾", bounds.x() + 8, headingY, 8, MUTED);
                if (row.icon() != null) row.icon().drawSmall(graphics, bounds.x() + 19, headingY, DebuggerTheme.foreground(headingColor));
                String count = Integer.toString(row.groupSize())
                    + (row.changedCount() > 0 ? " · Δ" + row.changedCount() : "")
                    + (row.issueCount() > 0 ? " · !" + row.issueCount() : "");
                int countX = bounds.x() + bounds.width() - 12 - font.width(count);
                int headingWidth = Math.max(0, countX - bounds.x() - 25 - inset);
                WatchUi.line(graphics, font, row.heading().getString(), bounds.x() + 19 + inset,
                    headingY, headingWidth, headingColor);
                WatchUi.line(graphics, font, count, countX, headingY, font.width(count), row.issueCount() > 0 ? RED : row.changedCount() > 0 ? AMBER : MUTED);
                // The header draws its name and counts. Hover explains only a clipped name, or a collapsed
                // group that changed or failed to read while hidden. The label is the narration, so it names
                // any changes or read issues even while the group is expanded and hover stays quiet.
                Component action = text(collapsed ? "group.expand" : "group.collapse", row.heading().getString(), row.groupSize());
                Component summary = text("group.summary", row.changedCount(), row.issueCount());
                boolean activity = row.changedCount() > 0 || row.issueCount() > 0;
                Component label = activity ? action.copy().append("\n").append(summary) : action;
                var toggle = button(id, header, label, true, false, () -> {
                    if (!collapsedHeadings.add(row.key())) collapsedHeadings.remove(row.key());
                    navigation.requestFocus(id);
                }, navigation, 2 * (offset + index), 0).asHitSurface();
                if (font.width(row.heading().getString()) > headingWidth) toggle.setTooltip(Tooltip.create(label));
                else if (collapsed && activity) toggle.setTooltip(Tooltip.create(summary));
                continue;
            }
            var entry = row.entry();
            int rowWidth = Math.max(1, bounds.width() - 14);
            Bounds rowArea = new Bounds(bounds.x() + 4, y, bounds.width() - 10, rowHeight - 1);
            rowBounds.put(entry.id(), rowArea);
            boolean expanded = extra > 0;
            boolean highlighted = entry.id() == contextMenuId
                || entry.id() == selectedId && System.nanoTime() < highlightedUntil;
            boolean focused = keyboardFocused("watch-row-" + entry.id(), interactive)
                || keyboardFocused("watch-value-" + entry.id(), interactive);
            var inspectionBounds = WatchPanelLayout.inspectionBounds(bounds, y, rowHeight);
            boolean hovered = false;
            for (Bounds area : inspectionBounds) hovered |= interactive && area.contains(mouseX, mouseY);
            if (highlighted || focused || hovered)
                graphics.fill(bounds.x() + 3, y, bounds.x() + bounds.width() - 6, y + rowHeight - 1,
                    DebuggerTheme.color(highlighted ? TEAL_SURFACE : focused ? DIVIDER : ROW_HOVER));
            if (entry.id() == firstUngroupedId) {
                int dividerY = y - topMargin + DIVIDER_TOP_MARGIN;
                graphics.fill(bounds.x() + 5, dividerY, bounds.x() + bounds.width() - 5, dividerY + 1, DebuggerTheme.color(BORDER));
            }
            boolean changed = entry.displayedChange().isValueChange();
            int lineSpacing = font.lineHeight + 3;
            int textHeight = font.lineHeight + (row.showScope() ? lineSpacing : 0);
            // Match button text centering, including the font's baseline adjustment.
            int textY = stackedValues ? y + 1 + (LABEL_HEIGHT - font.lineHeight) / 2 + 1
                : y + (rowHeight - textHeight) / 2 + 1;
            int labelColor = row.muted() ? MUTED : TEXT;
            int kindInset = row.icon() == null ? 0 : KIND_ICON_INSET;
            if (row.icon() != null) row.icon().drawSmall(graphics, bounds.x() + 7, textY, DebuggerTheme.foreground(labelColor));
            int valueColor = row.muted() ? MUTED : changed ? AMBER : TEXT;
            boolean clipped = stackedValues
                ? WatchRowRenderer.renderStacked(graphics, font, entry, state.isPaused(), rowLabel(entry, row.grouped()),
                    bounds.x() + 7, textY, rowWidth - 4, WatchPanelLayout.valueWidth(bounds.width()),
                    kindInset, labelColor, valueColor)
                : WatchRowRenderer.render(graphics, font, entry, state.isPaused(), rowLabel(entry, row.grouped()),
                    bounds.x() + 7 + kindInset, textY, rowWidth - 4 - kindInset, labelColor, valueColor);
            if (!stackedValues && row.showScope()) {
                String scope = scope(entry);
                WatchUi.line(graphics, font, scope, bounds.x() + 7 + kindInset,
                    textY + lineSpacing, rowWidth - 4 - kindInset, MUTED);
                clipped |= font.width(scope) > rowWidth - 4 - kindInset;
            }
            Component inspectionLabel = text("inspect", WatchFormatting.specification(entry.spec()).getString());
            // A healthy, fully drawn row is its own description; hover only adds what it hides.
            Tooltip inspectionTooltip = clipped || WatchFormatting.needsExplanation(entry) ? rowTooltip(entry) : null;
            // Hover stays quiet, but two rows of the same watch bound to different executors must still
            // be told apart, and the actions menu discovered, by speech.
            Component narration = Component.literal(scope(entry) + "\n").append(text("menu.hint"));
            for (int line = 0; line < inspectionBounds.size(); line++) {
                button((line == 0 ? "watch-row-" : "watch-value-") + entry.id(), inspectionBounds.get(line),
                    inspectionLabel, true, entry.id() == selectedId || entry.id() == expandedId,
                    () -> {
                        selectedId = entry.id();
                        toggleExpanded(entry.id());
                        navigation.requestFocus("watch-row-" + entry.id());
                    },
                    navigation, 2 * (offset + index), 0).asHitSurface()
                    .withSecondaryAction(() -> openContextMenu(entry.id(), rowArea, input, overlay))
                    .withNarrationHint(narration)
                    .setTooltip(inspectionTooltip);
            }
            if (expanded && expansion != null)
                paintExpansion(graphics, font, y, y + rowHeight, detailWidth, detailLines, actions, expansionLimit,
                    navigation, 2 * (offset + index) + 1);
        }
        if (focusRequested) navigation.requestFocus("watch-row-" + selectedId);
        if (maximum > 0 && scrollBounds.height() > 0 && displayed > 0) {
            int h = scrollBounds.height(), thumb = Math.min(h, Math.max(8, h * h / totalHeight));
            int x = bounds.x() + bounds.width() - 4;
            graphics.fill(x, scrollBounds.y(), x + 2, scrollBounds.y() + h, DebuggerTheme.color(BORDER));
            int y = scrollBounds.y() + (h - thumb) * offset / maximum;
            graphics.fill(x, y, x + 2, y + thumb, DebuggerTheme.color(SCROLLBAR));
            scrollbars.add("watch", false, x, scrollBounds.y(), h, 2, thumb, offset, maximum, value -> offset = value);
        }
        if (interactive && noticeVisible && mouseX >= bounds.x()
            && mouseX < bounds.x() + bounds.width() - 48 && mouseY >= bounds.y() && mouseY < bounds.y() + HEADER
            && HoverDelay.elapsed("watch.notice"))
            graphics.setTooltipForNextFrame(font, notice, mouseX, mouseY);
        buttons.keySet().retainAll(used);
        return List.copyOf(controls);
    }

    /** Keyboard entry uses only the currently focused Watch row, never a remembered selection elsewhere. */
    public boolean openContextMenu(@Nullable GuiEventListener focused, InputManager input, DebuggerOverlay overlay) {
        if (focused == null) return false;
        for (var row : rowBounds.entrySet()) {
            long id = row.getKey();
            if (buttons.get("watch-row-" + id) == focused || buttons.get("watch-value-" + id) == focused)
                return openContextMenu(id, row.getValue(), input, overlay);
        }
        return false;
    }

    private ClientWatchState.@Nullable Entry entry(long id) {
        return state.watches().displayedEntries().stream().filter(candidate -> candidate.id() == id).findFirst().orElse(null);
    }

    private boolean openContextMenu(long id, Bounds anchor, InputManager input, DebuggerOverlay overlay) {
        Screen parent = Minecraft.getInstance().gui.screen();
        var entry = entry(id);
        if (parent == null || entry == null) return false;
        long generation = state.watches().generation();
        selectedId = id;
        overlay.closeViewMenu();
        closeGroupingMenu();
        overlay.scrollbars().release();
        overlay.panelResizing().cancel();
        var menu = new DebuggerContextMenu(parent, anchor, WatchFormatting.specification(entry.spec()),
            () -> menuItems(id, generation, input, overlay), event -> {
                // A docked Source panel owns its covered pixels even while this layer is open.
                if (parent instanceof FunctionSourceScreen source && source.containsPanel(event.x(), event.y())) return false;
                for (var row : rowBounds.entrySet()) {
                    if (row.getValue().contains(event.x(), event.y()))
                        return openContextMenu(row.getKey(), row.getValue(), input, overlay);
                }
                return false;
            }, () -> {
                contextMenuId = 0;
                overlay.navigation().requestFocus(entry(id) == null ? "watch-add" : "watch-row-" + id);
            });
        if (parent instanceof FunctionSourceScreen) parent.setFocused(null);
        ScreenLayers.open(parent, menu);
        contextMenuId = id;
        return true;
    }

    private List<DebuggerContextMenu.Item> menuItems(long id, long generation, InputManager input, DebuggerOverlay overlay) {
        if (generation != state.watches().generation()) return List.of();
        var entry = entry(id);
        if (entry == null) return List.of();
        List<DebuggerContextMenu.Item> items = new ArrayList<>();
        items.add(new DebuggerContextMenu.Item(text("details.copy_value"), DebuggerIcon.COPY_UUID, true, null, () -> copyValue(entry)));
        if (entry.automatic()) {
            items.add(new DebuggerContextMenu.Item(text("menu.add_watch"), DebuggerIcon.WATCHES, true, text("keep"), () -> {
                if (state.watches().pinChange(id)) notice(text("feedback.added", WatchFormatting.specification(entry.spec()).getString()));
                else notice(text("feedback.duplicate"));
            }));
        } else {
            if (entry.spec().kind() != WatchSpec.Kind.STORAGE_NBT) {
                boolean pinned = entry.spec().isPinned();
                EntityRef target = executor(entry);
                String targetLabel = target == null ? "" : target.name() + " #" + target.uuid().toString().substring(0, 8);
                items.add(new DebuggerContextMenu.Item(text(pinned ? "unpin" : "pin"), DebuggerIcon.PIN,
                    pinned || target != null, pinned ? text("tooltip.unpin")
                        : target == null ? text("pin_unavailable") : text("tooltip.pin_context", targetLabel), () -> togglePin(id)));
            }
            items.add(new DebuggerContextMenu.Item(text("edit"), DebuggerIcon.EDIT, true, null,
                () -> beginComposer(id, input, overlay)));
            items.add(new DebuggerContextMenu.Item(text("menu.delete"), DebuggerIcon.DELETE, true, null, () -> {
                removedWatch = state.watches().removeForUndo(id);
                undoUntil = System.nanoTime() + 8_000_000_000L;
                notice(text("feedback.removed", WatchFormatting.specification(entry.spec()).getString()));
            }));
        }
        return items;
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
                    group.entries().stream().allMatch(WatchGrouping::isUnchangedMissing), group.entries().size(),
                    (int) group.entries().stream().filter(entry -> entry.displayedChange().isValueChange()).count(),
                    (int) group.entries().stream().filter(WatchPanel::hasIssue).count()));
                // Explicit reveals (new pins, Undo, details) must still reach their watch.
                if (requestedId != 0 && group.entries().stream().anyMatch(entry -> entry.id() == requestedId))
                    collapsedHeadings.remove(key);
                if (collapsedHeadings.contains(key)) continue;
            }
            group.entries().forEach(entry -> result.add(new Row(entry.id(), entry, Component.empty(), grouped,
                grouped && state.watches().grouping() == WatchGrouping.Mode.PATH
                    && entry.spec().kind() != WatchSpec.Kind.STORAGE_NBT ? null : kindIcon(entry.spec()),
                noExecutor(entry), WatchGrouping.isUnchangedMissing(entry), 0, 0, 0)));
        }
        headingKeys.keySet().retainAll(usedHeadings);
        collapsedHeadings.retainAll(headingKeys.values());
        return result;
    }

    private static boolean hasIssue(ClientWatchState.Entry entry) {
        WatchResult result = entry.displayedResult();
        return result != null && switch (result.status()) {
            case INVALID_PATH, TOO_LARGE, ERROR, UNAVAILABLE -> true;
            default -> false;
        };
    }

    private boolean keyboardFocused(String id, boolean interactive) {
        DebuggerButton button = buttons.get(id);
        return interactive && button != null && button.isFocused()
            && Minecraft.getInstance().getLastInputType().isKeyboard();
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

    private Tooltip rowTooltip(ClientWatchState.Entry entry) {
        var details = Component.empty();
        for (Component line : WatchFormatting.tooltip(entry, state.isPaused())) {
            if (!details.getSiblings().isEmpty()) details.append("\n");
            details.append(line);
        }
        return Tooltip.create(details);
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
        notice(Component.translatable("codon.ui.copied"));
    }

    private DebuggerButton button(String id, Bounds b, Component label, boolean active, boolean selected, Runnable action,
                                  DebuggerNavigation navigation, int row, int column) {
        return control(id, b, label, active, selected, action, navigation, row, column, false);
    }

    private DebuggerButton control(String id, Bounds b, Component label, boolean active, boolean selected, Runnable action,
                                   DebuggerNavigation navigation, int row, int column, boolean chrome) {
        DebuggerButton button = buttons.computeIfAbsent(id, ignored -> new DebuggerButton());
        button.configure(b.x(), b.y(), b.width(), b.height(), label, active, selected, false, false, action);
        if (!chrome) button.withoutChrome();
        if (selected) button.withStatusColor(TEAL, TEAL_SURFACE);
        used.add(id); controls.add(button);
        // Logical rows were registered for hidden entries; bind only the controls in the viewport.
        if (row < 0) navigation.add(id, DebuggerNavigation.Group.WATCH, row, column, () -> { });
        navigation.bind(id, DebuggerNavigation.Group.WATCH, button);
        return button;
    }

    private void paintExpansion(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font, int rowTop, int top,
                                int width, List<FormattedCharSequence> lines, List<Action> actions, int limit,
                                DebuggerNavigation navigation, int navigationRow) {
        var layout = WatchExpansionLayout.create(bounds.x() + 11, top, width, lines.size(), actionWidths(actions), limit);
        int bottom = top + layout.height();
        graphics.fill(bounds.x() + 3, top, bounds.x() + bounds.width() - 6, bottom - 1, DebuggerTheme.color(ROW_HOVER));
        graphics.fill(bounds.x() + 3, rowTop, bounds.x() + 4, bottom - 1, DebuggerTheme.color(TEAL));
        expansionMaxScroll = Math.max(0, lines.size() - layout.visibleLines());
        expansionVisibleLines = layout.visibleLines();
        expansionScroll = Math.clamp(expansionScroll, 0, expansionMaxScroll);
        int textTop = layout.textTop(top);
        int textHeight = layout.visibleLines() * WatchExpansionLayout.LINE_HEIGHT;
        expansionTextBounds = new Bounds(bounds.x() + 3, top, bounds.width() - 9, textHeight + WatchExpansionLayout.PADDING);
        for (int line = 0; line < layout.visibleLines(); line++)
            graphics.text(font, lines.get(expansionScroll + line), bounds.x() + 11,
                textTop + line * WatchExpansionLayout.LINE_HEIGHT + 1, DebuggerTheme.foreground(TEXT), false);
        if (expansionMaxScroll > 0) {
            int thumb = Math.max(4, textHeight * layout.visibleLines() / lines.size());
            int y = textTop + (textHeight - thumb) * expansionScroll / expansionMaxScroll;
            int x = bounds.x() + bounds.width() - 9;
            graphics.fill(x, y, x + 2, y + thumb, DebuggerTheme.color(SCROLLBAR));
        }
        for (int column = 0; column < actions.size(); column++) {
            Action action = actions.get(column);
            control(action.id(), layout.actions().get(column), action.label(), action.active(), false, action.run(),
                navigation, navigationRow, column, true);
        }
    }

    private static List<Integer> actionWidths(List<Action> actions) {
        var font = Minecraft.getInstance().font;
        return actions.stream().map(action -> font.width(action.label()) + 12).toList();
    }

    /** Copy works on the acknowledged value only; Retry follows the server-reported read state. */
    private List<Action> actions(ClientWatchState.Entry entry, InputManager input, DebuggerOverlay overlay) {
        long id = entry.id();
        List<Action> actions = new ArrayList<>();
        actions.add(new Action("watch-details-copy-value", text("details.copy_value"), entry.displayedResult() != null,
            () -> copyValue(entry)));
        actions.add(new Action("watch-details-copy-path", text("details.copy_path"), true, () -> {
            WatchSpec spec = entry.spec();
            Minecraft.getInstance().keyboardHandler.setClipboard(spec.kind() == WatchSpec.Kind.SCORE ? spec.target() : spec.path());
            notice(Component.translatable("codon.ui.copied"));
        }));
        // The displayed result may be the previous executor's; Retry follows the current read.
        if (!entry.automatic() && (hasIssue(entry) || state.watches().canRetry(id)))
            actions.add(new Action("watch-details-retry", text("details.retry"),
                !state.watchReadsFailed() && state.watches().canRetry(id), () -> state.watches().retry(id)));
        if (!entry.automatic())
            actions.add(new Action("watch-details-edit", text("edit"), true, () -> beginComposer(id, input, overlay)));
        return actions;
    }

    /** Value (with its last change) first, since narrow panels show only its first lines; then binding and expression. */
    private List<Component> details(ClientWatchState.Entry entry) {
        List<Component> lines = new ArrayList<>();
        lines.add(labeled("details.current_value", WatchFormatting.currentValue(entry, state.isPaused())));
        if (entry.completedStep() != null)
            lines.add(Component.empty().append(muted(text("details.previous_executor", WatchFormatting.executorLabel(entry))
                .getString() + ": ")).append(WatchFormatting.fullValue(entry, state.isPaused())));
        Component binding;
        if (entry.spec().kind() == WatchSpec.Kind.STORAGE_NBT) binding = text("details.storage");
        else if (entry.spec().scoreHolder() != null) binding = text("details.fixed", entry.spec().scoreHolder());
        else if (entry.spec().executor() == null) binding = text("details.current_context");
        else binding = text("details.fixed", (entry.executorName().isBlank() ? "" : entry.executorName() + " ")
            + entry.spec().executor());
        lines.add(labeled("details.binding", binding));
        lines.add(labeled("details.expression", detailExpression(entry.spec())));
        if (entry.automatic()) lines.add(muted(text("tooltip.automatic").getString()));
        if (entry.change() == ClientWatchState.Change.TARGET_CHANGED) lines.add(muted(text("tooltip.executor_changed").getString()));
        if (entry.change() == ClientWatchState.Change.AVAILABILITY_CHANGED)
            lines.add(muted(text("tooltip.availability_changed").getString()));
        WatchResult result = entry.displayedResult();
        if (result != null && !result.targetKey().isEmpty())
            lines.add(muted(text("tooltip.target", result.targetKey()).getString()));
        return lines;
    }

    static Component detailExpression(WatchSpec spec) {
        String value = switch (spec.kind()) {
            case SCORE -> spec.target();
            case ENTITY_NBT -> spec.path();
            case STORAGE_NBT -> spec.target() + " / " + spec.path();
        };
        return text("kind." + spec.kind().name().toLowerCase(java.util.Locale.ROOT)).copy().append(": ").append(Component.literal(value));
    }

    private static Component labeled(String key, Component value) {
        return Component.empty().append(muted(text(key).getString() + ": ")).append(value);
    }

    private static Component muted(String value) { return Component.literal(value).withColor(MUTED & 0xFFFFFF); }

    public boolean scroll(double x, double y, double amount) {
        if (!scrollBounds.contains(x, y) || amount == 0) return false;
        if (expansionTextBounds.contains(x, y) && expansionMaxScroll > 0) {
            expansionScroll = Math.clamp(expansionScroll + (amount > 0 ? -1 : 1), 0, expansionMaxScroll);
            return true;
        }
        offset = Math.clamp(offset + (amount > 0 ? -1 : 1), 0, maximum);
        return true;
    }
}
