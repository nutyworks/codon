package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import works.nuty.codon.client.ui.layout.WatchPickerLayout;
import works.nuty.codon.core.model.WatchEditorPage;
import works.nuty.codon.core.model.WatchEditorQuery;
import works.nuty.codon.core.model.WatchSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

import static works.nuty.codon.client.ui.DebuggerTheme.*;

/** Read-only paged chooser used by the Watch expression form. */
public final class WatchPickerScreen extends ScaledCodonScreen {
    private final Screen parent;
    private final ClientDebuggerState state;
    private final WatchEditorQuery initial;
    private final Consumer<WatchEditorPage.Option> selected;
    private final List<String> pathStack = new ArrayList<>();
    private final List<AbstractWidget> tabOrder = new ArrayList<>();
    private WatchEditorQuery.Mode mode;
    private WatchSpec.Kind kind;
    private String target;
    private String path;
    private String searchText;
    private java.util.UUID executor;
    private int pageOffset;
    private int rowOffset;
    private int focusedOption = -1;
    private int left, top, panelWidth, panelHeight;
    private WatchPickerLayout layout;
    private EditBox search;
    private DebuggerButton previous, next, up, retry, close;
    private WatchEditorPage presentedPage;
    /** Exact last-rendered options for a retained page, including its local NBT filter. */
    private List<WatchEditorPage.Option> presentedOptions = List.of();

    public WatchPickerScreen(Screen parent, ClientDebuggerState state, WatchEditorQuery initial,
                             Consumer<WatchEditorPage.Option> selected) {
        super(WatchUi.text("picker.title"), state.preferences());
        this.parent = Objects.requireNonNull(parent);
        this.state = Objects.requireNonNull(state);
        this.initial = Objects.requireNonNull(initial);
        this.selected = Objects.requireNonNull(selected);
        mode = initial.mode();
        kind = initial.kind();
        target = initial.target();
        path = initial.path();
        searchText = initial.search();
        executor = initial.executor();
        pageOffset = initial.offset();
    }

    @Override protected void init() {
        clearWidgets();
        tabOrder.clear();
        layout = WatchPickerLayout.create(width, height, 1);
        updateLayout(1);

        search = addRenderableWidget(new DebuggerEditBox(font, layout.search().x(), layout.search().y(), layout.search().width(), 20,
            WatchUi.text("picker.search")));
        search.setMaxLength(WatchEditorQuery.MAX_SEARCH_LENGTH);
        Component searchHint = mode == WatchEditorQuery.Mode.NBT
            ? WatchUi.text("picker.filter_page") : WatchUi.text("picker.search");
        search.setHint(searchHint);
        search.setTooltip(Tooltip.create(mode == WatchEditorQuery.Mode.NBT
            ? WatchUi.text("picker.filter_page") : WatchUi.text("picker.search_hint")));
        search.setValue(searchText);
        search.setResponder(ignored -> {
            searchText = ignored;
            rowOffset = 0;
            focusedOption = -1;
            if (mode != WatchEditorQuery.Mode.NBT) request(0);
        });
        tabOrder.add(search);

        previous = addRenderableWidget(WatchUi.button(layout.previous().x(), layout.previous().y(), 54, 20,
            WatchUi.text("picker.previous"), () -> {
                if (interactionAvailable()) request(Math.max(0, pageOffset - WatchEditorPage.PAGE_SIZE));
            }));
        next = addRenderableWidget(WatchUi.button(layout.next().x(), layout.next().y(), 54, 20,
            WatchUi.text("picker.next"), () -> {
                if (interactionAvailable()) request(pageOffset + currentPageSize());
            }));
        up = addRenderableWidget(WatchUi.button(layout.up().x(), layout.up().y(), 54, 20,
            WatchUi.text("picker.up"), this::up));
        retry = addRenderableWidget(WatchUi.button(layout.retry().x(), layout.retry().y(), 54, 20,
            WatchUi.text("retry"), () -> {
                if (interactionAvailable()) state.watchEditor().retry();
            }));
        close = addRenderableWidget(WatchUi.button(layout.close().x(), layout.close().y(), 54, 18,
            WatchUi.text("close"), this::onClose));
        tabOrder.add(previous);
        tabOrder.add(next);
        tabOrder.add(up);
        tabOrder.add(retry);
        tabOrder.add(close);
        for (int index = 0; index < tabOrder.size(); index++) tabOrder.get(index).setTabOrderGroup(index);
        setFocused(search);
        request(pageOffset);
    }

    private void request(int offset) {
        pageOffset = Math.max(0, offset);
        rowOffset = 0;
        focusedOption = -1;
        String querySearch = mode == WatchEditorQuery.Mode.NBT ? "" : search == null ? searchText : search.getValue();
        state.watchEditor().request(WatchUi.pause(state), state.selectedPauseSourceIndex(),
            new WatchEditorQuery(mode, kind, target, path, executor, querySearch, pageOffset));
    }

    private void refreshRequest() {
        String querySearch = mode == WatchEditorQuery.Mode.NBT ? "" : search.getValue();
        state.watchEditor().request(WatchUi.pause(state), state.selectedPauseSourceIndex(),
            new WatchEditorQuery(mode, kind, target, path, executor, querySearch, pageOffset));
    }

    private List<WatchEditorPage.Option> options(@org.jspecify.annotations.Nullable WatchEditorPage page,
                                                  boolean authoritative) {
        if (page == null) return List.of();
        // A retained page belongs to the preceding query.  Leave it intact until the
        // new response arrives instead of applying this query's search to it.
        if (!authoritative) return page == presentedPage ? presentedOptions : page.options();
        if (mode != WatchEditorQuery.Mode.NBT || search.getValue().isBlank()) return page.options();
        String needle = search.getValue().toLowerCase(Locale.ROOT);
        return page.options().stream().filter(option -> option.value().toLowerCase(Locale.ROOT).contains(needle)
            || option.label().toLowerCase(Locale.ROOT).contains(needle)
            || option.detail().toLowerCase(Locale.ROOT).contains(needle)).toList();
    }

    private int visibleRows() { return layout.visibleRows(); }
    private int currentPageSize() { WatchEditorPage page = state.watchEditor().page(); return page == null ? 0 : page.options().size(); }
    private int maxRowOffset(List<WatchEditorPage.Option> options) { return Math.max(0, options.size() - visibleRows()); }

    private boolean interactionAvailable() { return state.watchEditor().page() != null; }

    private void updateLayout(int optionCount) {
        layout = WatchPickerLayout.create(width, height, optionCount);
        left = layout.panel().x();
        top = layout.panel().y();
        panelWidth = layout.panel().width();
        panelHeight = layout.panel().height();
        if (previous != null) {
            position(previous, layout.previous());
            position(next, layout.next());
            position(up, layout.up());
            position(retry, layout.retry());
        }
    }

    private static void position(AbstractWidget widget, Bounds bounds) {
        widget.setX(bounds.x());
        widget.setY(bounds.y());
    }

    private void updatePresentation(WatchEditorPage page, List<WatchEditorPage.Option> options, boolean authoritative) {
        // A pending reply keeps the last presented size, just as it retains its rows.
        if (page != null) updateLayout(options.size());
        if (page != presentedPage) {
            presentedPage = page;
            rowOffset = 0;
            focusedOption = options.isEmpty() ? -1 : 0;
        }
        if (authoritative) presentedOptions = List.copyOf(options);
        if (focusedOption >= options.size()) focusedOption = options.isEmpty() ? -1 : options.size() - 1;
        rowOffset = Math.clamp(rowOffset, 0, maxRowOffset(options));
        previous.active = authoritative && pageOffset > 0;
        next.active = authoritative && page != null && page.hasMore() && currentPageSize() > 0;
        up.visible = up.active = authoritative && mode == WatchEditorQuery.Mode.NBT && !pathStack.isEmpty();
        retry.visible = retry.active = state.watchEditor().timedOut()
            || authoritative && page != null && (page.status() == works.nuty.codon.core.model.WatchResult.Status.ERROR
            || page.status() == works.nuty.codon.core.model.WatchResult.Status.UNAVAILABLE);
    }

    private void up() {
        if (!interactionAvailable() || pathStack.isEmpty()) return;
        path = pathStack.removeLast();
        search.setValue("");
        request(0);
    }

    private boolean selectable(WatchEditorPage.Option option) {
        return mode != WatchEditorQuery.Mode.NBT || option.value().length() <= WatchSpec.MAX_INPUT_LENGTH;
    }

    private void choose(WatchEditorPage.Option option) {
        if (!interactionAvailable() || !selectable(option)) return;
        selected.accept(option);
        Minecraft.getInstance().gui.setScreen(parent);
    }

    private void expand(WatchEditorPage.Option option) {
        if (!interactionAvailable() || mode != WatchEditorQuery.Mode.NBT || !option.expandable()) return;
        pathStack.add(path);
        path = option.value();
        search.setValue("");
        request(0);
    }

    private void moveFocus(int direction) {
        if (!interactionAvailable()) return;
        List<WatchEditorPage.Option> options = options(state.watchEditor().page(), true);
        if (options.isEmpty()) return;
        focusedOption = Math.clamp(focusedOption < 0 ? 0 : focusedOption + direction, 0, options.size() - 1);
        if (focusedOption < rowOffset) rowOffset = focusedOption;
        if (focusedOption >= rowOffset + visibleRows()) rowOffset = focusedOption - visibleRows() + 1;
        setFocused(null);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        refreshRequest();
        WatchEditorPage authoritativePage = state.watchEditor().page();
        WatchEditorPage page = state.watchEditor().displayedPage();
        boolean authoritative = authoritativePage != null;
        List<WatchEditorPage.Option> options = options(page, authoritative);
        updatePresentation(page, options, authoritative);
        graphics.fill(0, 0, width, height, DebuggerTheme.color(0x70000000));
        graphics.fill(left, top, left + panelWidth, top + panelHeight, DebuggerTheme.color(PANEL));
        graphics.outline(left, top, panelWidth, panelHeight, DebuggerTheme.color(BORDER));
        graphics.fill(left, top, left + 2, top + 24, DebuggerTheme.color(TEAL));
        WatchUi.line(graphics, font, title.getString(), left + 8, top + 9, panelWidth - 72, TEXT);
        String place = mode == WatchEditorQuery.Mode.NBT
            ? path.isEmpty() ? WatchUi.text("picker.root").getString() : path
            : WatchUi.text(mode == WatchEditorQuery.Mode.ENTITIES && kind == WatchSpec.Kind.SCORE
                ? "picker.score_holders" : "picker." + mode.name().toLowerCase(Locale.ROOT)).getString();
        WatchUi.line(graphics, font, place, left + 8, top + 55, panelWidth - 16, MUTED);
        int listTop = layout.listTop();
        int listBottom = layout.listBottom();
        boolean scrollable = options.size() > visibleRows();
        graphics.enableScissor(layout.contentX(), listTop, layout.contentRight(), listBottom);
        for (int index = rowOffset; index < Math.min(options.size(), rowOffset + visibleRows()); index++) {
            WatchEditorPage.Option option = options.get(index);
            int visibleIndex = index - rowOffset;
            Bounds row = layout.row(visibleIndex, scrollable);
            boolean focused = authoritative && index == focusedOption;
            boolean hovered = authoritative && row.contains(mouseX, mouseY);
            boolean expandable = option.expandable() && mode == WatchEditorQuery.Mode.NBT;
            int surface = focused || hovered ? RAISED : SURFACE;
            graphics.fill(row.x(), row.y(), row.x() + row.width(), row.y() + row.height(), DebuggerTheme.color(surface));
            if (focused) graphics.outline(row.x(), row.y(), row.width(), row.height(), DebuggerTheme.color(TEAL));
            int labelColor = selectable(option) ? TEXT : MUTED;
            WatchUi.line(graphics, font, option.label(), row.x() + 5,
                layout.labelY(visibleIndex, !option.detail().isBlank(), font.lineHeight),
                layout.textWidth(expandable, scrollable), labelColor);
            if (!option.detail().isBlank()) WatchUi.line(graphics, font, option.detail(), row.x() + 5, row.y() + 13,
                layout.textWidth(expandable, scrollable), MUTED);
            if (expandable) {
                Bounds arrow = layout.expand(visibleIndex, scrollable);
                graphics.fill(arrow.x(), arrow.y(), arrow.x() + arrow.width(), arrow.y() + arrow.height(), DebuggerTheme.color(TEAL_SURFACE));
                WatchUi.line(graphics, font, ">", arrow.x() + 4, arrow.y(), 8, TEAL);
            }
            if (authoritative && hovered && !selectable(option)) graphics.setTooltipForNextFrame(font,
                WatchUi.text("picker.path_too_long", WatchSpec.MAX_INPUT_LENGTH), mouseX, mouseY);
        }
        graphics.disableScissor();
        if (scrollable) {
            int track = Math.max(1, listBottom - listTop);
            int thumb = Math.max(4, track * visibleRows() / options.size());
            int thumbY = listTop + (track - thumb) * rowOffset / maxRowOffset(options);
            graphics.fill(layout.scrollbarX(), thumbY, layout.contentRight(), thumbY + thumb, DebuggerTheme.color(TEAL));
        }
        if (options.isEmpty()) {
            String status = page == null ? WatchUi.text("picker.loading").getString()
                : page.status() == works.nuty.codon.core.model.WatchResult.Status.VALUE
                    ? WatchUi.text("picker.empty").getString() : WatchFormatting.status(page.status()).getString();
            WatchUi.line(graphics, font, status, left + 13, listTop + 8, panelWidth - 26,
                page != null && page.status() != works.nuty.codon.core.model.WatchResult.Status.VALUE ? AMBER : MUTED);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (interactionAvailable() && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            List<WatchEditorPage.Option> options = options(state.watchEditor().page(), true);
            int listTop = layout.listTop();
            if (event.y() >= listTop && event.y() < layout.listBottom()) {
                int visibleIndex = (int) ((event.y() - listTop) / WatchPickerLayout.ROW_HEIGHT);
                int index = rowOffset + visibleIndex;
                boolean scrollable = options.size() > visibleRows();
                if (index < options.size() && layout.row(visibleIndex, scrollable).contains(event.x(), event.y())) {
                    WatchEditorPage.Option option = options.get(index);
                    focusedOption = index;
                    if (mode == WatchEditorQuery.Mode.NBT && option.expandable()
                        && layout.expand(visibleIndex, scrollable).contains(event.x(), event.y())) expand(option);
                    else choose(option);
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (scrollY == 0 || !layout.list().contains(x, y)) return super.mouseScrolled(x, y, scrollX, scrollY);
        if (!interactionAvailable()) return true;
        List<WatchEditorPage.Option> options = options(state.watchEditor().page(), true);
        rowOffset = Math.clamp(rowOffset - (int) Math.signum(scrollY) * 3, 0, maxRowOffset(options));
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_TAB) {
            List<AbstractWidget> eligible = tabOrder.stream().filter(widget -> widget.visible && widget.active).toList();
            if (!eligible.isEmpty()) {
                int current = eligible.indexOf(getFocused());
                setFocused(eligible.get(Math.floorMod(current + (event.hasShiftDown() ? -1 : 1), eligible.size())));
            }
            return true;
        }
        if (getFocused() instanceof EditBox && event.key() == InputConstants.KEY_DOWN) {
            moveFocus(1);
            return true;
        }
        if (getFocused() instanceof EditBox) return super.keyPressed(event);
        if (getFocused() == null && (event.key() == InputConstants.KEY_RIGHT || event.key() == InputConstants.KEY_LEFT)) {
            if (!interactionAvailable()) return true;
            if (event.key() == InputConstants.KEY_LEFT) up();
            else {
                List<WatchEditorPage.Option> options = options(state.watchEditor().page(), true);
                if (focusedOption >= 0 && focusedOption < options.size()) expand(options.get(focusedOption));
            }
            return true;
        }
        if (event.key() == InputConstants.KEY_UP) { moveFocus(-1); return true; }
        if (event.key() == InputConstants.KEY_DOWN) { moveFocus(1); return true; }
        if (getFocused() == null && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)
            && focusedOption >= 0) {
            if (!interactionAvailable()) return true;
            List<WatchEditorPage.Option> options = options(state.watchEditor().page(), true);
            if (focusedOption < options.size()) choose(options.get(focusedOption));
            return true;
        }
        if (event.key() == InputConstants.KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(event);
    }

    @Override public void removed() { state.watchEditor().cancel(); }
    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) { }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
}
