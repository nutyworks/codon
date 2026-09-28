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

/** Modal condition widgets; hosted by ScreenLayers, never installed as the active screen. */
public final class BreakpointConditionScreen extends Screen {
    /** Position of the control that opened this editor, in GUI pixels. */
    public record Anchor(int x, int y, int width, int height) { }

    private final Screen parent;
    private final ClientDebuggerState state;
    private final BreakpointDefinition original;
    private final Anchor anchor;
    private BreakpointCondition.Kind kind;
    private BreakpointCondition.Comparison comparison;
    private String thresholdText;
    private EditBox threshold;
    private DebuggerButton saveButton;
    private final List<DebuggerButton> kinds = new ArrayList<>();
    private final List<DebuggerButton> comparisons = new ArrayList<>();
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
        kinds.clear();
        comparisons.clear();
        panelWidth = Math.max(1, Math.min(340, width - 12));
        panelHeight = Math.max(1, Math.min(204, height - 12));
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
        int kindWidth = Math.min(30, (panelWidth - 16) / BreakpointCondition.Kind.values().length);
        for (var value : BreakpointCondition.Kind.values()) {
            DebuggerButton button = WatchUi.button(left + 8 + value.ordinal() * kindWidth, top + 58,
                kindWidth - 2, 24, Component.literal(kindLabel(value)), () -> { kind = value; refreshControls(); });
            button.withIcon(kindIcon(value));
            kinds.add(addRenderableWidget(button));
        }
        for (var value : BreakpointCondition.Comparison.values()) {
            DebuggerButton button = WatchUi.button(left + 8 + value.ordinal() * 25, top + 104,
                23, 22, Component.literal(symbol(value)), () -> { comparison = value; refreshControls(); });
            button.withTextPadding(2);
            comparisons.add(addRenderableWidget(button));
        }
        threshold = addRenderableWidget(new DebuggerEditBox(font, left + 166, top + 104,
            Math.max(36, panelWidth - 174), 22, Component.translatable("codon.breakpoint.count")));
        threshold.setMaxLength(9);
        threshold.setValue(thresholdText);
        threshold.setResponder(value -> { thresholdText = value; refreshControls(); });
        threshold.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("codon.breakpoint.count")));
        int footer = top + panelHeight - 27;
        saveButton = addRenderableWidget(WatchUi.button(left + panelWidth - 36, footer, 28, 20,
            Component.translatable("codon.breakpoint.save"), this::save)).withIcon(DebuggerIcon.CONFIRM);
        saveButton.withStatusColor(TEAL, TEAL_SURFACE);
        addRenderableWidget(WatchUi.button(left + panelWidth - 28, top + 5, 20, 20,
            Component.translatable("codon.breakpoint.cancel"), this::onClose)).withIcon(DebuggerIcon.REMOVE);
        addRenderableWidget(WatchUi.button(left + 8, footer, 28, 20,
            Component.translatable("codon.breakpoint.delete"), this::delete))
            .withIcon(DebuggerIcon.DELETE).withStatusColor(RED, RED_SURFACE);
        refreshControls();
        setFocused(kinds.get(kind.ordinal()));
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
            DebuggerButton button = comparisons.get(value.ordinal());
            button.visible = button.active = kind.isCount();
            button.setSelected(comparison == value);
        }
        threshold.visible = threshold.active = kind.isCount();
        if (saveButton != null) saveButton.active = validCount() && supportedCondition() && state.breakpoints().ready()
            && !state.breakpoints().pending(original.target());
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
        WatchUi.line(graphics, font, tr("codon.breakpoint.condition_title"), left + 8, top + 10, panelWidth - 44, TEXT);
        WatchUi.line(graphics, font, BreakpointUi.target(original.target()), left + 8, top + 29,
            panelWidth - 16, MUTED);
        String fragment = commandFragment();
        WatchUi.line(graphics, font, fragment, left + 8, top + 42, panelWidth - 16, MUTED);
        if (font.width(fragment) > panelWidth - 16
            && mouseX >= left + 8 && mouseX < left + panelWidth - 8
            && mouseY >= top + 41 && mouseY < top + 52)
            graphics.setTooltipForNextFrame(font, Component.literal(fragment), mouseX, mouseY);
        WatchUi.line(graphics, font, kindLabel(kind), left + 8, top + 88, panelWidth - 16, TEAL);
        String hint;
        int hintColor = MUTED;
        if (kind.isCount() && !validCount()) {
            hint = tr("codon.breakpoint.invalid_count");
            hintColor = AMBER;
        } else if ((kind.isCount() || kind.isEvent()) && previewLoading()) {
            hint = tr("codon.breakpoint.loading_stages");
        } else if (!supportedCondition()) {
            hint = tr("codon.breakpoint.unsupported_result");
            hintColor = AMBER;
        } else hint = tr(kind == BreakpointCondition.Kind.ALWAYS
            ? "codon.breakpoint.hint.always" : original.target().wholeCommand()
                ? "codon.breakpoint.hint.whole_result" : "codon.breakpoint.hint.stage_result");
        int hintY = top + 134;
        for (var line : font.split(Component.literal(hint), panelWidth - 16)) {
            if (hintY + font.lineHeight > top + panelHeight - 47) break;
            graphics.text(font, line, left + 8, hintY, DebuggerTheme.color(hintColor), false);
            hintY += font.lineHeight + 1;
        }
        var error = state.breakpoints().error(original.target());
        String feedback = error != null ? tr("codon.breakpoint.error." + error.name().toLowerCase(java.util.Locale.ROOT))
            : sendFailed ? tr("codon.breakpoint.request_unavailable") :
            state.breakpoints().pending(original.target()) ? tr("codon.breakpoint.saving") : "";
        WatchUi.line(graphics, font, feedback, left + 8, top + panelHeight - 45, panelWidth - 16,
            error == null && !sendFailed ? MUTED : AMBER);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(event);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.x() < left || event.x() >= left + panelWidth
            || event.y() < top || event.y() >= top + panelHeight) {
            onClose();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
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
