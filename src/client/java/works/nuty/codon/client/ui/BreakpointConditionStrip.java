package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.AbstractContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.network.ClientNetworking;
import works.nuty.codon.client.state.BreakpointTargetPolicy;
import works.nuty.codon.client.state.ClientBreakpointState;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import works.nuty.codon.core.model.BreakpointCondition;
import works.nuty.codon.core.model.BreakpointConditionDraft;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.service.BreakpointConditionEvaluator;

import static works.nuty.codon.client.ui.DebuggerTheme.*;

/**
 * Inline condition editor for one exact breakpoint target, like an IDE's inline breakpoint editor.
 * Flow and Source draw it in their own layout, under the stage or line it belongs to; the
 * command-block editor shows it under its input. Its owning screen keeps its widgets and focus,
 * and the strip receives input first only inside its own bounds or while one of its controls is focused.
 */
public final class BreakpointConditionStrip extends AbstractContainerEventHandler {
    /** Two rows: the rule's controls, then its sentence and actions. */
    public static final int HEIGHT = 26;
    private static final int ROW = 13;
    private static final int CONTROL = 12;
    private static final int GAP = 3;
    /** Decrease, a 30-pixel number field and Increase. */
    private static final int STEPPER = 50;
    /** The number's text width; created at this width so its initial value is not scrolled out of view. */
    private static final int NUMBER_WIDTH = 27;
    private static final BreakpointCondition.Kind[] EVENTS = {
        BreakpointCondition.Kind.CREATED, BreakpointCondition.Kind.REMOVED, BreakpointCondition.Kind.CHANGED };
    private static final BreakpointCondition.Kind[] METRICS = {
        BreakpointCondition.Kind.INPUT_COUNT, BreakpointCondition.Kind.OUTPUT_COUNT, BreakpointCondition.Kind.CREATED_COUNT,
        BreakpointCondition.Kind.REMOVED_COUNT, BreakpointCondition.Kind.CHANGED_COUNT };
    private static final Bounds EMPTY = new Bounds(0, 0, 0, 0);

    /** Where the owning screen draws the strip. */
    public enum Placement { FLOW, SOURCE, EDITOR }

    private static final Map<Screen, BreakpointConditionStrip> OPEN = new WeakHashMap<>();
    private static final Map<Screen, Event<ScreenEvents.AfterTick>> ATTACHED = new WeakHashMap<>();

    private final Screen host;
    private final Placement placement;
    private final ClientDebuggerState state;
    private final BreakpointDefinition original;
    private final BreakpointTarget markerTarget;
    private final BooleanSupplier contextCurrent;
    private final Runnable restoreFocus;
    private @Nullable Supplier<Bounds> overlayBounds;
    private @Nullable GuiEventListener previousFocus;
    private BreakpointConditionDraft draft;
    private boolean confirmingDiscard;
    private boolean saving, deleting, sendFailed;
    private long requestId;
    private boolean closed;
    private boolean rendered;
    private Bounds bounds = EMPTY;
    private Bounds sentenceBounds = EMPTY;
    private String sentenceTooltip = "";

    private final List<DebuggerButton> modes = new ArrayList<>();
    private final List<DebuggerButton> events = new ArrayList<>();
    private final List<DebuggerButton> metrics = new ArrayList<>();
    private final DebuggerButton comparison = new DebuggerButton();
    private final DebuggerButton decrease = new StepButton("−");
    private final DebuggerButton increase = new StepButton("+");
    private final DebuggerEditBox threshold;
    private final DebuggerButton remove = new DebuggerButton();
    private final DebuggerButton cancel = new DebuggerButton();
    private final DebuggerButton save = new DebuggerButton();
    private final DebuggerButton keepEditing = new DebuggerButton();
    private final DebuggerButton discard = new DebuggerButton();
    private final List<AbstractWidget> visible = new ArrayList<>();

    private BreakpointConditionStrip(Screen host, Placement placement, ClientDebuggerState state,
                                     BreakpointDefinition definition, BreakpointTarget markerTarget,
                                     BooleanSupplier contextCurrent, Runnable restoreFocus) {
        this.host = host;
        this.placement = placement;
        this.state = state;
        this.original = definition;
        this.markerTarget = markerTarget;
        this.contextCurrent = contextCurrent;
        this.restoreFocus = restoreFocus;
        this.draft = BreakpointConditionDraft.of(definition.condition());
        for (int i = 0; i < 3; i++) modes.add(new DebuggerButton());
        for (int i = 0; i < EVENTS.length; i++) events.add(new DebuggerButton());
        for (int i = 0; i < METRICS.length; i++) metrics.add(new DebuggerButton());
        threshold = new DebuggerEditBox(Minecraft.getInstance().font, 0, 0, NUMBER_WIDTH, CONTROL - 2, Component.translatable("codon.breakpoint.strip.count"));
        threshold.setMaxLength(BreakpointConditionDraft.MAX_THRESHOLD_LENGTH);
        threshold.setBordered(false);
        threshold.setValue(draft.threshold());
        threshold.setResponder(value -> {
            if (value.equals(draft.threshold())) return;
            draft = draft.withThreshold(value);
            layout(null);
        });
        threshold.setTooltip(Tooltip.create(Component.translatable("codon.breakpoint.strip.count_hint")));
    }

    // ---------------------------------------------------------------- registry

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> attach(screen));
    }

    /**
     * Opens the editor for {@code definition} in {@code host}, replacing any other strip there.
     * {@code markerTarget} is the marker that shows it as being edited; it differs from the definition
     * only for a saved sole stage shown on its line marker.
     */
    public static @Nullable BreakpointConditionStrip open(Screen host, Placement placement, ClientDebuggerState state,
                                                          BreakpointDefinition definition, BreakpointTarget markerTarget,
                                                          BooleanSupplier contextCurrent, Runnable restoreFocus) {
        if (!contextCurrent.getAsBoolean() || !state.breakpoints().ready()
            || state.breakpoints().pending(definition.target())) return null;
        BreakpointConditionStrip previous = OPEN.get(host);
        if (previous != null) previous.close(false);
        var strip = new BreakpointConditionStrip(host, placement, state, definition, markerTarget, contextCurrent, restoreFocus);
        // Every screen is attached on init (see register), including one opened right after this call.
        OPEN.put(host, strip);
        strip.requestPreview();
        strip.previousFocus = host.getFocused();
        host.setFocused(null);
        host.setDragging(false);
        strip.setFocused(strip.modes.get(strip.draft.mode().ordinal()));
        return strip;
    }

    public static @Nullable BreakpointConditionStrip get(@Nullable Screen host) {
        return host == null ? null : OPEN.get(host);
    }

    public static @Nullable BreakpointConditionStrip get(@Nullable Screen host, Placement placement) {
        var strip = get(host);
        return strip != null && strip.placement == placement ? strip : null;
    }

    /** The command-block editor has no layout slot of its own; the strip draws itself over the area below the input. */
    public BreakpointConditionStrip overlay(Supplier<Bounds> bounds) {
        this.overlayBounds = bounds;
        this.bounds = bounds.get();
        layout(null);
        return this;
    }

    private static void attach(Screen screen) {
        // Fabric replaces per-screen events on init/resize; register once per event instance.
        var tickEvent = ScreenEvents.afterTick(screen);
        if (ATTACHED.put(screen, tickEvent) == tickEvent) return;
        ScreenEvents.remove(screen).register(removed -> {
            var strip = OPEN.get(removed);
            if (strip != null) strip.close(strip.pending());
            ATTACHED.remove(removed, tickEvent);
        });
        tickEvent.register(owner -> { var strip = OPEN.get(owner); if (strip != null) strip.validContext(); });
        ScreenEvents.afterExtract(screen).register((owner, graphics, x, y, delta) -> {
            var strip = OPEN.get(owner);
            if (strip == null) return;
            if (strip.overlayBounds != null && ScreenLayers.get(owner) == null) {
                Bounds area = strip.overlayBounds.get();
                graphics.nextStratum();
                strip.render(graphics, area, x, y, delta);
            }
            strip.endFrame();
        });
        ScreenMouseEvents.allowMouseClick(screen).register((owner, event) -> {
            var strip = active(owner);
            if (strip == null) return true;
            if (strip.bounds.contains(event.x(), event.y())) {
                strip.mouseClicked(event, false);
                return false;
            }
            strip.setFocused(null);
            return true;
        });
        ScreenMouseEvents.allowMouseRelease(screen).register((owner, event) -> {
            var strip = active(owner);
            if (strip == null || !strip.isDragging()) return true;
            strip.mouseReleased(event);
            strip.setDragging(false);
            return false;
        });
        ScreenMouseEvents.allowMouseDrag(screen).register((owner, event, dx, dy) -> {
            var strip = active(owner);
            if (strip == null || !strip.isDragging()) return true;
            strip.mouseDragged(event, dx, dy);
            return false;
        });
        ScreenMouseEvents.allowMouseScroll(screen).register((owner, x, y, dx, dy) -> {
            var strip = active(owner);
            if (strip == null || !strip.bounds.contains(x, y)) return true;
            strip.mouseScrolled(x, y, dx, dy);
            return false;
        });
        ScreenKeyboardEvents.allowKeyPress(screen).register((owner, event) -> {
            var strip = active(owner);
            if (strip == null || strip.getFocused() == null) return true;
            strip.keyPressed(event);
            return false;
        });
        ScreenKeyboardEvents.allowCharType(screen).register((owner, event) -> {
            var strip = active(owner);
            if (strip == null || strip.getFocused() == null) return true;
            strip.charTyped(event);
            return false;
        });
    }

    /** A modal layer above the screen owns all input. */
    private static @Nullable BreakpointConditionStrip active(Screen owner) {
        var strip = OPEN.get(owner);
        return strip == null || ScreenLayers.get(owner) != null || !strip.validContext() ? null : strip;
    }

    // ---------------------------------------------------------------- identity

    public Placement placement() { return placement; }
    public BreakpointTarget target() { return original.target(); }
    public BreakpointTarget markerTarget() { return markerTarget; }
    public Bounds bounds() { return bounds; }

    public boolean editsMarker(BreakpointTarget target, String command) {
        return BreakpointTargetPolicy.editedMarker(target, markerTarget, markerDefinition(), command);
    }

    /** The acknowledged definition, or the unsaved default; opening never changes the marker. */
    public BreakpointDefinition markerDefinition() {
        var acknowledged = state.breakpoints().get(original.target());
        return acknowledged == null ? original.withEnabled(false) : acknowledged;
    }

    /** Controls in the order Tab visits them. Game tests also find them here by label. */
    @Override public List<? extends GuiEventListener> children() { return visible; }

    public List<AbstractWidget> widgets() { return List.copyOf(visible); }

    private boolean pending() { return state.breakpoints().pending(original.target()); }

    /** Associates a request started elsewhere with this editor, as its own Save or Remove would. */
    void trackPending(long requestId) { this.requestId = requestId; }

    /** The owning screen's tick: a stale context closes the strip and hands a pending result to a toast. */
    void tick() { validContext(); }

    private boolean validContext() {
        if (closed) return false;
        if (contextCurrent.getAsBoolean()) return true;
        close(true);
        return false;
    }

    /** Esc and Cancel: close a clean draft, confirm before discarding changes, and wait while a request is pending. */
    public void cancel() {
        if (!validContext() || pending()) return;
        if (confirmingDiscard) { keepEditing(); return; }
        if (!dirty()) { close(false); return; }
        confirmingDiscard = true;
        layout(null);
        setFocused(keepEditing);
    }

    private void keepEditing() {
        confirmingDiscard = false;
        layout(null);
        setFocused(cancel);
    }

    private void close(boolean detach) {
        if (closed) return;
        closed = true;
        if (detach) state.breakpoints().detachPending(original.target(), requestId);
        else state.breakpoints().forgetModalFailure(original.target(), requestId);
        OPEN.remove(host, this);
        setFocused(null);
        var gui = Minecraft.getInstance().gui;
        if (!detach && gui != null && gui.screen() == host && contextCurrent.getAsBoolean()) {
            if (previousFocus != null && host.children().contains(previousFocus)) host.setFocused(previousFocus);
            restoreFocus.run();
        }
    }

    /** Called by its owning screen when a newer interaction closes the editor; unsaved changes are dropped. */
    public void dismiss() {
        close(pending());
    }

    public boolean closed() { return closed; }

    // ---------------------------------------------------------------- draft

    private boolean dirty() { return draft.changedFrom(original.condition()); }

    private boolean changedFromSaved() {
        BreakpointDefinition saved = state.breakpoints().get(original.target());
        return saved == null || !saved.enabled() || draft.changedFrom(saved.condition());
    }

    private void requestPreview() {
        var preview = state.stagePreviews().get(original.target().location());
        // An exact READY stage preview already validates these offsets.
        boolean ready = !original.target().wholeCommand() && preview != null
            && preview.status() == ClientStagePreviewState.Status.READY
            && original.target().commandFingerprint().equals(BreakpointTarget.fingerprint(preview.savedCommand()));
        if (!ready) ClientNetworking.requestStagePreview(state, original.target().location());
    }

    private boolean previewLoading() {
        var preview = state.stagePreviews().get(original.target().location());
        return preview == null || preview.status() == ClientStagePreviewState.Status.LOADING;
    }

    private boolean supportedCondition() {
        if (!draft.stopsAfter()) return true;
        var preview = state.stagePreviews().get(original.target().location());
        if (preview == null || preview.status() != ClientStagePreviewState.Status.READY) return false;
        if (original.target().wholeCommand()) return preview.spans().stream().anyMatch(span -> !span.terminal());
        if (!original.target().commandFingerprint().equals(BreakpointTarget.fingerprint(preview.savedCommand()))) return false;
        int index = original.target().stageIndex();
        return index >= 0 && index < preview.spans().size() && !preview.spans().get(index).terminal();
    }

    private boolean canSave() {
        return !pending() && state.breakpoints().ready() && draft.valid() && supportedCondition() && changedFromSaved();
    }

    private void save() {
        if (!validContext() || !canSave()) return;
        deleting = false;
        BreakpointDefinition current = state.breakpoints().get(original.target());
        saving = ClientNetworking.sendBreakpointEdit(state, ClientBreakpointState.Action.SAVE,
            (current == null ? original : current).withCondition(draft.condition()).withEnabled(true));
        if (saving) requestId = state.breakpoints().pendingRequestId(original.target());
        sendFailed = !saving;
        layout(null);
    }

    private void delete() {
        if (!validContext() || state.breakpoints().get(original.target()) == null || pending()) return;
        saving = false;
        deleting = ClientNetworking.sendBreakpointEdit(state, ClientBreakpointState.Action.DELETE, original);
        if (deleting) requestId = state.breakpoints().pendingRequestId(original.target());
        sendFailed = !deleting;
        layout(null);
    }

    /** Closes after the server acknowledges this editor's own request. */
    private boolean acknowledged() {
        if (pending() || state.breakpoints().error(original.target()) != null) return false;
        BreakpointDefinition saved = state.breakpoints().get(original.target());
        if (deleting) return state.breakpoints().ready() && saved == null;
        return saving && draft.valid() && saved != null && saved.enabled() && saved.condition().equals(draft.condition());
    }

    private void setDraft(BreakpointConditionDraft next) {
        if (pending() || confirmingDiscard) return;
        draft = next;
        if (!threshold.getValue().equals(draft.threshold())) threshold.setValue(draft.threshold());
        layout(null);
    }

    // ---------------------------------------------------------------- rendering

    /** Draws the strip in {@code area}; only a strip drawn this frame receives pointer input. */
    public void render(GuiGraphicsExtractor graphics, Bounds area, int mouseX, int mouseY, float partialTick) {
        if (!validContext()) return;
        if (acknowledged()) { close(false); return; }
        Font font = Minecraft.getInstance().font;
        rendered = true;
        bounds = new Bounds(area.x(), area.y(), area.width(), Math.min(area.height(), HEIGHT));
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + bounds.width(), bounds.y() + bounds.height(), DebuggerTheme.modalColor(EDITOR));
        graphics.outline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), DebuggerTheme.modalColor(BORDER));
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + 2, bounds.y() + bounds.height(), DebuggerTheme.foreground(RED));
        layout(graphics);
        boolean pointer = bounds.contains(mouseX, mouseY);
        for (var widget : visible) widget.extractRenderState(graphics, pointer ? mouseX : -1, pointer ? mouseY : -1, partialTick);
        if (pointer && sentenceBounds.contains(mouseX, mouseY) && !sentenceTooltip.isEmpty()
            && HoverDelay.elapsed(List.of("condition-strip.sentence", original.target())))
            graphics.setTooltipForNextFrame(font, font.split(Component.literal(sentenceTooltip),
                Math.min(260, graphics.guiWidth() - 16)), mouseX, mouseY);
    }

    /**
     * Places the controls for the current draft inside the last drawn bounds; with {@code graphics}
     * it also draws the strip's text. Draft changes lay out at once, so the next input sees the new controls.
     */
    private void layout(@Nullable GuiGraphicsExtractor graphics) {
        if (bounds.width() == 0) return;
        Font font = Minecraft.getInstance().font;
        visible.clear();
        boolean pending = pending();
        layoutRule(font, bounds.x() + 5, bounds.y() + 1, bounds.x() + bounds.width() - 3, !pending && !confirmingDiscard, graphics);
        layoutActions(font, bounds.x() + 5, bounds.y() + ROW + 1, bounds.x() + bounds.width() - 3, pending, graphics);
        if (getFocused() instanceof AbstractWidget focused && !visible.contains(focused))
            setFocused(confirmingDiscard ? keepEditing : cancel);
    }

    private static void text(@Nullable GuiGraphicsExtractor graphics, Font font, String value, int x, int y, int width, int color) {
        if (graphics != null) WatchUi.line(graphics, font, value, x, y, width, color);
    }

    private void endFrame() {
        if (!rendered) {
            bounds = sentenceBounds = EMPTY;
            if (getFocused() != null) setFocused(null);
        }
        rendered = false;
    }

    private void layoutRule(Font font, int x, int y, int right, boolean editable, @Nullable GuiGraphicsExtractor graphics) {
        String[] modeLabels = { tr("codon.breakpoint.strip.mode.always"), tr("codon.breakpoint.strip.mode.event"),
            tr("codon.breakpoint.strip.mode.count") };
        String label = tr("codon.breakpoint.strip.stop_when");
        int modesWidth = 0;
        for (String mode : modeLabels) modesWidth += segment(font, mode);
        int specific = switch (draft.mode()) {
            case ALWAYS -> 0;
            case EVENT -> {
                int width = 0;
                for (var event : EVENTS) width += segment(font, eventLabel(event));
                yield GAP + width;
            }
            case COUNT -> GAP + metricsWidth(font) + GAP + 14 + GAP + STEPPER;
        };
        int available = right - x;
        boolean showLabel = font.width(label) + 4 + modesWidth + specific <= available;
        if (showLabel) {
            text(graphics, font, label, x, y + 2, font.width(label), MUTED);
            x += font.width(label) + 4;
        }
        // Narrow windows clip the mode labels before they hide any of the rule's controls.
        int modeRoom = Math.max(36, Math.min(modesWidth, right - x - specific));
        for (int i = 0; i < 3; i++) {
            var mode = BreakpointConditionDraft.Mode.values()[i];
            int width = i == 2 ? modeRoom - segmentsBefore(font, modeLabels, modesWidth, modeRoom, 2)
                : scaled(segment(font, modeLabels[i]), modesWidth, modeRoom);
            var button = modes.get(i);
            configure(button, x, y, width, Component.literal(modeLabels[i]), editable, draft.mode() == mode,
                () -> setDraft(draft.withMode(mode)));
            button.withOpenEdges(i > 0, i < 2);
            x += width;
        }
        if (draft.mode() == BreakpointConditionDraft.Mode.EVENT) {
            x += GAP;
            for (int i = 0; i < EVENTS.length; i++) {
                var event = EVENTS[i];
                int width = Math.min(segment(font, eventLabel(event)), Math.max(12, right - x));
                configure(events.get(i), x, y, width, Component.literal(eventLabel(event)), editable,
                    draft.event() == event, () -> setDraft(draft.withEvent(event)));
                events.get(i).withOpenEdges(i > 0, i < EVENTS.length - 1)
                    .setTooltip(Tooltip.create(Component.translatable("codon.breakpoint.kind." + key(event))));
                x += width;
            }
        } else if (draft.mode() == BreakpointConditionDraft.Mode.COUNT) {
            x += GAP;
            for (int i = 0; i < METRICS.length; i++) {
                var metric = METRICS[i];
                String text = tr("codon.breakpoint.strip.metric." + key(metric));
                int width = segment(font, text);
                configure(metrics.get(i), x, y, width, Component.literal(text), editable, draft.metric() == metric,
                    () -> setDraft(draft.withMetric(metric)));
                metrics.get(i).withOpenEdges(i > 0, i < METRICS.length - 1).setTooltip(Tooltip.create(
                    Component.translatable("codon.breakpoint.strip.metric." + key(metric) + ".tooltip")));
                x += width;
            }
            x += GAP;
            configure(comparison, x, y, 14, Component.literal(symbol(draft.comparison())), editable, false,
                () -> setDraft(draft.withComparison(cycle(draft.comparison(), 1))));
            comparison.withSecondaryAction(() -> setDraft(draft.withComparison(cycle(draft.comparison(), -1))))
                .setTooltip(Tooltip.create(Component.translatable("codon.breakpoint.strip.comparison",
                    tr("codon.breakpoint.strip.comparison." + draft.comparison().name().toLowerCase(Locale.ROOT)))));
            x += 14 + GAP;
            configure(decrease, x, y, 10, Component.translatable("codon.breakpoint.strip.decrease"), editable, false,
                () -> setDraft(draft.step(-1)));
            decrease.withOpenEdges(false, true).withTextPadding(0);
            x += 10;
            threshold.setX(x + 3);
            threshold.setY(y + 2);
            threshold.setWidth(NUMBER_WIDTH);
            threshold.setHeight(CONTROL - 2);
            threshold.active = editable;
            threshold.setEditable(editable);
            threshold.setTextColor(DebuggerTheme.foreground(draft.valid() ? TEXT : AMBER));
            if (graphics != null) {
                graphics.fill(x, y, x + 30, y + CONTROL, DebuggerTheme.modalColor(SURFACE));
                graphics.fill(x, y, x + 30, y + 1, DebuggerTheme.modalColor(draft.valid() ? BORDER : AMBER));
                graphics.fill(x, y + CONTROL - 1, x + 30, y + CONTROL, DebuggerTheme.modalColor(draft.valid() ? BORDER : AMBER));
            }
            visible.add(threshold);
            x += 30;
            configure(increase, x, y, 10, Component.translatable("codon.breakpoint.strip.increase"), editable, false,
                () -> setDraft(draft.step(1)));
            increase.withOpenEdges(true, false).withTextPadding(0);
        }
    }

    private void layoutActions(Font font, int x, int y, int right, boolean pending, @Nullable GuiGraphicsExtractor graphics) {
        if (confirmingDiscard) {
            String prompt = tr("codon.breakpoint.strip.discard_prompt");
            int keepWidth = button(font, tr("codon.breakpoint.keep_editing"));
            int discardWidth = button(font, tr("codon.breakpoint.discard"));
            int buttons = keepWidth + GAP + discardWidth;
            text(graphics, font, prompt, x, y + 2, Math.max(0, right - buttons - GAP - x), AMBER);
            sentenceBounds = new Bounds(x, y, Math.max(0, right - buttons - GAP - x), CONTROL);
            sentenceTooltip = WatchUi.clipped(font, prompt, sentenceBounds.width()) ? prompt : "";
            configure(keepEditing, right - buttons, y, keepWidth, Component.translatable("codon.breakpoint.keep_editing"),
                true, false, this::keepEditing);
            configure(discard, right - discardWidth, y, discardWidth, Component.translatable("codon.breakpoint.discard"),
                !pending, false, () -> { if (!pending()) close(false); });
            discard.withStatusColor(RED, RED_SURFACE);
            return;
        }
        int saveWidth = button(font, tr("codon.breakpoint.save"));
        int cancelWidth = button(font, tr("codon.breakpoint.cancel"));
        int removeWidth = button(font, tr("codon.breakpoint.strip.remove"));
        int buttons = removeWidth + GAP + cancelWidth + GAP + saveWidth;
        int textRight = right - buttons - GAP;
        String last = draft.mode() == BreakpointConditionDraft.Mode.COUNT ? lastRecorded() : "";
        if (!last.isEmpty()) {
            int width = Math.min(font.width(last), Math.max(0, (textRight - x) / 2));
            text(graphics, font, last, textRight - width, y + 2, width, MUTED);
            textRight -= width + 6;
        }
        String sentence = sentence();
        String status = status();
        boolean warning = status != null && !status.equals(tr(deleting ? "codon.breakpoint.deleting" : "codon.breakpoint.saving"))
            && !status.equals(tr("codon.breakpoint.loading_stages"));
        String shown = status == null ? sentence : status;
        sentenceBounds = new Bounds(x, y, Math.max(0, textRight - x), CONTROL);
        text(graphics, font, shown, x, y + 2, sentenceBounds.width(), warning ? AMBER : TEXT);
        sentenceTooltip = status != null ? status + "\n" + sentence
            : WatchUi.clipped(font, sentence, sentenceBounds.width()) ? sentence : "";
        int bx = right - buttons;
        configure(remove, bx, y, removeWidth, Component.translatable("codon.breakpoint.strip.remove"),
            state.breakpoints().get(original.target()) != null && state.breakpoints().ready() && !pending, false, this::delete);
        remove.withStatusColor(RED, RED_SURFACE);
        bx += removeWidth + GAP;
        configure(cancel, bx, y, cancelWidth, Component.translatable("codon.breakpoint.cancel"), !pending, false, this::cancel);
        bx += cancelWidth + GAP;
        configure(save, bx, y, saveWidth, Component.translatable("codon.breakpoint.save"), canSave(), false, this::save);
        save.withStatusColor(TEAL, TEAL_SURFACE).setTooltip(Tooltip.create(Component.translatable("codon.breakpoint.strip.save_hint")));
    }

    private void configure(DebuggerButton button, int x, int y, int width, Component label, boolean active,
                           boolean selected, Runnable action) {
        button.configure(x, y, Math.max(1, width), CONTROL, label, active, selected, false, false, action);
        button.withTextPadding(4);
        visible.add(button);
    }

    private static int segment(Font font, String text) { return Math.max(12, font.width(text) + 6); }
    private static int button(Font font, String text) { return Math.max(20, font.width(text) + 8); }

    private int metricsWidth(Font font) {
        int width = 0;
        for (var metric : METRICS) width += segment(font, tr("codon.breakpoint.strip.metric." + key(metric)));
        return width;
    }

    private static int scaled(int width, int total, int room) {
        return total <= room ? width : Math.max(12, width * room / total);
    }

    private static int segmentsBefore(Font font, String[] labels, int total, int room, int count) {
        int width = 0;
        for (int i = 0; i < count; i++) width += scaled(segment(font, labels[i]), total, room);
        return width;
    }

    /**
     * Shows a −/+ glyph but keeps its full name as the message, for narration and its hover tooltip,
     * so it is never confused with the − and + count metrics.
     */
    private static final class StepButton extends DebuggerButton {
        private final Component glyph;

        StepButton(String glyph) { this.glyph = Component.literal(glyph); }

        @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            Component name = getMessage();
            setMessage(glyph);
            super.extractContents(graphics, mouseX, mouseY, partialTick);
            setMessage(name);
        }
    }

    // ---------------------------------------------------------------- text

    private @Nullable String status() {
        var error = state.breakpoints().error(original.target());
        if (error != null) return tr("codon.breakpoint.error." + error.name().toLowerCase(Locale.ROOT));
        if (sendFailed || !state.breakpoints().ready()) return tr("codon.breakpoint.request_unavailable");
        if (pending()) return tr(deleting ? "codon.breakpoint.deleting" : "codon.breakpoint.saving");
        if (!draft.valid()) return tr("codon.breakpoint.invalid_count");
        if (draft.stopsAfter() && previewLoading()) return tr("codon.breakpoint.loading_stages");
        if (!supportedCondition()) return tr("codon.breakpoint.unsupported_result");
        return null;
    }

    /** The rule in words: Always stops before the target runs, Event and Count after it. */
    String sentence() {
        BreakpointTarget target = original.target();
        String subject = !target.wholeCommand() ? tr("codon.breakpoint.strip.subject.stage", target.stageIndex() + 1)
            : target.location() instanceof SourceLocation.Function function
                ? tr("codon.breakpoint.strip.subject.line", function.location().line())
                : tr("codon.breakpoint.strip.subject.command");
        if (draft.stopsAfter() && target.wholeCommand()) subject = tr("codon.breakpoint.strip.subject.each_stage", subject);
        return switch (draft.mode()) {
            case ALWAYS -> tr("codon.breakpoint.strip.rule.always", subject);
            case EVENT -> tr("codon.breakpoint.strip.rule.event", subject, eventLabel(draft.event()));
            case COUNT -> tr("codon.breakpoint.strip.rule.count", subject,
                tr("codon.breakpoint.strip.noun." + key(draft.metric())), symbol(draft.comparison()),
                draft.valid() ? Integer.toString(draft.thresholdValue()) : "?");
        };
    }

    /** The metric's value in the latest recorded run of this exact stage; unmeasured stays distinct from zero. */
    private String lastRecorded() {
        BreakpointTarget target = original.target();
        var snapshot = state.snapshot();
        if (target.wholeCommand() || snapshot == null) return "";
        for (int index = snapshot.executionFlows().size() - 1; index >= 0; index--) {
            var flow = snapshot.executionFlows().get(index);
            if (!flow.location().equals(target.location()) || flow.stages().isEmpty()) continue;
            if (!target.commandFingerprint().equals(BreakpointTarget.fingerprint(flow.stages().getFirst().command().text()))) continue;
            for (ExecutionFlowStage stage : flow.stages()) {
                if (stage.index() != target.stageIndex()) continue;
                int value = BreakpointConditionEvaluator.observedCount(draft.metric(), stage);
                return tr("codon.breakpoint.strip.last", value < 0 ? tr("codon.breakpoint.strip.unmeasured") : Integer.toString(value));
            }
        }
        return tr("codon.breakpoint.strip.last", tr("codon.breakpoint.strip.not_recorded"));
    }

    private static String eventLabel(BreakpointCondition.Kind event) {
        return tr("codon.breakpoint.strip.event." + key(event));
    }

    private static String key(BreakpointCondition.Kind kind) { return kind.name().toLowerCase(Locale.ROOT); }

    static String symbol(BreakpointCondition.Comparison comparison) {
        return switch (comparison) {
            case EQ -> "="; case NE -> "≠"; case LT -> "<";
            case LE -> "≤"; case GT -> ">"; case GE -> "≥";
        };
    }

    private static BreakpointCondition.Comparison cycle(BreakpointCondition.Comparison value, int direction) {
        var values = BreakpointCondition.Comparison.values();
        return values[Math.floorMod(value.ordinal() + direction, values.length)];
    }

    private static String tr(String key, Object... args) { return Component.translatable(key, args).getString(); }

    // ---------------------------------------------------------------- input

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!validContext()) return true;
        for (var child : visible) {
            if (!child.isMouseOver(event.x(), event.y())) continue;
            setFocused(child);
            if (child.mouseClicked(event, doubleClick) && event.button() == InputConstants.MOUSE_BUTTON_LEFT) setDragging(true);
            return true;
        }
        // A click on the strip's own background keeps it focused for Esc.
        if (getFocused() == null) setFocused(confirmingDiscard ? keepEditing : cancel);
        return true;
    }

    @Override public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (scrollY != 0 && draft.mode() == BreakpointConditionDraft.Mode.COUNT
            && (threshold.isMouseOver(x, y) || decrease.isMouseOver(x, y) || increase.isMouseOver(x, y)))
            setDraft(draft.step(scrollY > 0 ? 1 : -1));
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (!validContext()) return true;
        int key = event.key();
        if (key == InputConstants.KEY_ESCAPE) { cancel(); return true; }
        if (pending()) return true;
        if (key == InputConstants.KEY_TAB) {
            moveFocus(event.hasShiftDown() ? -1 : 1);
            return true;
        }
        if (getFocused() == threshold && (key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN)) {
            setDraft(draft.step(key == InputConstants.KEY_UP ? 1 : -1));
            return true;
        }
        if (!confirmingDiscard && getFocused() == threshold
            && (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER)) {
            save();
            return true;
        }
        if ((key == InputConstants.KEY_LEFT || key == InputConstants.KEY_RIGHT) && getFocused() != threshold
            && getFocused() instanceof DebuggerButton) {
            moveFocus(key == InputConstants.KEY_LEFT ? -1 : 1);
            return true;
        }
        var focused = getFocused();
        if (focused != null) focused.keyPressed(event);
        return true;
    }

    @Override public boolean charTyped(CharacterEvent event) {
        if (!validContext() || pending() || confirmingDiscard) return true;
        if (getFocused() == threshold) threshold.charTyped(event);
        return true;
    }

    private void moveFocus(int direction) {
        List<AbstractWidget> targets = visible.stream().filter(widget -> widget.active).toList();
        if (targets.isEmpty()) return;
        int index = targets.indexOf(getFocused());
        setFocused(targets.get(Math.floorMod(index < 0 ? (direction > 0 ? -1 : 0) + direction : index + direction,
            targets.size())));
    }
}
