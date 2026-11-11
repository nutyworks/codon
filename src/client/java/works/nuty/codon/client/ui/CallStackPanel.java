package works.nuty.codon.client.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.CodonClientMod;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import works.nuty.codon.client.ui.layout.VisibleWidgetCache;
import works.nuty.codon.core.model.CallFrame;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.SourceLocation;

import static works.nuty.codon.client.ui.DebuggerTheme.*;

/**
 * Docked list of the recorded call stack, innermost frame first. A list never overflows sideways,
 * however deep the stack. Selecting a row selects that frame for Flow; it never sends a request.
 */
final class CallStackPanel {
    private static final Bounds EMPTY = new Bounds(0, 0, 0, 0);
    private static final int HEADER = 22;
    private static final int ROW_HEIGHT = 18;
    private static final int PADDING = 4;
    private static final int ACTION_SIZE = 14;
    /** Header plus one row, so a docked section is never reduced to a title bar. */
    static final int MINIMUM_HEIGHT = HEADER + ROW_HEIGHT + PADDING;

    private final ClientDebuggerState state;
    private final Runnable selectionChanged;
    private final Supplier<@Nullable Screen> host;
    private final VisibleWidgetCache<String, DebuggerButton> rows = new VisibleWidgetCache<>();
    private final VisibleWidgetCache<String, DebuggerButton> sources = new VisibleWidgetCache<>();
    private final Map<DebuggerButton, Runnable> menus = new HashMap<>();
    private Bounds bounds = EMPTY;
    private Bounds scrollBounds = EMPTY;
    private int offset;
    private int maximum;
    private @Nullable Selection lastSelection;

    private record Selection(List<CallFrame> frames, int selected, int capacity) { }

    CallStackPanel(ClientDebuggerState state, Runnable selectionChanged) {
        this(state, selectionChanged, () -> Minecraft.getInstance().gui.screen());
    }

    CallStackPanel(ClientDebuggerState state, Runnable selectionChanged, Supplier<@Nullable Screen> host) {
        this.state = state;
        this.selectionChanged = selectionChanged;
        this.host = host;
    }

    Bounds bounds() { return bounds; }
    void clearBounds() {
        bounds = scrollBounds = EMPTY;
        menus.clear();
    }

    /** Height that shows every frame, for the dock to clamp to its space. */
    int preferredHeight() {
        return HEADER + Math.max(1, frames().size()) * ROW_HEIGHT + PADDING;
    }

    private List<CallFrame> frames() {
        return state.snapshot() == null ? List.of() : state.displayedCallStack();
    }

    String countHeader() {
        var withheld = withheld();
        return tr("codon.ui.call_stack", withheld != null ? withheld.callStackSize() : frames().size());
    }

    /** Withheld frames were measured by the server; never present them as zero or unrecorded. */
    private ClientDebuggerState.@Nullable WithheldPauseDetails withheld() {
        return frames().isEmpty() && state.isPaused() ? state.withheldPauseDetails() : null;
    }

    List<DebuggerButton> render(GuiGraphicsExtractor graphics, Bounds area, DebuggerNavigation navigation,
                                ScrollbarInput scrollbars) {
        clearBounds();
        if (area.width() < 72 || area.height() < MINIMUM_HEIGHT) return List.of();
        var font = Minecraft.getInstance().font;
        PauseSnapshot snapshot = state.snapshot();
        List<CallFrame> frames = frames();
        int capacity = Math.max(1, (area.height() - HEADER - PADDING) / ROW_HEIGHT);
        maximum = Math.max(0, frames.size() - capacity);
        Selection selection = new Selection(frames, state.selectedCallFrameIndex(), capacity);
        if (!selection.equals(lastSelection)) {
            // A new stop or frame selection reveals the selected frame; ordinary scrolling keeps its place.
            int selected = state.selectedCallFrameIndex();
            if (selected >= 0) offset = revealRow(selected, offset, capacity);
            lastSelection = selection;
        }
        for (int row = 0; row < frames.size(); row++) {
            int logical = row;
            Runnable reveal = () -> offset = Math.clamp(revealRow(logical, offset, capacity), 0, maximum);
            navigation.add(rowId(frames, row), DebuggerNavigation.Group.CALL_STACK, row, 0, reveal);
            if (canOpenSource(frames.get(row)))
                navigation.add(sourceId(frames, row), DebuggerNavigation.Group.CALL_STACK, row, 1, reveal);
        }
        navigation.revealFocus(DebuggerNavigation.Group.CALL_STACK);
        offset = Math.clamp(offset, 0, maximum);
        int shown = Math.min(capacity, frames.size() - offset);
        int height = Math.min(area.height(), HEADER + Math.max(1, shown) * ROW_HEIGHT + PADDING);
        bounds = new Bounds(area.x(), area.y(), area.width(), height);
        scrollBounds = new Bounds(area.x() + 3, area.y() + HEADER, area.width() - 6, height - HEADER - PADDING);
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + bounds.width(), bounds.y() + height, DebuggerTheme.color(PANEL));
        graphics.outline(bounds.x(), bounds.y(), bounds.width(), height, DebuggerTheme.color(BORDER));
        WatchUi.line(graphics, font, countHeader(), bounds.x() + 7, bounds.y() + (HEADER - font.lineHeight) / 2 + 1,
            bounds.width() - 14, TEXT);
        if (frames.isEmpty()) {
            var withheld = withheld();
            String empty = snapshot == null ? tr("codon.ui.call_stack.idle")
                : withheld != null ? tr("codon.ui.details_withheld") : tr("codon.ui.stack_unavailable");
            WatchUi.line(graphics, font, empty, bounds.x() + 7, bounds.y() + HEADER + 5, bounds.width() - 14, MUTED);
        }

        rows.begin();
        sources.begin();
        var controls = new ArrayList<DebuggerButton>();
        for (int row = offset; row < offset + shown; row++) {
            CallFrame frame = frames.get(row);
            int index = row;
            Runnable select = guarded(snapshot, frame, index, () -> {
                state.selectCallFrame(index);
                selectionChanged.run();
            });
            int y = bounds.y() + HEADER + (row - offset) * ROW_HEIGHT;
            DebuggerButton button = rows.get(rowId(frames, row), DebuggerButton::new);
            // The row leaves room on its right for its hover action, so each owns its own clicks.
            button.configure(bounds.x() + 4, y, bounds.width() - 10 - ACTION_SIZE, ROW_HEIGHT - 1,
                Component.literal(label(frame.location())), true, state.selectedCallFrameIndex() == index, true, false, select);
            button.withFlatChrome();
            if (state.isPausedCallFrame(frame)) button.withTextIcon(DebuggerIcon.PAUSE);
            if (index == state.selectedCallFrameIndex() && state.isViewingCurrentCommand())
                button.withStatusColor(AMBER, AMBER_SURFACE);
            button.setTooltip(Tooltip.create(tooltip(frame)));
            Bounds rowArea = new Bounds(button.getX(), y, button.getWidth() + ACTION_SIZE + 2, ROW_HEIGHT - 1);
            Runnable menu = () -> openMenu(snapshot, frame, index, rowArea, navigation, rowId(frames, index));
            button.withSecondaryAction(menu);
            menus.put(button, menu);
            navigation.bind(rowId(frames, row), DebuggerNavigation.Group.CALL_STACK, button);
            controls.add(button);
            if (canOpenSource(frame)) {
                DebuggerButton source = sources.get(sourceId(frames, row), DebuggerButton::new);
                source.configure(button.getRight() + 1, y + (button.getHeight() - ACTION_SIZE) / 2, ACTION_SIZE, ACTION_SIZE,
                    Component.translatable("codon.ui.call_stack.open_source"), true, false, false, false,
                    guarded(snapshot, frame, index, () -> openSource(frame)));
                source.withIcon(DebuggerIcon.SOURCE_FILE).withoutChrome()
                    .revealOnHover(rowArea.x(), rowArea.y(), rowArea.width(), rowArea.height());
                navigation.bind(sourceId(frames, row), DebuggerNavigation.Group.CALL_STACK, source);
                controls.add(source);
            }
        }
        rows.end();
        sources.end();
        if (maximum > 0 && scrollBounds.height() > 0) {
            int h = scrollBounds.height();
            int thumb = Math.min(h, Math.max(8, h * capacity / frames.size()));
            int x = bounds.x() + bounds.width() - 4;
            graphics.fill(x, scrollBounds.y(), x + 2, scrollBounds.y() + h, DebuggerTheme.color(BORDER));
            int top = scrollBounds.y() + (h - thumb) * offset / maximum;
            graphics.fill(x, top, x + 2, top + thumb, DebuggerTheme.color(SCROLLBAR));
            scrollbars.add("call-stack", false, x, scrollBounds.y(), h, 2, thumb, offset, maximum, value -> offset = value);
        }
        return controls;
    }

    /** Keyboard entry (Shift+F10) opens the focused row's menu. */
    boolean openContextMenu(@Nullable GuiEventListener focused) {
        Runnable menu = focused instanceof DebuggerButton button ? menus.get(button) : null;
        if (menu == null) return false;
        menu.run();
        return true;
    }

    boolean scroll(double x, double y, double amount) {
        if (!scrollBounds.contains(x, y) || amount == 0) return false;
        offset = Math.clamp(offset + (amount > 0 ? -1 : 1), 0, maximum);
        return true;
    }

    /** An action applies only to the frame it was drawn for, in the stop it was drawn in. */
    private Runnable guarded(@Nullable PauseSnapshot snapshot, CallFrame frame, int index, Runnable action) {
        return () -> {
            if (state.snapshot() != snapshot) return;
            List<CallFrame> current = state.displayedCallStack();
            if (index >= current.size() || !current.get(index).equals(frame)) return;
            action.run();
        };
    }

    private void openMenu(@Nullable PauseSnapshot snapshot, CallFrame frame, int index, Bounds anchor,
                          DebuggerNavigation navigation, String focusId) {
        Screen parent = host.get();
        if (parent == null || state.snapshot() != snapshot) return;
        String location = label(frame.location());
        var menu = new DebuggerContextMenu(parent, Component.translatable("codon.ui.callstack.title"), anchor,
            Component.literal(location), () -> {
                if (state.snapshot() != snapshot) return List.of();
                List<DebuggerContextMenu.Item> items = new ArrayList<>();
                items.add(new DebuggerContextMenu.Item(Component.translatable("codon.ui.call_stack.show_flow"),
                    DebuggerIcon.COMMAND, true, null, guarded(snapshot, frame, index, () -> {
                        state.selectCallFrame(index);
                        selectionChanged.run();
                    })));
                if (frame.location() instanceof SourceLocation.Function)
                    items.add(new DebuggerContextMenu.Item(Component.translatable("codon.ui.call_stack.open_source"),
                        DebuggerIcon.SOURCE_FILE, canOpenSource(frame), null,
                        guarded(snapshot, frame, index, () -> openSource(frame))));
                items.add(new DebuggerContextMenu.Item(Component.translatable("codon.ui.call_stack.copy_location"),
                    DebuggerIcon.COPY_UUID, true, null,
                    () -> Minecraft.getInstance().keyboardHandler.setClipboard(location)));
                return items;
            }, event -> false, () -> navigation.requestFocus(focusId));
        ScreenLayers.open(parent, menu);
    }

    private boolean canOpenSource(CallFrame frame) {
        return frame.location() instanceof SourceLocation.Function && CodonClientMod.sources() != null
            && host.get() instanceof CodonScreen;
    }

    private void openSource(CallFrame frame) {
        var sources = CodonClientMod.sources();
        if (!(frame.location() instanceof SourceLocation.Function function) || sources == null
            || !(host.get() instanceof CodonScreen screen)) return;
        sources.selectAt(function.location());
        Minecraft.getInstance().gui.setScreen(new FunctionSourceScreen(screen, sources));
    }

    private static String rowId(List<CallFrame> frames, int index) {
        return "call-frame-" + index + "-" + frames.get(index).invocationId();
    }

    private static String sourceId(List<CallFrame> frames, int index) {
        return "call-frame-source-" + index + "-" + frames.get(index).invocationId();
    }

    private static int revealRow(int row, int offset, int capacity) {
        if (row < offset) return row;
        return row >= offset + capacity ? row - capacity + 1 : offset;
    }

    static String label(SourceLocation location) {
        return switch (location) {
            case SourceLocation.Function function -> function.location().function() + ":" + function.location().line();
            case SourceLocation.Block block -> tr("codon.ui.command_block") + " " + block.block().x() + ", "
                + block.block().y() + ", " + block.block().z();
            case SourceLocation.Player player -> player.name();
        };
    }

    private static Component tooltip(CallFrame frame) {
        return ClientFormatting.sourceLocation(frame.location()).copy().append("\n" + frame.command().text());
    }

    private static String tr(String key, Object... args) { return Component.translatable(key, args).getString(); }
}
