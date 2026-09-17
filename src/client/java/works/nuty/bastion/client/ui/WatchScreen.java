package works.nuty.bastion.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import works.nuty.bastion.client.input.InputManager;
import works.nuty.bastion.client.state.ClientDebuggerState;
import works.nuty.bastion.client.state.ClientWatchState;
import works.nuty.bastion.core.model.EntityRef;
import works.nuty.bastion.core.model.PauseSource;
import works.nuty.bastion.core.model.WatchResult;
import works.nuty.bastion.core.model.WatchSpec;

import java.util.List;

import static works.nuty.bastion.client.ui.DebuggerTheme.*;

/** Transparent editor for the small persistent set of server-evaluated watches. */
public final class WatchScreen extends Screen {
    private final InputManager input;
    private final ClientDebuggerState state;
    private final DebuggerOverlay overlay;
    private WatchSpec.Kind kind = WatchSpec.Kind.SCORE;
    private int offset;
    private String targetDraft = "";
    private String pathDraft = "";
    private String feedback = "";
    private EditBox target;
    private EditBox path;
    private final List<PinControl> pinControls = new java.util.ArrayList<>();

    public WatchScreen(InputManager input, ClientDebuggerState state, DebuggerOverlay overlay) {
        super(Component.translatable("bastion.watch.title"));
        this.input = input;
        this.state = state;
        this.overlay = overlay;
    }

    @Override
    protected void init() {
        rememberDrafts();
        clearWidgets();
        pinControls.clear();
        int panelWidth = Math.min(500, width - 24);
        int x = (width - panelWidth) / 2;
        int y = Math.max(18, (height - 250) / 2);
        int editorY = y + 44;
        int buttonWidth = 78;

        addRenderableWidget(configure(new DebuggerButton(), x + 8, editorY, buttonWidth, 20,
            typeLabel(), true, false, () -> { kind = WatchSpec.Kind.values()[(kind.ordinal() + 1) % WatchSpec.Kind.values().length]; rebuildWidgets(); }));
        int fieldX = x + 16 + buttonWidth;
        int addX = x + panelWidth - 56;
        int combinedWidth = Math.max(32, addX - 6 - fieldX);
        int fieldWidth = Math.max(16, (combinedWidth - 6) / 2);
        target = addRenderableWidget(field(fieldX, editorY,
            kind == WatchSpec.Kind.STORAGE_NBT ? fieldWidth : combinedWidth, targetHint()));
        path = addRenderableWidget(field(kind == WatchSpec.Kind.STORAGE_NBT ? fieldX + fieldWidth + 6 : fieldX, editorY,
            kind == WatchSpec.Kind.STORAGE_NBT ? fieldWidth : combinedWidth, pathHint()));
        if (kind == WatchSpec.Kind.SCORE) path.setVisible(false);
        if (kind == WatchSpec.Kind.ENTITY_NBT) target.setVisible(false);
        target.setValue(targetDraft);
        path.setValue(pathDraft);
        addRenderableWidget(configure(new DebuggerButton(), addX, editorY, 48, 20,
            Component.translatable("bastion.watch.add"), true, false, this::addWatch));
        addRenderableWidget(configure(new DebuggerButton(), x + panelWidth - 48, y + 3, 40, 18,
            Component.translatable("bastion.watch.close"), true, false, this::onClose));

        List<ClientWatchState.Entry> entries = state.watches().entries();
        offset = Math.clamp(offset, 0, Math.max(0, entries.size() - visibleRows()));
        int listY = editorY + 31;
        for (int index = 0; index < visibleRows() && offset + index < entries.size(); index++) {
            ClientWatchState.Entry entry = entries.get(offset + index);
            int rowY = listY + index * ROW_HEIGHT;
            int removeX = x + panelWidth - 60;
            if (supportsExecutorBinding(entry.spec())) {
                DebuggerButton pin = pinButton(entry.id(), removeX - 22, rowY + 3);
                addRenderableWidget(pin);
                pinControls.add(new PinControl(entry.id(), pin));
            }
            DebuggerButton remove = configure(new DebuggerButton(), removeX, rowY + 3, 52, 18,
                Component.translatable("bastion.watch.remove"), true, false,
                () -> { state.watches().remove(entry.id()); rebuildWidgets(); });
            addRenderableWidget(remove);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int panelWidth = Math.min(500, width - 24);
        int x = (width - panelWidth) / 2;
        int y = Math.max(18, (height - 250) / 2);
        List<ClientWatchState.Entry> entries = state.watches().entries();
        updatePinControls(entries);
        offset = Math.clamp(offset, 0, Math.max(0, entries.size() - visibleRows()));
        int panelHeight = Math.min(height - y - 12, 88 + Math.max(1, Math.min(visibleRows(), entries.size())) * ROW_HEIGHT);
        graphics.fill(x, y, x + panelWidth, y + panelHeight, PANEL);
        graphics.outline(x, y, panelWidth, panelHeight, BORDER);
        graphics.fill(x, y, x + 2, y + 25, TEAL);
        graphics.text(font, title, x + 8, y + 8, TEXT, false);

        for (EditBox field : List.of(target, path)) {
            if (field.isVisible()) {
                drawTrimmed(graphics, field.getMessage().getString(), field.getX(), field.getY() - 11,
                    field.getWidth(), MUTED);
            }
        }

        int rowY = y + 75;
        if (!feedback.isEmpty()) graphics.text(font, feedback, x + 8, y + 65, RED, false);
        if (entries.isEmpty()) {
            graphics.text(font, Component.translatable(state.isPaused() ? "bastion.watch.empty" : "bastion.watch.running").getString(),
                x + 8, rowY, MUTED, false);
        }
        for (int index = 0; index < visibleRows() && offset + index < entries.size(); index++) {
            ClientWatchState.Entry entry = entries.get(offset + index);
            boolean changed = entry.displayedChange().isValueChange();
            int valueColor = changed ? AMBER : (entry.displayedResult() != null && entry.displayedResult().status() == WatchResult.Status.VALUE ? TEXT : MUTED);
            int rowWidth = panelWidth - (supportsExecutorBinding(entry.spec()) ? 100 : 76);
            WatchRowRenderer.render(graphics, font, entry, state.isPaused(), x + 8, rowY, rowWidth,
                changed ? AMBER : TEXT, valueColor);
            if (mouseX >= x + 8 && mouseX < x + 8 + rowWidth && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
                graphics.setComponentTooltipForNextFrame(font, WatchFormatting.tooltip(entry, state.isPaused()), mouseX, mouseY);
            }
            rowY += ROW_HEIGHT;
        }
        if (entries.size() > visibleRows()) {
            graphics.text(font, (offset + 1) + "–" + Math.min(entries.size(), offset + visibleRows()) + " / " + entries.size(),
                x + panelWidth - 82, y + panelHeight - 10, MUTED, false);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) { }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (input.handleScreenKey(event)) return true;
        if (input.menuKey.matches(event) && !isInputCaptured()) {
            while (input.menuKey.consumeClick()) { }
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        List<ClientWatchState.Entry> entries = state.watches().entries();
        int maximum = Math.max(0, entries.size() - visibleRows());
        if (maximum == 0 || scrollY == 0) return super.mouseScrolled(x, y, scrollX, scrollY);
        int next = Math.clamp(offset + (scrollY > 0 ? -1 : 1), 0, maximum);
        if (next == offset) return true;
        offset = next;
        rebuildWidgets();
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(new BastionScreen(input, overlay));
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }

    private void addWatch() {
        try {
            String requestedTarget = target.getValue();
            String requestedPath = path.getValue();
            WatchSpec spec = switch (kind) {
                case SCORE -> new WatchSpec(kind, requestedTarget, "");
                case ENTITY_NBT -> new WatchSpec(kind, "", requestedPath);
                case STORAGE_NBT -> new WatchSpec(kind, requestedTarget, requestedPath);
            };
            if (state.watches().add(spec)) {
                target.setValue("");
                path.setValue("");
                targetDraft = pathDraft = "";
                feedback = "";
                rebuildWidgets();
            } else feedback = tr("bastion.watch.feedback.duplicate");
        } catch (IllegalArgumentException ignored) {
            feedback = tr("bastion.watch.feedback.invalid");
        }
    }

    private DebuggerButton pinButton(long id, int x, int y) {
        DebuggerButton pin = new DebuggerButton();
        configurePin(pin, id, x, y, null);
        return pin;
    }

    /** Runs each frame: source selection can change through F9 while this screen stays open. */
    private void updatePinControls(List<ClientWatchState.Entry> entries) {
        for (PinControl control : pinControls) {
            ClientWatchState.Entry entry = entries.stream().filter(candidate -> candidate.id() == control.id()).findFirst().orElse(null);
            if (entry != null) configurePin(control.button(), entry.id(), control.button().getX(), control.button().getY(), entry);
        }
    }

    private void configurePin(DebuggerButton button, long id, int x, int y, ClientWatchState.Entry entry) {
        ClientWatchState.Entry current = entry == null ? state.watches().entries().stream()
            .filter(candidate -> candidate.id() == id).findFirst().orElse(null) : entry;
        boolean pinned = current != null && current.spec().isPinned();
        EntityRef selected = selectedExecutor();
        boolean active = pinned || selected != null;
        Component label = Component.translatable(pinned ? "bastion.watch.unpin" : "bastion.watch.pin");
        button.configure(x, y, 18, 18, label, active, pinned, false, false, () -> togglePin(id));
        button.withIcon(DebuggerIcon.PIN);
        Component tooltip = pinned ? Component.translatable("bastion.watch.tooltip.unpin")
            : selected == null ? Component.translatable("bastion.watch.pin_unavailable")
            : Component.translatable("bastion.watch.tooltip.pin", selected.name());
        button.setTooltip(Tooltip.create(tooltip));
    }

    /** The id is stable, while source and executor are intentionally resolved only at click time. */
    private void togglePin(long id) {
        ClientWatchState.Entry entry = state.watches().entries().stream().filter(candidate -> candidate.id() == id).findFirst().orElse(null);
        if (entry == null || !supportsExecutorBinding(entry.spec())) return;
        boolean changed;
        if (entry.spec().isPinned()) {
            changed = state.watches().unpin(id);
        } else {
            EntityRef executor = selectedExecutor();
            if (executor == null) {
                feedback = tr("bastion.watch.feedback.pin_unavailable");
                return;
            }
            changed = state.watches().pin(id, executor);
        }
        if (!changed) feedback = tr("bastion.watch.feedback.duplicate");
        else feedback = "";
        rebuildWidgets();
    }

    private EntityRef selectedExecutor() {
        if (!state.isPaused()) return null;
        PauseSource source = state.selectedSource();
        return source == null ? null : source.entity();
    }

    /** Score and entity-NBT queries can follow an executor; storage queries are global. */
    private static boolean supportsExecutorBinding(WatchSpec spec) {
        return spec.kind() == WatchSpec.Kind.SCORE || spec.kind() == WatchSpec.Kind.ENTITY_NBT;
    }

    private int visibleRows() { return Math.max(1, (height - Math.max(18, (height - 250) / 2) - 88) / ROW_HEIGHT); }

    private void rememberDrafts() {
        if (target != null) targetDraft = target.getValue();
        if (path != null) pathDraft = path.getValue();
    }

    private EditBox field(int x, int y, int fieldWidth, Component hint) {
        EditBox field = new EditBox(font, x, y, fieldWidth, 20, hint);
        field.setMaxLength(WatchSpec.MAX_INPUT_LENGTH);
        field.setHint(hint);
        field.setTooltip(Tooltip.create(hint));
        return field;
    }

    private Component typeLabel() { return Component.translatable("bastion.watch.kind." + kind.name().toLowerCase(java.util.Locale.ROOT)); }
    private Component targetHint() { return Component.translatable(kind == WatchSpec.Kind.SCORE ? "bastion.watch.objective" : "bastion.watch.storage"); }
    private Component pathHint() { return Component.translatable(kind == WatchSpec.Kind.SCORE ? "bastion.watch.no_path" : "bastion.watch.path"); }
    private static String tr(String key) { return Component.translatable(key).getString(); }

    private static final int ROW_HEIGHT = 34;
    private record PinControl(long id, DebuggerButton button) { }

    private static DebuggerButton configure(DebuggerButton button, int x, int y, int width, int height,
                                            Component label, boolean active, boolean selected, Runnable action) {
        button.configure(x, y, width, height, label, active, selected, false, false, action);
        button.setTooltip(Tooltip.create(label));
        return button;
    }

    private void drawTrimmed(GuiGraphicsExtractor graphics, String text, int x, int y, int available, int color) {
        String result = font.width(text) <= available ? text : font.plainSubstrByWidth(text, Math.max(0, available - font.width("…"))) + "…";
        graphics.enableScissor(x, y, x + available, y + font.lineHeight + 1);
        graphics.text(font, result, x, y, color, false);
        graphics.disableScissor();
    }
}
