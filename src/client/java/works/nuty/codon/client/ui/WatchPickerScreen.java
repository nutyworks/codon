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
public final class WatchPickerScreen extends Screen {
    private static final int ROW_HEIGHT = 26;
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
    private EditBox search;
    private DebuggerButton previous, next, up, retry, close;
    private WatchEditorPage presentedPage;

    public WatchPickerScreen(Screen parent, ClientDebuggerState state, WatchEditorQuery initial,
                             Consumer<WatchEditorPage.Option> selected) {
        super(WatchUi.text("picker.title"));
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
        panelWidth = Math.max(1, Math.min(560, width - 16));
        panelHeight = Math.max(1, Math.min(360, height - 12));
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;

        search = addRenderableWidget(new EditBox(font, left + 8, top + 29, Math.max(1, panelWidth - 84), 20,
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

        previous = addRenderableWidget(WatchUi.button(left + 8, top + panelHeight - 28, 46, 20,
            WatchUi.text("picker.previous"), () -> request(Math.max(0, pageOffset - WatchEditorPage.PAGE_SIZE))));
        next = addRenderableWidget(WatchUi.button(left + 56, top + panelHeight - 28, 46, 20,
            WatchUi.text("picker.next"), () -> request(pageOffset + currentPageSize())));
        up = addRenderableWidget(WatchUi.button(left + 104, top + panelHeight - 28, 42, 20,
            WatchUi.text("picker.up"), this::up));
        retry = addRenderableWidget(WatchUi.button(left + panelWidth - 112, top + panelHeight - 28, 50, 20,
            WatchUi.text("retry"), () -> state.watchEditor().retry()));
        close = addRenderableWidget(WatchUi.button(left + panelWidth - 58, top + 5, 50, 18,
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

    private List<WatchEditorPage.Option> options() {
        WatchEditorPage page = state.watchEditor().page();
        if (page == null) return List.of();
        if (mode != WatchEditorQuery.Mode.NBT || search.getValue().isBlank()) return page.options();
        String needle = search.getValue().toLowerCase(Locale.ROOT);
        return page.options().stream().filter(option -> option.value().toLowerCase(Locale.ROOT).contains(needle)
            || option.label().toLowerCase(Locale.ROOT).contains(needle)
            || option.detail().toLowerCase(Locale.ROOT).contains(needle)).toList();
    }

    private int visibleRows() { return Math.max(1, (panelHeight - 106) / ROW_HEIGHT); }
    private int currentPageSize() { WatchEditorPage page = state.watchEditor().page(); return page == null ? 0 : page.options().size(); }
    private int maxRowOffset(List<WatchEditorPage.Option> options) { return Math.max(0, options.size() - visibleRows()); }

    private void updatePresentation(WatchEditorPage page, List<WatchEditorPage.Option> options) {
        if (page != presentedPage) {
            presentedPage = page;
            rowOffset = 0;
            focusedOption = options.isEmpty() ? -1 : 0;
        }
        if (focusedOption >= options.size()) focusedOption = options.isEmpty() ? -1 : options.size() - 1;
        rowOffset = Math.clamp(rowOffset, 0, maxRowOffset(options));
        previous.active = page != null && pageOffset > 0;
        next.active = page != null && page.hasMore() && currentPageSize() > 0;
        up.visible = up.active = mode == WatchEditorQuery.Mode.NBT && !pathStack.isEmpty();
        retry.visible = retry.active = state.watchEditor().timedOut()
            || page != null && (page.status() == works.nuty.codon.core.model.WatchResult.Status.ERROR
            || page.status() == works.nuty.codon.core.model.WatchResult.Status.UNAVAILABLE);
    }

    private void up() {
        if (pathStack.isEmpty()) return;
        path = pathStack.removeLast();
        search.setValue("");
        request(0);
    }

    private boolean selectable(WatchEditorPage.Option option) {
        return mode != WatchEditorQuery.Mode.NBT || option.value().length() <= WatchSpec.MAX_INPUT_LENGTH;
    }

    private void choose(WatchEditorPage.Option option) {
        if (!selectable(option)) return;
        selected.accept(option);
        Minecraft.getInstance().gui.setScreen(parent);
    }

    private void expand(WatchEditorPage.Option option) {
        if (mode != WatchEditorQuery.Mode.NBT || !option.expandable()) return;
        pathStack.add(path);
        path = option.value();
        search.setValue("");
        request(0);
    }

    private void moveFocus(int direction) {
        List<WatchEditorPage.Option> options = options();
        if (options.isEmpty()) return;
        focusedOption = Math.clamp(focusedOption < 0 ? 0 : focusedOption + direction, 0, options.size() - 1);
        if (focusedOption < rowOffset) rowOffset = focusedOption;
        if (focusedOption >= rowOffset + visibleRows()) rowOffset = focusedOption - visibleRows() + 1;
        setFocused(null);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        refreshRequest();
        WatchEditorPage page = state.watchEditor().page();
        List<WatchEditorPage.Option> options = options();
        updatePresentation(page, options);
        graphics.fill(0, 0, width, height, 0x70000000);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, PANEL);
        graphics.outline(left, top, panelWidth, panelHeight, BORDER);
        graphics.fill(left, top, left + 2, top + 24, TEAL);
        WatchUi.line(graphics, font, title.getString(), left + 8, top + 9, panelWidth - 72, TEXT);
        String place = mode == WatchEditorQuery.Mode.NBT
            ? path.isEmpty() ? WatchUi.text("picker.root").getString() : path
            : WatchUi.text("picker." + mode.name().toLowerCase(Locale.ROOT)).getString();
        WatchUi.line(graphics, font, place, left + 8, top + 55, panelWidth - 16, MUTED);
        int listTop = top + 70;
        int listBottom = listTop + visibleRows() * ROW_HEIGHT;
        graphics.enableScissor(left + 7, listTop, left + panelWidth - 7, listBottom);
        for (int index = rowOffset; index < Math.min(options.size(), rowOffset + visibleRows()); index++) {
            WatchEditorPage.Option option = options.get(index);
            int y = listTop + (index - rowOffset) * ROW_HEIGHT;
            boolean focused = index == focusedOption;
            boolean hovered = mouseX >= left + 8 && mouseX < left + panelWidth - 8 && mouseY >= y && mouseY < y + ROW_HEIGHT - 2;
            boolean expandable = option.expandable() && mode == WatchEditorQuery.Mode.NBT;
            int surface = focused || hovered ? RAISED : SURFACE;
            graphics.fill(left + 8, y, left + panelWidth - 8, y + ROW_HEIGHT - 2, surface);
            if (focused) graphics.outline(left + 8, y, panelWidth - 16, ROW_HEIGHT - 2, TEAL);
            int labelColor = selectable(option) ? TEXT : MUTED;
            WatchUi.line(graphics, font, option.label(), left + 13, y + 4, panelWidth - (expandable ? 54 : 28), labelColor);
            if (!option.detail().isBlank()) WatchUi.line(graphics, font, option.detail(), left + 13, y + 14,
                panelWidth - (expandable ? 54 : 28), MUTED);
            if (expandable) {
                graphics.fill(left + panelWidth - 28, y + 5, left + panelWidth - 15, y + 18, TEAL_SURFACE);
                WatchUi.line(graphics, font, ">", left + panelWidth - 24, y + 5, 8, TEAL);
            }
            if (hovered && !selectable(option)) graphics.setTooltipForNextFrame(font,
                WatchUi.text("picker.path_too_long", WatchSpec.MAX_INPUT_LENGTH), mouseX, mouseY);
        }
        graphics.disableScissor();
        if (options.size() > visibleRows()) {
            int track = Math.max(1, listBottom - listTop);
            int thumb = Math.max(4, track * visibleRows() / options.size());
            int thumbY = listTop + (track - thumb) * rowOffset / maxRowOffset(options);
            graphics.fill(left + panelWidth - 5, thumbY, left + panelWidth - 3, thumbY + thumb, TEAL);
        }
        if (options.isEmpty()) {
            String status = page == null || state.watchEditor().waiting() ? WatchUi.text("picker.loading").getString()
                : page.status() == works.nuty.codon.core.model.WatchResult.Status.VALUE
                    ? WatchUi.text("picker.empty").getString() : WatchFormatting.status(page.status()).getString();
            WatchUi.line(graphics, font, status, left + 13, listTop + 8, panelWidth - 26,
                page != null && page.status() != works.nuty.codon.core.model.WatchResult.Status.VALUE ? AMBER : MUTED);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            List<WatchEditorPage.Option> options = options();
            int listTop = top + 70;
            if (event.x() >= left + 8 && event.x() < left + panelWidth - 8 && event.y() >= listTop
                && event.y() < listTop + visibleRows() * ROW_HEIGHT) {
                int index = rowOffset + (int) ((event.y() - listTop) / ROW_HEIGHT);
                if (index < options.size()) {
                    WatchEditorPage.Option option = options.get(index);
                    focusedOption = index;
                    if (mode == WatchEditorQuery.Mode.NBT && option.expandable() && event.x() >= left + panelWidth - 34) expand(option);
                    else choose(option);
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        List<WatchEditorPage.Option> options = options();
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
            if (event.key() == InputConstants.KEY_LEFT) up();
            else {
                List<WatchEditorPage.Option> options = options();
                if (focusedOption >= 0 && focusedOption < options.size()) expand(options.get(focusedOption));
            }
            return true;
        }
        if (event.key() == InputConstants.KEY_UP) { moveFocus(-1); return true; }
        if (event.key() == InputConstants.KEY_DOWN) { moveFocus(1); return true; }
        if (getFocused() == null && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)
            && focusedOption >= 0) {
            List<WatchEditorPage.Option> options = options();
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
