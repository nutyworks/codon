package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Objects;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.state.ClientFunctionSourceState;
import works.nuty.codon.core.model.FunctionSourceDocument;

import static works.nuty.codon.client.ui.DebuggerTheme.*;

/** Short-lived navigation input tied to the exact loaded source, with no source edits. */
final class SourceLineJumpScreen extends ScaledCodonScreen {
    private final FunctionSourceScreen parent;
    private final ClientFunctionSourceState sources;
    private final FunctionSourceDocument document;
    private final IntConsumer jump;
    private final GuiEventListener previousFocus;
    private String draft;
    private EditBox lineNumber;
    private DebuggerButton go, cancel;
    private int left, top, panelWidth, panelHeight;

    SourceLineJumpScreen(FunctionSourceScreen parent, ClientFunctionSourceState sources,
                         FunctionSourceDocument document, int selectedLine, IntConsumer jump) {
        super(text("title"), preferencesFor(parent));
        this.parent = parent;
        this.sources = sources;
        this.document = document;
        this.jump = jump;
        previousFocus = parent.getFocused();
        draft = selectedLine >= 1 && selectedLine <= document.lines().size() ? Integer.toString(selectedLine) : "";
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("codon.source.go_to_line." + key, args);
    }

    private boolean current() {
        return minecraft.gui.screen() == parent && sources.sourceStatus() == ClientFunctionSourceState.Status.READY
            && sources.document() == document && Objects.equals(sources.selected(), document.id());
    }

    private boolean validContext() {
        if (current()) return true;
        ScreenLayers.close(this);
        parent.setFocused(null);
        return false;
    }

    private int parsedLine() {
        try {
            int line = Integer.parseInt(draft.strip());
            return line >= 1 && line <= document.lines().size() ? line : -1;
        } catch (NumberFormatException invalid) { return -1; }
    }

    private Component hint() {
        return text(draft.isBlank() || parsedLine() > 0 ? "range" : "invalid", document.lines().size());
    }

    @Override protected void init() {
        clearWidgets();
        panelWidth = Math.max(1, Math.min(260, width - 12));
        left = (width - panelWidth) / 2;
        go = null;
        lineNumber = addRenderableWidget(new DebuggerEditBox(font, left + 8, 0, Math.max(1, panelWidth - 16), 20,
            text("range", document.lines().size())));
        lineNumber.setMaxLength(9);
        lineNumber.setValue(draft);
        lineNumber.setResponder(value -> { draft = value; refreshControls(); });
        lineNumber.setHint(text("range", document.lines().size()));
        int half = Math.max(1, (panelWidth - 20) / 2);
        go = addRenderableWidget(WatchUi.button(left + 8, 0, half, 20, text("go"), this::go)).withOpaqueColors();
        cancel = addRenderableWidget(WatchUi.button(left + 12 + half, 0, half, 20,
            Component.translatable("gui.cancel"), this::onClose)).withOpaqueColors();
        refreshControls();
        setFocused(lineNumber);
        lineNumber.setHighlightPos(0);
    }

    private void refreshControls() {
        if (go == null) return;
        panelHeight = 96 + font.split(hint(), Math.max(1, panelWidth - 16)).size() * (font.lineHeight + 1);
        top = Math.max(6, (height - panelHeight) / 2);
        lineNumber.setY(top + 29);
        go.setY(top + panelHeight - 28);
        cancel.setY(go.getY());
        go.active = current() && parsedLine() > 0;
    }

    private void go() {
        if (!validContext()) return;
        int line = parsedLine();
        if (line < 1) return;
        ScreenLayers.close(this);
        jump.accept(line);
    }

    @Override public void tick() { validContext(); }

    @Override public boolean keyPressed(KeyEvent event) {
        if (!validContext()) return true;
        if (event.key() == InputConstants.KEY_ESCAPE) { onClose(); return true; }
        if ((event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)
            && getFocused() == lineNumber) { go(); return true; }
        if (event.key() == InputConstants.KEY_TAB) {
            var eligible = children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                .filter(widget -> widget.visible && widget.active).toList();
            int index = eligible.indexOf(getFocused());
            setFocused(eligible.get(Math.floorMod(index + (event.hasShiftDown() ? -1 : 1), eligible.size())));
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public void onClose() {
        ScreenLayers.close(this);
        parent.setFocused(null);
        if (current() && previousFocus instanceof AbstractWidget previous)
            parent.children().stream().filter(child -> child.getClass() == previous.getClass()).map(AbstractWidget.class::cast)
                .filter(widget -> widget.visible && widget.active && widget.getMessage().equals(previous.getMessage()))
                .findFirst().ifPresent(parent::setFocused);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
        if (!validContext()) return;
        refreshControls();
        graphics.fill(0, 0, width, height, 0x70000000);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, WORKSPACE);
        graphics.outline(left, top, panelWidth, panelHeight, BORDER);
        WatchUi.line(graphics, font, title.getString(), left + 8, top + 9, panelWidth - 16, TEXT);
        int hintY = top + 58;
        for (var line : font.split(hint(), Math.max(1, panelWidth - 16))) {
            graphics.text(font, line, left + 8, hintY, draft.isBlank() || parsedLine() > 0 ? MUTED : AMBER, false);
            hintY += font.lineHeight + 1;
        }
        super.extractRenderState(graphics, x, y, delta);
    }

    @Override public void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float delta) { }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
}
