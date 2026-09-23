package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.network.ClientNetworking;
import works.nuty.codon.client.state.ClientBreakpointState;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.client.ui.layout.CommandFlowLayout;
import works.nuty.codon.core.model.BreakpointCondition;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;

import static works.nuty.codon.client.ui.DebuggerTheme.*;

/** Edits the optional condition of one existing breakpoint, retaining the draft on failure. */
public final class BreakpointConditionScreen extends Screen {
    /** Position of the control that opened this editor, in GUI pixels. */
    public record Anchor(int x, int y, int width, int height) { }

    private enum ChoiceMenu { NONE, CONDITION, COMPARISON }
    private static final int CHOICE_HEIGHT = 18;
    private final Screen parent;
    private final ClientDebuggerState state;
    private final BreakpointDefinition original;
    private final Anchor anchor;
    private BreakpointCondition.Kind kind;
    private BreakpointCondition.Comparison comparison;
    private String thresholdText;
    private EditBox threshold;
    private DebuggerButton kindButton, comparisonButton, saveButton;
    private final List<DebuggerButton> choices = new ArrayList<>();
    private ChoiceMenu choiceMenu = ChoiceMenu.NONE;
    private int choiceOffset, choiceCursor, choiceTop, choiceLeft, choiceWidth, choiceRows;
    private boolean saving;
    private boolean sendFailed;
    private boolean previewRequested;
    private int left, top, panelWidth, panelHeight;

    public BreakpointConditionScreen(Screen parent, ClientDebuggerState state, BreakpointDefinition definition) {
        this(parent, state, definition, null);
    }

    public BreakpointConditionScreen(Screen parent, ClientDebuggerState state, BreakpointDefinition definition,
                                     Anchor anchor) {
        super(Component.translatable("codon.breakpoint.condition_title"));
        this.parent = parent;
        this.state = state;
        this.original = definition;
        this.anchor = anchor;
        this.kind = definition.condition().kind();
        this.comparison = definition.condition().comparison();
        this.thresholdText = Integer.toString(definition.condition().threshold());
    }

    @Override protected void init() {
        clearWidgets();
        choices.clear();
        panelWidth = Math.max(1, Math.min(340, width - 12));
        panelHeight = Math.max(1, Math.min(228, height - 12));
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
            previewRequested = ClientNetworking.requestStagePreview(state, original.target().location());
        }
        kindButton = addRenderableWidget(WatchUi.button(left + 8, top + 53, panelWidth - 16, 20,
            Component.translatable("codon.breakpoint.condition_select", kindLabel(kind)), this::toggleKindMenu));
        comparisonButton = addRenderableWidget(WatchUi.button(left + 8, top + 79, 88, 20,
            Component.translatable("codon.breakpoint.compare_select", symbol(comparison)), this::toggleComparisonMenu));
        threshold = addRenderableWidget(new DebuggerEditBox(font, left + 102, top + 79,
            Math.max(36, panelWidth - 110), 20, Component.translatable("codon.breakpoint.count")));
        threshold.setMaxLength(9);
        threshold.setValue(thresholdText);
        threshold.setResponder(value -> thresholdText = value);
        int footer = top + panelHeight - 27;
        saveButton = addRenderableWidget(WatchUi.button(left + 8, footer, 70, 20,
            Component.translatable("codon.breakpoint.save"), this::save));
        addRenderableWidget(WatchUi.button(left + 82, footer, 70, 20,
            Component.translatable("codon.breakpoint.cancel"), this::onClose));
        addRenderableWidget(WatchUi.button(left + panelWidth - 78, footer, 70, 20,
            Component.translatable("codon.breakpoint.delete"), this::delete));
        for (int slot = 0; slot < 4; slot++) {
            int index = slot;
            choices.add(addRenderableWidget(WatchUi.button(left + 8, top + 76, panelWidth - 16,
                CHOICE_HEIGHT, Component.empty(), () -> selectChoice(choiceOffset + index))));
        }
        refreshControls();
        setFocused(kindButton);
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

    private void toggleKindMenu() {
        openMenu(choiceMenu == ChoiceMenu.CONDITION ? ChoiceMenu.NONE : ChoiceMenu.CONDITION, kind.ordinal());
    }

    private void toggleComparisonMenu() {
        openMenu(choiceMenu == ChoiceMenu.COMPARISON ? ChoiceMenu.NONE : ChoiceMenu.COMPARISON,
            comparison.ordinal());
    }

    private void openMenu(ChoiceMenu menu, int selectedIndex) {
        choiceMenu = menu;
        choiceCursor = selectedIndex;
        choiceOffset = Math.max(0, selectedIndex - 1);
        refreshControls();
    }

    private int choiceCount() {
        return choiceMenu == ChoiceMenu.CONDITION ? BreakpointCondition.Kind.values().length
            : choiceMenu == ChoiceMenu.COMPARISON ? BreakpointCondition.Comparison.values().length : 0;
    }

    private static String kindLabel(BreakpointCondition.Kind value) {
        return BreakpointUi.kindLabel(value);
    }

    private void selectChoice(int index) {
        if (index < 0 || index >= choiceCount()) return;
        if (choiceMenu == ChoiceMenu.CONDITION) kind = BreakpointCondition.Kind.values()[index];
        else if (choiceMenu == ChoiceMenu.COMPARISON) comparison = BreakpointCondition.Comparison.values()[index];
        choiceMenu = ChoiceMenu.NONE;
        refreshControls();
        setFocused(kindButton);
    }

    private void updateChoiceButtons() {
        int count = choiceCount();
        if (count == 0) {
            choices.forEach(button -> button.visible = button.active = false);
            return;
        }
        int anchorY = choiceMenu == ChoiceMenu.CONDITION ? top + 53 : top + 79;
        int footer = top + panelHeight - 27;
        int below = Math.max(0, (footer - anchorY - 22) / CHOICE_HEIGHT);
        int above = Math.max(0, (anchorY - top - 10) / CHOICE_HEIGHT);
        boolean placeBelow = below >= Math.min(3, count) || below >= above;
        choiceRows = Math.min(Math.min(choices.size(), count), Math.max(1, placeBelow ? below : above));
        choiceTop = placeBelow ? anchorY + 22 : anchorY - 2 - choiceRows * CHOICE_HEIGHT;
        choiceLeft = left + 8;
        choiceWidth = choiceMenu == ChoiceMenu.CONDITION ? panelWidth - 16 : Math.min(170, panelWidth - 16);
        choiceOffset = Math.clamp(choiceOffset, 0, count - choiceRows);
        for (int slot = 0; slot < choices.size(); slot++) {
            DebuggerButton button = choices.get(slot);
            button.visible = button.active = slot < choiceRows;
            if (!button.visible) continue;
            int index = choiceOffset + slot;
            String label = choiceMenu == ChoiceMenu.CONDITION
                ? kindLabel(BreakpointCondition.Kind.values()[index])
                : symbol(BreakpointCondition.Comparison.values()[index]);
            button.setX(choiceLeft);
            button.setY(choiceTop + slot * CHOICE_HEIGHT);
            button.setSize(choiceWidth, CHOICE_HEIGHT);
            button.setMessage(Component.literal((index == choiceCursor ? "› " : "  ") + label));
            button.withStatusColor(index == choiceCursor ? TEAL : TEXT,
                index == choiceCursor ? TEAL_SURFACE : SURFACE);
        }
    }

    private void refreshControls() {
        boolean count = kind.isCount();
        comparisonButton.visible = comparisonButton.active = count;
        threshold.visible = threshold.active = count;
        kindButton.setMessage(Component.translatable("codon.breakpoint.condition_select", kindLabel(kind)));
        comparisonButton.setMessage(Component.translatable("codon.breakpoint.compare_select", symbol(comparison)));
        saveButton.active = validCount() && supportedCondition() && state.breakpoints().ready()
            && !state.breakpoints().pending(original.target());
        updateChoiceButtons();
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
        BreakpointDefinition current = state.breakpoints().get(original.target());
        saving = ClientNetworking.sendBreakpointEdit(state, ClientBreakpointState.Action.SAVE,
            (current == null ? original : current).withCondition(draft()));
        sendFailed = !saving;
        refreshControls();
    }

    private void delete() {
        if (!ClientNetworking.sendBreakpointEdit(state, ClientBreakpointState.Action.DELETE, original)) {
            sendFailed = true;
            return;
        }
        BreakpointListScreen list = parent instanceof BreakpointListScreen existing ? existing
            : new BreakpointListScreen(parent, state);
        list.recordDeleted(original);
        Minecraft.getInstance().gui.setScreen(list);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        refreshControls();
        if (saving && validCount() && !state.breakpoints().pending(original.target())
            && state.breakpoints().error(original.target()) == null) {
            BreakpointDefinition saved = state.breakpoints().get(original.target());
            if (saved != null && saved.condition().equals(draft())) { onClose(); return; }
        }
        graphics.fill(0, 0, width, height, DebuggerTheme.color(0x70000000));
        graphics.fill(left, top, left + panelWidth, top + panelHeight, DebuggerTheme.color(PANEL));
        graphics.outline(left, top, panelWidth, panelHeight, DebuggerTheme.color(BORDER));
        WatchUi.line(graphics, font, tr("codon.breakpoint.condition_title"), left + 8, top + 10, panelWidth - 16, TEXT);
        WatchUi.line(graphics, font, BreakpointUi.target(original.target()), left + 8, top + 29,
            panelWidth - 16, MUTED);
        String fragment = commandFragment();
        WatchUi.line(graphics, font, fragment, left + 8, top + 42, panelWidth - 16, MUTED);
        if (mouseX >= left + 8 && mouseX < left + panelWidth - 8
            && mouseY >= top + 41 && mouseY < top + 52)
            graphics.setTooltipForNextFrame(font, Component.literal(fragment), mouseX, mouseY);
        if (kind.isCount() && !validCount()) WatchUi.line(graphics, font, tr("codon.breakpoint.invalid_count"),
            left + 8, top + 106, panelWidth - 16, AMBER);
        else if ((kind.isCount() || kind.isEvent()) && previewLoading()) WatchUi.line(graphics, font,
            tr("codon.breakpoint.loading_stages"), left + 8, top + 106, panelWidth - 16, MUTED);
        else if (!supportedCondition()) WatchUi.line(graphics, font,
            tr("codon.breakpoint.unsupported_result"), left + 8, top + 106, panelWidth - 16, AMBER);
        else WatchUi.line(graphics, font, tr(kind == BreakpointCondition.Kind.ALWAYS
            ? "codon.breakpoint.hint.always" : original.target().wholeCommand()
                ? "codon.breakpoint.hint.whole_result" : "codon.breakpoint.hint.stage_result"),
            left + 8, top + 106, panelWidth - 16, MUTED);
        var error = state.breakpoints().error(original.target());
        String feedback = error != null ? tr("codon.breakpoint.error." + error.name().toLowerCase(java.util.Locale.ROOT))
            : sendFailed ? tr("codon.breakpoint.request_unavailable") :
            state.breakpoints().pending(original.target()) ? tr("codon.breakpoint.saving") : "";
        WatchUi.line(graphics, font, feedback, left + 8, top + panelHeight - 45, panelWidth - 16,
            error == null && !sendFailed ? MUTED : AMBER);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (choiceMenu != ChoiceMenu.NONE) {
            if (event.key() == InputConstants.KEY_ESCAPE) { choiceMenu = ChoiceMenu.NONE; refreshControls(); return true; }
            if (event.key() == InputConstants.KEY_UP || event.key() == InputConstants.KEY_DOWN) {
                choiceCursor = Math.clamp(choiceCursor + (event.key() == InputConstants.KEY_DOWN ? 1 : -1),
                    0, choiceCount() - 1);
                if (choiceCursor < choiceOffset) choiceOffset = choiceCursor;
                if (choiceCursor >= choiceOffset + choiceRows) choiceOffset = choiceCursor - choiceRows + 1;
                refreshControls();
                return true;
            }
            if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER
                || event.key() == InputConstants.KEY_SPACE) { selectChoice(choiceCursor); return true; }
            if (event.key() == InputConstants.KEY_TAB) { choiceMenu = ChoiceMenu.NONE; refreshControls(); }
        }
        if (event.key() == InputConstants.KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(event);
    }

    @Override public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (choiceMenu == ChoiceMenu.NONE || x < choiceLeft || x >= choiceLeft + choiceWidth
            || y < choiceTop || y >= choiceTop + choiceRows * CHOICE_HEIGHT)
            return super.mouseScrolled(x, y, scrollX, scrollY);
        choiceOffset = Math.clamp(choiceOffset - (int) Math.signum(scrollY), 0, choiceCount() - choiceRows);
        refreshControls();
        return true;
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.x() < left || event.x() >= left + panelWidth
            || event.y() < top || event.y() >= top + panelHeight) {
            onClose();
            return true;
        }
        if (choiceMenu != ChoiceMenu.NONE && event.x() >= choiceLeft && event.x() < choiceLeft + choiceWidth
            && event.y() >= choiceTop && event.y() < choiceTop + choiceRows * CHOICE_HEIGHT) {
            // The popup covers the comparison field on compact screens. Dispatch its rows
            // before the underlying widgets so selecting a choice cannot change another field.
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                selectChoice(choiceOffset + ((int) event.y() - choiceTop) / CHOICE_HEIGHT);
            }
            return true;
        }
        if (choiceMenu != ChoiceMenu.NONE) {
            boolean toggle = kindButton.isMouseOver(event.x(), event.y())
                || comparisonButton.isMouseOver(event.x(), event.y());
            choiceMenu = ChoiceMenu.NONE;
            refreshControls();
            if (!toggle) return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
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
