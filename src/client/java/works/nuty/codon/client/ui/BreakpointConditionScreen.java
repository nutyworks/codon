package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.network.ClientNetworking;
import works.nuty.codon.client.state.ClientBreakpointState;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.client.state.BreakpointTargetPolicy;
import works.nuty.codon.client.ui.layout.CommandFlowLayout;
import works.nuty.codon.core.model.BreakpointCondition;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;

import static works.nuty.codon.client.ui.DebuggerTheme.*;

/** Modal condition widgets; hosted by ScreenLayers, never installed as the active screen. */
public final class BreakpointConditionScreen extends ScaledCodonScreen {
    private enum Menu { NONE, KIND, COMPARISON }
    private static final int MENU_ROW_HEIGHT = 18;
    private static final long HOVER_DELAY = 180_000_000L;
    private static final long LEAVE_DELAY = 220_000_000L;
    private static final long TOGGLE_DELAY = 250_000_000L;
    /** Position of the control that opened this editor, in GUI pixels. */
    public record Anchor(int x, int y, int width, int height) { }

    private final Screen parent;
    private final ClientDebuggerState state;
    private final BreakpointDefinition original;
    private final BreakpointTarget markerTarget;
    private final Anchor anchor;
    private BreakpointCondition.Kind kind;
    private BreakpointCondition.Comparison comparison;
    private String thresholdText;
    private EditBox threshold;
    private DebuggerButton saveButton;
    private DebuggerButton deleteButton;
    private DebuggerButton kindButton;
    private DebuggerButton comparisonButton;
    private final List<DebuggerButton> kinds = new ArrayList<>();
    private final List<DebuggerButton> comparisons = new ArrayList<>();
    private boolean saving;
    private boolean deleting;
    private boolean sendFailed;
    private boolean previewRequested;
    private int left, top, panelWidth, panelHeight;
    private Menu menu = Menu.NONE;
    private Menu hoverMenu = Menu.NONE;
    private Menu suppressedMenu = Menu.NONE;
    private long hoverStarted = -1, leaveStarted = -1;
    private long menuOpenedAt;
    private boolean keyboardMenu;
    private int lastMouseX = -1, lastMouseY = -1;
    private int menuLeft, menuTop, menuWidth, menuHeight, menuRows, menuScroll;

    public BreakpointConditionScreen(Screen parent, ClientDebuggerState state, BreakpointDefinition definition) {
        this(parent, state, definition, null);
    }

    public BreakpointConditionScreen(Screen parent, ClientDebuggerState state, BreakpointDefinition definition,
                                     Anchor anchor) {
        this(parent, state, definition, definition.target(), anchor);
    }

    public BreakpointConditionScreen(Screen parent, ClientDebuggerState state, BreakpointDefinition definition,
                                     BreakpointTarget markerTarget, Anchor anchor) {
        super(Component.translatable("codon.breakpoint.condition_title"), state.preferences());
        this.parent = parent;
        this.state = state;
        this.original = definition;
        this.markerTarget = markerTarget;
        this.anchor = localAnchor(parent, anchor);
        this.kind = definition.condition().kind();
        this.comparison = definition.condition().comparison();
        this.thresholdText = Integer.toString(definition.condition().threshold());
    }

    public boolean editsMarker(BreakpointTarget target, String command) {
        return BreakpointTargetPolicy.editedMarker(target, markerTarget, markerDefinition(), command);
    }

    public BreakpointDefinition markerDefinition() {
        var acknowledged = state.breakpoints().get(original.target());
        return acknowledged == null ? original.withEnabled(false) : acknowledged;
    }

    @Override protected void init() {
        clearWidgets();
        kinds.clear();
        comparisons.clear();
        menu = hoverMenu = suppressedMenu = Menu.NONE;
        hoverStarted = leaveStarted = -1;
        panelWidth = Math.max(1, Math.min(300, width - 12));
        panelHeight = contentHeight();
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        if (anchor != null && width >= 520 && height >= 280) {
            left = Math.clamp(anchor.x() + anchor.width() / 2 - panelWidth / 2, 6, width - panelWidth - 6);
            int below = anchor.y() + anchor.height() + 4;
            int above = anchor.y() - panelHeight - 4;
            top = below + panelHeight <= height - 6 ? below
                : above >= 6 ? above : Math.clamp(top, 6, height - panelHeight - 6);
        }
        if (!previewRequested) {
            var preview = state.stagePreviews().get(original.target().location());
            // An exact READY stage preview already validates these offsets. Keeping
            // it also preserves an unobserved Flow selection while its editor is open.
            previewRequested = !original.target().wholeCommand() && preview != null
                && preview.status() == ClientStagePreviewState.Status.READY
                && original.target().commandFingerprint().equals(BreakpointTarget.fingerprint(preview.savedCommand()))
                || ClientNetworking.requestStagePreview(state, original.target().location());
        }
        kindButton = addRenderableWidget(WatchUi.button(left + 8, top + 58, panelWidth - 16, 20,
            Component.empty(), () -> openMenu(Menu.KIND, false))).withTextPadding(6);
        comparisonButton = addRenderableWidget(WatchUi.button(left + panelWidth - 116, top + 58, 40, 20,
            Component.empty(), () -> openMenu(Menu.COMPARISON, false))).withTextPadding(4);
        threshold = addRenderableWidget(new DebuggerEditBox(font, left + panelWidth - 72, top + 58,
            64, 20, Component.translatable("codon.breakpoint.count")));
        threshold.setMaxLength(9);
        threshold.setValue(thresholdText);
        threshold.setResponder(value -> { thresholdText = value; refreshControls(); });
        threshold.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("codon.breakpoint.count")));
        saveButton = addRenderableWidget(WatchUi.button(left + panelWidth - 60, top, 52, 20,
            Component.translatable("codon.breakpoint.save"), this::save));
        saveButton.withStatusColor(TEAL, TEAL_SURFACE);
        addRenderableWidget(WatchUi.button(left + panelWidth - 28, top + 5, 20, 20,
            Component.translatable("codon.breakpoint.cancel"), this::onClose)).withIcon(DebuggerIcon.REMOVE);
        deleteButton = addRenderableWidget(WatchUi.button(left + 8, top, 28, 20,
            Component.translatable("codon.breakpoint.delete"), this::delete))
            .withIcon(DebuggerIcon.DELETE).withStatusColor(RED, RED_SURFACE);
        for (var value : BreakpointCondition.Kind.values()) {
            kinds.add(menuOption(Component.literal(kindLabel(value)), () -> {
                kind = value;
                closeMenu();
                setFocused(kindButton);
                refreshControls();
            }).withTextIcon(kindIcon(value)));
        }
        for (var value : BreakpointCondition.Comparison.values()) {
            comparisons.add(menuOption(Component.literal(symbol(value)), () -> {
                comparison = value;
                closeMenu();
                setFocused(comparisonButton);
                refreshControls();
            }));
        }
        refreshControls();
        setFocused(kindButton);
    }

    private DebuggerButton menuOption(Component label, Runnable action) {
        DebuggerButton button = new DebuggerButton();
        button.configure(0, 0, 1, MENU_ROW_HEIGHT, label, false, false, true, false, action);
        button.visible = false;
        // Menu widgets participate in focus/narration, but render above the form separately.
        return addWidget(button.withTextPadding(6).withOpaqueColors());
    }

    private static DebuggerIcon kindIcon(BreakpointCondition.Kind kind) {
        return switch (kind) {
            case ALWAYS -> DebuggerIcon.BREAKPOINT;
            case CREATED -> DebuggerIcon.SOURCE_CREATED;
            case REMOVED -> DebuggerIcon.SOURCE_EXCLUDED;
            case CHANGED -> DebuggerIcon.EDIT;
            case INPUT_COUNT -> DebuggerIcon.INPUT_COUNT;
            case OUTPUT_COUNT -> DebuggerIcon.OUTPUT_COUNT;
            case CREATED_COUNT -> DebuggerIcon.CREATED_COUNT;
            case REMOVED_COUNT -> DebuggerIcon.REMOVED_COUNT;
            case CHANGED_COUNT -> DebuggerIcon.CHANGED_COUNT;
        };
    }

    private boolean validCount() {
        if (!kind.isCount()) return true;
        try { return Integer.parseInt(thresholdText) >= 0; }
        catch (NumberFormatException invalid) { return false; }
    }

    private boolean supportedCondition() {
        if (!kind.isCount() && !kind.isEvent()) return true;
        ClientStagePreviewState.Preview preview = state.stagePreviews().get(original.target().location());
        if (preview == null || preview.status() != ClientStagePreviewState.Status.READY) return false;
        if (original.target().wholeCommand()) return preview.spans().stream().anyMatch(span -> !span.terminal());
        if (!original.target().commandFingerprint().equals(BreakpointTarget.fingerprint(preview.savedCommand()))) return false;
        int index = original.target().stageIndex();
        return index >= 0 && index < preview.spans().size() && !preview.spans().get(index).terminal();
    }

    private static String kindLabel(BreakpointCondition.Kind value) {
        return BreakpointUi.kindLabel(value);
    }

    private void refreshControls() {
        for (var value : BreakpointCondition.Kind.values()) kinds.get(value.ordinal()).setSelected(kind == value);
        for (var value : BreakpointCondition.Comparison.values()) {
            comparisons.get(value.ordinal()).setSelected(comparison == value);
        }
        kindButton.setMessage(Component.translatable("codon.breakpoint.condition_select", kind.isCount()
            ? tr("codon.breakpoint.short." + kind.name().toLowerCase(java.util.Locale.ROOT)) : kindLabel(kind)));
        kindButton.setWidth(panelWidth - (kind.isCount() ? 128 : 16));
        kindButton.withHorizontalViewport(0, kindButton.getWidth());
        comparisonButton.setMessage(Component.translatable("codon.breakpoint.compare_select", symbol(comparison)));
        comparisonButton.visible = comparisonButton.active = kind.isCount();
        threshold.visible = threshold.active = kind.isCount();
        if (saveButton != null) saveButton.active = validCount() && supportedCondition() && state.breakpoints().ready()
            && !state.breakpoints().pending(original.target());
        if (deleteButton != null) {
            deleteButton.active = state.breakpoints().ready() && !state.breakpoints().pending(original.target());
            panelHeight = contentHeight();
            int nextTop = Math.max(6, Math.min(top, height - panelHeight - 6));
            if (nextTop != top) {
                // A bottom-anchored popup must still fit when server feedback adds a row.
                for (var child : children()) if (child instanceof AbstractWidget widget)
                    widget.setY(widget.getY() + nextTop - top);
                top = nextTop;
            }
            saveButton.setY(top + panelHeight - 28);
            deleteButton.setY(top + panelHeight - 28);
        }
        layoutMenu();
    }

    private DebuggerButton trigger(Menu value) { return value == Menu.KIND ? kindButton : comparisonButton; }
    private List<DebuggerButton> options() { return menu == Menu.KIND ? kinds : comparisons; }
    private int selectedOption() { return menu == Menu.KIND ? kind.ordinal() : comparison.ordinal(); }

    private void openMenu(Menu value, boolean keyboard) {
        if (menu != Menu.NONE) closeMenu();
        menu = value;
        menuOpenedAt = System.nanoTime();
        keyboardMenu = keyboard;
        hoverMenu = suppressedMenu = Menu.NONE;
        hoverStarted = leaveStarted = -1;
        menuScroll = 0;
        layoutMenu();
        revealOption(selectedOption());
        if (keyboard) setFocused(options().get(selectedOption()));
    }

    private void closeMenu() {
        if (menu == Menu.NONE) return;
        if (options().contains(getFocused())) setFocused(trigger(menu));
        suppressedMenu = menu;
        menu = hoverMenu = Menu.NONE;
        hoverStarted = leaveStarted = -1;
        layoutMenu();
    }

    private void layoutMenu() {
        for (var option : kinds) option.visible = option.active = false;
        for (var option : comparisons) option.visible = option.active = false;
        if (menu == Menu.NONE) return;
        DebuggerButton source = trigger(menu);
        int below = height - source.getBottom() - 8;
        int above = source.getY() - 8;
        boolean openBelow = below >= options().size() * MENU_ROW_HEIGHT + 2 || below >= above;
        menuRows = Math.min(options().size(), Math.max(1, ((openBelow ? below : above) - 2) / MENU_ROW_HEIGHT));
        menuHeight = menuRows * MENU_ROW_HEIGHT + 2;
        menuWidth = Math.min(width - 12, Math.max(source.getWidth(), options().stream()
            .mapToInt(option -> font.width(option.getMessage()) + 22).max().orElse(40)));
        menuLeft = Math.clamp(source.getX(), 6, width - menuWidth - 6);
        menuTop = openBelow ? source.getBottom() + 2 : source.getY() - menuHeight - 2;
        menuScroll = Math.clamp(menuScroll, 0, options().size() - menuRows);
        for (int i = menuScroll; i < menuScroll + menuRows; i++) {
            DebuggerButton option = options().get(i);
            option.setPosition(menuLeft + 1, menuTop + 1 + (i - menuScroll) * MENU_ROW_HEIGHT);
            option.setWidth(menuWidth - 5);
            // DebuggerButton keeps text-layout width separately from its hit box.
            option.withHorizontalViewport(0, option.getWidth());
            option.visible = option.active = true;
        }
    }

    private void revealOption(int index) {
        if (index < menuScroll) menuScroll = index;
        else if (index >= menuScroll + menuRows) menuScroll = index - menuRows + 1;
        layoutMenu();
    }

    private boolean overMenu(double x, double y) {
        return menu != Menu.NONE && x >= menuLeft && x < menuLeft + menuWidth
            && y >= menuTop && y < menuTop + menuHeight;
    }

    private void updateMenuHover(int mouseX, int mouseY) {
        if (lastMouseX >= 0 && (mouseX != lastMouseX || mouseY != lastMouseY)) keyboardMenu = false;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        long now = System.nanoTime();
        // A popup can overlap the other trigger: its rows always own that hit area.
        Menu hovered = overMenu(mouseX, mouseY) ? Menu.NONE : kindButton.isMouseOver(mouseX, mouseY) ? Menu.KIND
            : comparisonButton.visible && comparisonButton.isMouseOver(mouseX, mouseY) ? Menu.COMPARISON : Menu.NONE;
        if (hovered != suppressedMenu) suppressedMenu = Menu.NONE;
        if (hovered != hoverMenu) { hoverMenu = hovered; hoverStarted = now; }
        if (hovered != Menu.NONE && hovered != menu && hovered != suppressedMenu && now - hoverStarted >= HOVER_DELAY)
            openMenu(hovered, false);
        if (menu == Menu.NONE || keyboardMenu) return;
        DebuggerButton source = trigger(menu);
        boolean bridge = mouseX >= source.getX() && mouseX < source.getRight()
            && mouseY >= Math.min(source.getY(), menuTop) && mouseY < Math.max(source.getBottom(), menuTop + menuHeight);
        if (overMenu(mouseX, mouseY) || bridge) leaveStarted = -1;
        else if (leaveStarted < 0) leaveStarted = now;
        else if (now - leaveStarted >= LEAVE_DELAY) {
            closeMenu();
            // The pointer already left both hit areas. A prompt re-entry must open
            // again even if no intervening frame observes the outside position.
            suppressedMenu = Menu.NONE;
        }
    }

    private static String symbol(BreakpointCondition.Comparison comparison) {
        return switch (comparison) {
            case EQ -> "="; case NE -> "≠"; case LT -> "<";
            case LE -> "≤"; case GT -> ">"; case GE -> "≥";
        };
    }

    private BreakpointCondition draft() {
        return new BreakpointCondition(kind, kind.isCount() ? comparison : BreakpointCondition.Comparison.EQ,
            kind.isCount() ? Integer.parseInt(thresholdText) : 0);
    }

    private void save() {
        if (!validCount() || !supportedCondition() || state.breakpoints().pending(original.target())) return;
        deleting = false;
        BreakpointDefinition current = state.breakpoints().get(original.target());
        saving = ClientNetworking.sendBreakpointEdit(state, ClientBreakpointState.Action.SAVE,
            (current == null ? original : current).withCondition(draft()).withEnabled(true));
        sendFailed = !saving;
        refreshControls();
    }

    private void delete() {
        if (state.breakpoints().pending(original.target())) return;
        saving = false;
        deleting = ClientNetworking.sendBreakpointEdit(state, ClientBreakpointState.Action.DELETE, original);
        sendFailed = !deleting;
        refreshControls();
    }

    private String hint() {
        if (!validCount()) return tr("codon.breakpoint.invalid_count");
        if (kind != BreakpointCondition.Kind.ALWAYS && previewLoading()) return tr("codon.breakpoint.loading_stages");
        if (!supportedCondition()) return tr("codon.breakpoint.unsupported_result");
        return tr(kind == BreakpointCondition.Kind.ALWAYS ? "codon.breakpoint.hint.always"
            : original.target().wholeCommand() ? "codon.breakpoint.hint.whole_result" : "codon.breakpoint.hint.stage_result");
    }

    private String feedback() {
        var error = state.breakpoints().error(original.target());
        return error != null ? tr("codon.breakpoint.error." + error.name().toLowerCase(java.util.Locale.ROOT))
            : sendFailed || !state.breakpoints().ready() ? tr("codon.breakpoint.request_unavailable")
            : state.breakpoints().pending(original.target())
                ? tr(deleting ? "codon.breakpoint.deleting" : "codon.breakpoint.saving") : "";
    }

    private int textHeight(String text) {
        return text.isEmpty() ? 0 : font.split(Component.literal(text), panelWidth - 16).size() * (font.lineHeight + 1);
    }

    private int contentHeight() {
        return Math.min(height - 12, 120 + textHeight(hint()) + (feedback().isEmpty() ? 0 : textHeight(feedback()) + 4));
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        refreshControls();
        updateMenuHover(mouseX, mouseY);
        if (deleting && state.breakpoints().ready() && !state.breakpoints().pending(original.target())
            && state.breakpoints().error(original.target()) == null && state.breakpoints().get(original.target()) == null) {
            onClose();
            if (parent instanceof BreakpointListScreen list) list.recordDeleted(original);
            return;
        }
        if (saving && validCount() && !state.breakpoints().pending(original.target())
            && state.breakpoints().error(original.target()) == null) {
            BreakpointDefinition saved = state.breakpoints().get(original.target());
            if (saved != null && saved.enabled() && saved.condition().equals(draft())) { onClose(); return; }
        }
        graphics.fill(0, 0, width, height, DebuggerTheme.color(0x70000000));
        graphics.fill(left, top, left + panelWidth, top + panelHeight, DebuggerTheme.color(PANEL));
        graphics.outline(left, top, panelWidth, panelHeight, DebuggerTheme.color(BORDER));
        WatchUi.line(graphics, font, tr("codon.breakpoint.condition_title"), left + 8, top + 10, panelWidth - 44, TEXT);
        WatchUi.line(graphics, font, BreakpointUi.target(original.target()), left + 8, top + 29,
            panelWidth - 16, MUTED);
        String fragment = commandFragment();
        WatchUi.line(graphics, font, fragment, left + 8, top + 42, panelWidth - 16, MUTED);
        if (menu == Menu.NONE && font.width(fragment) > panelWidth - 16
            && mouseX >= left + 8 && mouseX < left + panelWidth - 8
            && mouseY >= top + 41 && mouseY < top + 52)
            graphics.setTooltipForNextFrame(font, Component.literal(fragment), mouseX, mouseY);
        int hintColor = !validCount() || !supportedCondition() && !previewLoading() ? AMBER : MUTED;
        int hintY = drawLines(graphics, hint(), top + 86, hintColor);
        if (!feedback().isEmpty()) drawLines(graphics, feedback(), hintY + 4,
            state.breakpoints().error(original.target()) != null || sendFailed ? AMBER : MUTED);
        super.extractRenderState(graphics, menu == Menu.NONE ? mouseX : -1, menu == Menu.NONE ? mouseY : -1, partialTick);
        if (menu != Menu.NONE) {
            graphics.nextStratum();
            graphics.fill(menuLeft, menuTop, menuLeft + menuWidth, menuTop + menuHeight, SURFACE);
            graphics.outline(menuLeft, menuTop, menuWidth, menuHeight, BORDER);
            for (var option : options()) if (option.visible) option.extractRenderState(graphics, mouseX, mouseY, partialTick);
            if (menuRows < options().size()) {
                int trackHeight = menuHeight - 2;
                int thumbHeight = Math.max(8, trackHeight * menuRows / options().size());
                int thumbTop = menuTop + 1 + (trackHeight - thumbHeight) * menuScroll / (options().size() - menuRows);
                graphics.fill(menuLeft + menuWidth - 3, thumbTop, menuLeft + menuWidth - 1, thumbTop + thumbHeight, TEAL);
            }
        }
    }

    private int drawLines(GuiGraphicsExtractor graphics, String text, int y, int color) {
        for (var line : font.split(Component.literal(text), panelWidth - 16)) {
            if (y + font.lineHeight > top + panelHeight - 32) break;
            graphics.text(font, line, left + 8, y, DebuggerTheme.foreground(color), false);
            y += font.lineHeight + 1;
        }
        return y;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (menu != Menu.NONE) {
            if (event.key() == InputConstants.KEY_ESCAPE) { closeMenu(); return true; }
            if (event.key() == InputConstants.KEY_UP || event.key() == InputConstants.KEY_DOWN) {
                int index = options().indexOf(getFocused());
                if (index < 0) index = selectedOption();
                index = Math.floorMod(index + (event.key() == InputConstants.KEY_UP ? -1 : 1), options().size());
                revealOption(index);
                setFocused(options().get(index));
                keyboardMenu = true;
                return true;
            }
            if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_SPACE) {
                int index = options().indexOf(getFocused());
                options().get(index < 0 ? selectedOption() : index).onPress(event);
                return true;
            }
            closeMenu();
        } else if ((getFocused() == kindButton || getFocused() == comparisonButton)
            && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_SPACE
                || event.key() == InputConstants.KEY_DOWN || event.key() == InputConstants.KEY_UP)) {
            openMenu(getFocused() == kindButton ? Menu.KIND : Menu.COMPARISON, true);
            return true;
        }
        if (event.key() == InputConstants.KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(event);
    }

    @Override public boolean charTyped(CharacterEvent event) {
        closeMenu();
        return super.charTyped(event);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (menu != Menu.NONE) {
            if (overMenu(event.x(), event.y())) {
                for (var option : options()) if (option.visible && option.mouseClicked(event, doubleClick)) return true;
                return true;
            }
            if (trigger(menu).isMouseOver(event.x(), event.y())) {
                // Consume the click even during the guard or after closing: neither
                // options nor the form beneath may receive this toggle event.
                if (event.button() == InputConstants.MOUSE_BUTTON_LEFT
                    && System.nanoTime() - menuOpenedAt >= TOGGLE_DELAY) closeMenu();
                return true;
            }
            closeMenu();
        }
        if (event.x() < left || event.x() >= left + panelWidth
            || event.y() < top || event.y() >= top + panelHeight) {
            onClose();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (menu == Menu.NONE) return super.mouseScrolled(x, y, scrollX, scrollY);
        if (overMenu(x, y) && scrollY != 0) {
            menuScroll = Math.clamp(menuScroll + (scrollY > 0 ? -1 : 1), 0, options().size() - menuRows);
            layoutMenu();
            if (options().contains(getFocused()) && !((DebuggerButton) getFocused()).visible) setFocused(trigger(menu));
        }
        return true;
    }

    @Override public void onClose() { ScreenLayers.close(this); }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) { }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }

    private static String tr(String key, Object... args) { return Component.translatable(key, args).getString(); }

    private boolean previewLoading() {
        ClientStagePreviewState.Preview preview = state.stagePreviews().get(original.target().location());
        return preview == null || preview.status() == ClientStagePreviewState.Status.LOADING;
    }

    private String commandFragment() {
        ClientStagePreviewState.Preview preview = state.stagePreviews().get(original.target().location());
        if (previewLoading()) return tr("codon.breakpoint.loading_stages");
        if (preview.status() != ClientStagePreviewState.Status.READY)
            return tr("codon.breakpoint.stages_unavailable");
        if (original.target().wholeCommand()) return preview.savedCommand();
        if (!original.target().commandFingerprint().equals(BreakpointTarget.fingerprint(preview.savedCommand())))
            return tr("codon.breakpoint.location_review");
        return preview.spans().stream().filter(span -> span.index() == original.target().stageIndex())
            .findFirst().map(span -> {
                int start = span.index() == 0
                    ? Math.max(span.start(), CommandFlowLayout.executePrefixEnd(preview.savedCommand())) : span.start();
                return preview.savedCommand().substring(start, span.end()).trim();
            })
            .orElseGet(() -> tr("codon.breakpoint.stages_unavailable"));
    }
}
