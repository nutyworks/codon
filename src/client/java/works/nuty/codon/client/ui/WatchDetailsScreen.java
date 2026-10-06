package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientWatchState;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import works.nuty.codon.client.ui.layout.WatchDetailsLayout;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

import static works.nuty.codon.client.ui.DebuggerTheme.*;

/** Read-only full-text inspector for one stable Watch id. Management stays in the Watches HUD. */
public final class WatchDetailsScreen extends ScaledCodonScreen {
    private record Line(FormattedCharSequence text, int color) { }

    private final InputManager input;
    private final ClientDebuggerState state;
    private final DebuggerOverlay overlay;
    private final ScreenReturn origin;
    private final long entryId;
    private final List<AbstractWidget> tabOrder = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();
    private DebuggerButton copyValue;
    private DebuggerButton copyPath;
    private DebuggerButton edit;
    private DebuggerButton more;
    private DebuggerButton retry;
    private int left, top, panelWidth, panelHeight, offset;
    private WatchDetailsLayout layout;
    /** Rows open a compact summary first; full wrapping is available for long NBT values. */
    private boolean expanded;
    private long copiedUntil;

    public WatchDetailsScreen(InputManager input, ClientDebuggerState state, DebuggerOverlay overlay, long entryId) {
        super(WatchUi.text("details.title"), state.preferences());
        this.input = input;
        this.state = state;
        this.overlay = overlay;
        this.origin = new ScreenReturn(input, overlay);
        this.entryId = entryId;
    }

    @Override protected void init() {
        layout = WatchDetailsLayout.create(width, height, expanded);
        panelWidth = layout.panel().width();
        panelHeight = layout.panel().height();
        left = layout.panel().x();
        top = layout.panel().y();
        tabOrder.clear();
        copyValue = footerButton(layout.copyValue(), "details.copy_value", this::copyValue);
        copyPath = footerButton(layout.copyPath(), "details.copy_path", this::copyPath);
        // Native mouse dispatch focuses the initiating widget after its action returns.
        // Reuse this target so rebuilding on More/Less cannot focus a detached button.
        if (more == null) more = new DebuggerButton();
        Bounds moreBounds = layout.more();
        more.configure(moreBounds.x(), moreBounds.y(), moreBounds.width(), moreBounds.height(),
            WatchUi.text(expanded ? "details.less" : "details.more"), true, false, false, false, this::toggleExpanded);
        addRenderableWidget(more);
        edit = footerButton(layout.edit(), "edit", this::edit);
        edit.visible = edit.active = expanded;
        retry = footerButton(layout.retry(), "details.retry", () -> state.watches().retry(entryId));
        var close = footerButton(layout.close(), "close", this::onClose);
        tabOrder.addAll(List.of(copyValue, copyPath, more, edit, retry, close));
        for (int i = 0; i < tabOrder.size(); i++) tabOrder.get(i).setTabOrderGroup(i);
        setFocused(copyValue);
        rebuildLines();
    }

    private DebuggerButton footerButton(Bounds bounds, String label, Runnable action) {
        return addRenderableWidget(WatchUi.button(bounds.x(), bounds.y(), bounds.width(), bounds.height(), WatchUi.text(label), action));
    }

    private ClientWatchState.Entry entry() {
        return state.watches().displayedEntries().stream().filter(candidate -> candidate.id() == entryId).findFirst().orElse(null);
    }

    private void copyValue() {
        ClientWatchState.Entry entry = entry();
        if (entry != null) {
            Minecraft.getInstance().keyboardHandler.setClipboard(value(entry.displayedResult()));
            copiedUntil = System.nanoTime() + 4_000_000_000L;
        }
    }

    private void copyPath() {
        ClientWatchState.Entry entry = entry();
        if (entry != null) {
            WatchSpec spec = entry.spec();
            Minecraft.getInstance().keyboardHandler.setClipboard(spec.kind() == WatchSpec.Kind.SCORE ? spec.target() : spec.path());
            copiedUntil = System.nanoTime() + 4_000_000_000L;
        }
    }

    private void edit() {
        if (entry() == null) return;
        setFocused(edit);
        Minecraft.getInstance().gui.setScreen(WatchScreen.edit(input, state, overlay, entryId));
    }

    private void toggleExpanded() {
        expanded = !expanded;
        rebuildWidgets();
        setFocused(more);
    }

    private void rebuildLines() {
        lines.clear();
        ClientWatchState.Entry entry = entry();
        if (entry == null) {
            copyValue.active = copyPath.active = retry.active = edit.active = false;
            add(WatchUi.text("details.unavailable"), RED);
            offset = 0;
            return;
        }
        copyValue.active = copyPath.active = true;
        retry.active = !entry.automatic() && !state.watchReadsFailed() && state.watches().canRetry(entryId);
        edit.active = expanded;
        add(WatchUi.text("details.expression"), TEAL);
        add(Component.literal(expression(entry.spec())), TEXT);
        blank();
        add(WatchUi.text("details.binding"), TEAL);
        if (entry.spec().kind() == WatchSpec.Kind.STORAGE_NBT) add(WatchUi.text("details.storage"), TEXT);
        else if (entry.spec().scoreHolder() != null) add(WatchUi.text("details.fixed", entry.spec().scoreHolder()), TEXT);
        else if (entry.spec().executor() == null) add(WatchUi.text("details.current_context"), TEXT);
        else add(Component.literal(fixedBinding(entry)), TEXT);
        blank();
        add(WatchUi.text("details.current_value"), TEAL);
        add(Component.literal(value(entry.result(), entry.change(), entry.previousValue())), TEXT);
        if (entry.completedStep() != null) {
            blank();
            add(WatchUi.text("details.previous_executor", WatchFormatting.executorLabel(entry)), AMBER);
            add(Component.literal(value(entry.completedStep().result(), entry.completedStep().change(),
                entry.completedStep().previousValue())), TEXT);
        }
        offset = Math.clamp(offset, 0, maxOffset());
    }

    private String fixedBinding(ClientWatchState.Entry entry) {
        String name = entry.executorName().isBlank() ? "" : entry.executorName() + " ";
        return WatchUi.text("details.fixed", name + entry.spec().executor()).getString();
    }

    private static String expression(WatchSpec spec) {
        return switch (spec.kind()) {
            case SCORE -> "score: " + spec.target();
            case ENTITY_NBT -> "entity NBT: " + spec.path();
            case STORAGE_NBT -> "storage NBT: " + spec.target() + " / " + spec.path();
        };
    }

    private static String value(WatchResult result) {
        if (result == null) return WatchUi.text("pending").getString();
        return result.status() == WatchResult.Status.VALUE ? result.value() : WatchFormatting.status(result.status()).getString();
    }

    private static String value(WatchResult result, ClientWatchState.Change change, String previous) {
        String current = value(result);
        if (!change.isValueChange()) return current;
        String before = previous.isEmpty() ? WatchFormatting.status(WatchResult.Status.VALUE_MISSING).getString() : previous;
        return before + " → " + current;
    }

    private void blank() { lines.add(new Line(FormattedCharSequence.EMPTY, TEXT)); }
    private void add(Component text, int color) {
        for (FormattedCharSequence line : font.split(text, Math.max(1, panelWidth - 32))) lines.add(new Line(line, color));
    }
    private int visibleLines() { return Math.max(1, (layout.textBottom() - top - 31 + 3) / (font.lineHeight + 3)); }
    private int maxOffset() { return Math.max(0, lines.size() - visibleLines()); }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        rebuildLines();
        graphics.fill(0, 0, width, height, DebuggerTheme.color(0x70000000));
        graphics.fill(left, top, left + panelWidth, top + panelHeight, DebuggerTheme.color(PANEL));
        graphics.outline(left, top, panelWidth, panelHeight, DebuggerTheme.color(BORDER));
        graphics.fill(left, top, left + 2, top + 24, DebuggerTheme.color(TEAL));
        boolean copied = System.nanoTime() < copiedUntil;
        String confirmation = Component.translatable("codon.ui.copied").getString();
        int confirmationWidth = copied ? font.width(confirmation) + 12 : 0;
        WatchUi.line(graphics, font, title.getString(), left + 8, top + 9, panelWidth - 16 - confirmationWidth, TEXT);
        if (copied) WatchUi.line(graphics, font, confirmation,
            left + panelWidth - 8 - font.width(confirmation), top + 9, font.width(confirmation), TEAL);
        graphics.enableScissor(left + 8, top + 29, left + panelWidth - 8, layout.textBottom());
        for (int row = 0; row < visibleLines() && offset + row < lines.size(); row++) {
            Line line = lines.get(offset + row);
            graphics.text(font, line.text(), left + 10, top + 31 + row * (font.lineHeight + 3), DebuggerTheme.foreground(line.color()), false);
        }
        graphics.disableScissor();
        if (maxOffset() > 0) {
            int track = Math.max(1, layout.textBottom() - top - 30);
            int thumb = Math.max(4, track * visibleLines() / lines.size());
            int y = top + 30 + (track - thumb) * offset / maxOffset();
            graphics.fill(left + panelWidth - 5, y, left + panelWidth - 3, y + thumb, DebuggerTheme.color(TEAL));
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        offset = Math.clamp(offset - (int) Math.signum(scrollY) * 3, 0, maxOffset());
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_TAB) {
            List<AbstractWidget> eligible = tabOrder.stream().filter(widget -> widget.active && widget.visible).toList();
            if (!eligible.isEmpty()) {
                int current = eligible.indexOf(getFocused());
                setFocused(eligible.get(Math.floorMod(current + (event.hasShiftDown() ? -1 : 1), eligible.size())));
            }
            return true;
        }
        if (event.key() == InputConstants.KEY_HOME || event.key() == InputConstants.KEY_END) {
            offset = event.key() == InputConstants.KEY_HOME ? 0 : maxOffset();
            return true;
        }
        int delta = switch (event.key()) {
            case InputConstants.KEY_UP -> -1;
            case InputConstants.KEY_DOWN -> 1;
            case InputConstants.KEY_PAGEUP -> -visibleLines();
            case InputConstants.KEY_PAGEDOWN -> visibleLines();
            default -> 0;
        };
        if (delta != 0) { offset = Math.clamp(offset + delta, 0, maxOffset()); return true; }
        return super.keyPressed(event);
    }

    @Override public void onClose() { origin.restore(); }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) { }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
}
