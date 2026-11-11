package works.nuty.codon.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.CodonClientMod;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.ui.layout.DebuggerLayout;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout;
import works.nuty.codon.client.ui.layout.DockLayout;
import works.nuty.codon.client.ui.layout.WatchPanelLayout;
import works.nuty.codon.client.ui.layout.DebuggerToolbarLayout;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Anchor;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import works.nuty.codon.core.model.ExecutionFlowContext;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.SourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static works.nuty.codon.client.ui.DebuggerTheme.*;
import static works.nuty.codon.client.state.DebuggerPreferences.*;

/** Shared HUD/screen presentation. Only the menu screen registers the rendered controls. */
public final class DebuggerOverlay {
    /** Compact windows show one dock section at a time, in the dock's own order. */
    private enum AuxiliaryPanel { NONE, CONTEXTS, WATCHES, CALL_STACK, BREAKPOINTS }
    private static final Bounds EMPTY = new Bounds(0, 0, 0, 0);
    private static final int MAX_WORLD_LABELS = 20;
    private static final int DOCK_GAP = 4;
    private static final int CONTEXT_HEADER = 22;
    private static final int CONTEXT_ROW = 18;
    private static final int CONTEXT_PADDING = 3;
    private static final int CONTEXT_ACTION = 14;
    /** Header plus one row, so a docked section is never reduced to a title bar. */
    private static final int CONTEXTS_MINIMUM = CONTEXT_HEADER + CONTEXT_ROW + CONTEXT_PADDING;
    /** The NBT tree keeps its heading and two rows before the expansion gives up its detail lines. */
    private static final int NBT_MINIMUM = 18 + 2 * 17 + 2;
    private static final int NBT_PREFERRED = 18 + 8 * 17 + 2;
    private static final int NBT_MESSAGE = 26;
    /** NbtTreePanel's heading height; a smaller allocation cannot hold the tree. */
    private static final int NBT_HEADING = 18;
    /** Blank advance (see font/inline_icon.json) reserving room for a toolbar icon inside wrapped text. */
    private static final int ICON_SLOT = 0xE000;
    private static final FontDescription ICON_SLOT_FONT =
        new FontDescription.Resource(Identifier.fromNamespaceAndPath("codon", "inline_icon"));
    private final ClientDebuggerState state;
    private final BackgroundOpacitySlider opacitySlider;
    private final NbtTreePanel nbtPanel;
    private final CommandPanel commandPanel;
    private final ScrollbarInput scrollbars = new ScrollbarInput();
    private final PanelResizeInput panelResizing = new PanelResizeInput();
    private final DebuggerNavigation navigation = new DebuggerNavigation();
    private DebuggerNavigation.Group navigationGroup = DebuggerNavigation.Group.TOOLBAR;
    private final Minecraft client = Minecraft.getInstance();
    private final Map<String, DebuggerButton> buttonCache = new HashMap<>();
    private final List<DebuggerButton> controls = new ArrayList<>();
    private final Set<String> usedButtons = new HashSet<>();
    private final Set<Integer> visibleSources = new HashSet<>();
    private @Nullable PauseSnapshot lastSnapshot;
    private @Nullable String copiedUuid;
    private long copiedUuidUntil;
    private List<Integer> expandedGroup = List.of();
    private int sourceOffset;
    private int maxSourceOffset;
    private int lastSourceRows = -1;
    private int lastSelectedSource = -1;
    private boolean lastContextExpanded = true;
    private final WatchPanel watchPanel;
    private final CallStackPanel callStackPanel;
    private final BreakpointPanel breakpointPanel;
    private Bounds sourceScrollBounds = EMPTY;
    private Bounds contextsBounds = EMPTY;
    private Bounds watchSummaryBounds = EMPTY;
    /** The selected context row shows its details and NBT tree under it until clicked again. */
    private boolean contextExpanded = true;
    private final Map<DebuggerButton, Runnable> contextMenus = new HashMap<>();
    private Bounds viewTriggerBounds = EMPTY;
    private Bounds viewMenuBounds = EMPTY;
    private final List<DebuggerButton> viewMenuButtons = new ArrayList<>();
    private boolean viewMenuOpen;
    private boolean showContexts;
    private boolean showWatches;
    private boolean showCallStack;
    private boolean showBreakpoints;
    /** Session-only section visibility; compact layouts use the auxiliary panel instead. */
    private boolean callStackOpen = true;
    private boolean breakpointsOpen;
    private boolean compactAuxiliary;
    private boolean narrowAuxiliary;
    private int lastAuxiliarySize = -1;
    private AuxiliaryPanel auxiliaryPanel = AuxiliaryPanel.NONE;
    private int hoverX = -1;
    int pointerX() { return hoverX; }
    int pointerY() { return hoverY; }
    private int hoverY = -1;

    public DebuggerOverlay(ClientDebuggerState state) {
        this.state = state;
        this.opacitySlider = new BackgroundOpacitySlider(state.preferences());
        this.nbtPanel = new NbtTreePanel(state);
        this.watchPanel = new WatchPanel(state);
        this.breakpointPanel = new BreakpointPanel(state);
        this.commandPanel = new CommandPanel(state, () -> { sourceOffset = 0; expandedGroup = List.of(); });
        this.callStackPanel = new CallStackPanel(state, () -> { sourceOffset = 0; expandedGroup = List.of(); }, () -> {
            // Show in Flow reveals Flow: it may be hidden through View or by the compact drawer.
            preferences().setCommandVisible(true);
            closeAuxiliaryPanel();
        });
    }

    public works.nuty.codon.client.state.DebuggerPreferences preferences() { return state.preferences(); }

    void commitBackgroundOpacity() { opacitySlider.commitPreview(); }

    ScrollbarInput scrollbars() { return scrollbars; }
    PanelResizeInput panelResizing() { return panelResizing; }

    public DebuggerNavigation navigation() { return navigation; }
    public WatchPanel watchPanel() { return watchPanel; }

    /** Opening an expression row needs its panel: wide layouts show Watches, compact ones derive it per frame. */
    void revealWatches() {
        if (!compactAuxiliary) state.preferences().setWatchesVisible(true);
    }
    void revealSelectedFlow(CodonScreen screen, String focusId) {
        // The destination stage has its own context list; a group expanded for the old one indexes stale rows.
        expandedGroup = List.of();
        sourceOffset = 0;
        preferences().setCommandVisible(true);
        closeAuxiliaryPanel();
        commandPanel.revealSelection();
        var level = client.level;
        var snapshot = state.snapshot();
        var flow = state.selectedExecutionFlow();
        int stage = state.selectedFlowStageIndex(), unobserved = state.selectedUnobservedStageIndex();
        int frame = state.selectedFrameIndex(), callFrame = state.selectedCallFrameIndex();
        navigation.requestFocusOnNextFrame(focusId, () -> client.level == level && client.gui.screen() == screen
            && ScreenLayers.get(screen) == null && preferences().commandVisible() && state.snapshot() == snapshot
            && state.selectedExecutionFlow() == flow && state.selectedFlowStageIndex() == stage
            && state.selectedUnobservedStageIndex() == unobserved && state.selectedFrameIndex() == frame
            && state.selectedCallFrameIndex() == callFrame);
    }
    void openFlowCondition(works.nuty.codon.core.model.BreakpointDefinition definition,
                           works.nuty.codon.core.model.BreakpointTarget marker) {
        commandPanel.openCondition(definition, marker);
    }
    boolean openFlowContextMenu(net.minecraft.client.gui.components.events.GuiEventListener focused) {
        Runnable context = contextMenus.get(focused);
        if (context != null) context.run();
        boolean opened = context != null || callStackPanel.openContextMenu(focused) || commandPanel.openContextMenu(focused);
        if (opened) panelResizing.cancel();
        return opened;
    }

    boolean viewMenuOpen() { return viewMenuOpen; }
    boolean viewTriggerContains(double x, double y) { return viewTriggerBounds.contains(x, y); }
    boolean viewMenuContains(double x, double y) { return viewMenuBounds.contains(x, y); }
    void closeViewMenu() {
        viewMenuOpen = false;
        opacitySlider.commitPreview();
    }
    boolean closeAuxiliaryPanel() {
        if (!compactAuxiliary || auxiliaryPanel == AuxiliaryPanel.NONE) return false;
        auxiliaryPanel = AuxiliaryPanel.NONE;
        return true;
    }

    @Nullable DebuggerButton viewMenuButtonAt(double x, double y) {
        if (!viewMenuOpen || !viewMenuBounds.contains(x, y)) return null;
        return viewMenuButtons.stream().filter(button -> button.isMouseOver(x, y)).findFirst().orElse(null);
    }

    public List<DebuggerButton> render(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                       float partialTick, boolean interactive, InputManager input) {
        controls.clear();
        scrollbars.beginFrame();
        panelResizing.beginFrame(graphics.guiWidth(), graphics.guiHeight());
        navigation.beginFrame(interactive && client.getLastInputType().isKeyboard());
        hoverX = interactive ? mouseX : -1;
        hoverY = interactive ? mouseY : -1;
        usedButtons.clear();
        sourceScrollBounds = EMPTY;
        viewTriggerBounds = viewMenuBounds = EMPTY;
        viewMenuButtons.clear();
        commandPanel.clearBounds();
        watchPanel.clearBounds();
        callStackPanel.clearBounds();
        breakpointPanel.clearBounds();
        contextMenus.clear();
        contextsBounds = watchSummaryBounds = EMPTY;
        nbtPanel.clearBounds();
        if (client.level == null || client.player == null) {
            buttonCache.clear();
            navigation.endFrame();
            scrollbars.endFrame();
            panelResizing.endFrame();
            return List.of();
        }
        // The live debugger panels must not present retained history after resume.
        PauseSnapshot snapshot = state.snapshot();
        if (snapshot != lastSnapshot) {
            expandedGroup = List.of();
            sourceOffset = Math.max(0, state.selectedSourceIndex());
            lastSnapshot = snapshot;
            copiedUuid = null;
        }
        Font font = client.font;
        if ((!state.isPaused() || snapshot == null) && !interactive) {
            if (state.preferences().idleBadgeVisible() && !client.gui.hud.getDebugOverlay().showDebugScreen()) {
                Component text = Component.literal("CODON · " + statusText() + " ")
                    .append(keybind(Component.literal("[").append(input.menuKey.getTranslatedKeyMessage()).append("]")));
                Bounds header = DebuggerLayout.create(graphics.guiWidth(), graphics.guiHeight(), false).idleBadgeRow();
                Bounds badge = new Bounds(header.x(), header.y(),
                    Math.min(graphics.guiWidth() - 2 * header.x(), font.width(text) + 14), header.height());
                graphics.fill(badge.x(), badge.y(), badge.x() + badge.width(), badge.y() + badge.height(), PANEL);
                graphics.outline(badge.x(), badge.y(), badge.width(), badge.height(), BORDER);
                graphics.enableScissor(header.x() + 7, header.y(),
                    header.x() + Math.max(7, badge.width() - 7), header.y() + header.height());
                graphics.text(font, text, header.x() + 7, header.y() + 5, MUTED, false);
                graphics.disableScissor();
            }
            buttonCache.clear();
            navigation.endFrame();
            scrollbars.endFrame();
            panelResizing.endFrame();
            return List.of();
        }

        int auxiliarySize = graphics.guiWidth() < 420 ? 2 : graphics.guiWidth() < 600 ? 1 : 0;
        if (auxiliarySize != lastAuxiliarySize) {
            auxiliaryPanel = auxiliarySize == 1 ? AuxiliaryPanel.CONTEXTS : AuxiliaryPanel.NONE;
            lastAuxiliarySize = auxiliarySize;
        }
        // An open expression row lives in the Watch section, so compact layouts must show it.
        // It is derived per frame, so closing the row restores the section the user had chosen.
        AuxiliaryPanel shownPanel = auxiliarySize != 0 && watchPanel.composerOpen() ? AuxiliaryPanel.WATCHES : auxiliaryPanel;
        // Breakpoints is cursor-only: the passive HUD ignores the choice, so it keeps Flow and labels,
        // and the next interactive frame shows the section again.
        if (!interactive && shownPanel == AuxiliaryPanel.BREAKPOINTS) shownPanel = AuxiliaryPanel.NONE;
        compactAuxiliary = auxiliarySize != 0;
        narrowAuxiliary = auxiliarySize == 2;
        showContexts = compactAuxiliary ? shownPanel == AuxiliaryPanel.CONTEXTS
            : state.preferences().inspectorVisible() != null ? state.preferences().inspectorVisible()
                : graphics.guiWidth() >= 420 && graphics.guiHeight() >= 220;
        showWatches = compactAuxiliary ? shownPanel == AuxiliaryPanel.WATCHES
            : state.preferences().watchesVisible();
        // Hiding Watch (View menu) ends the row, so its field never lingers unpainted.
        if (!showWatches && watchPanel.composerOpen()) watchPanel.closeComposer();
        showCallStack = compactAuxiliary ? shownPanel == AuxiliaryPanel.CALL_STACK : callStackOpen;
        // The saved-breakpoint list is a cursor-mode section; the passive HUD never shows it.
        showBreakpoints = interactive && (compactAuxiliary ? shownPanel == AuxiliaryPanel.BREAKPOINTS : breakpointsOpen);
        // Contexts and Call stack form the left dock; Watch and Breakpoints keep their right-hand column.
        boolean dockVisible = showContexts || showCallStack;
        boolean sideColumn = showWatches || showBreakpoints;
        boolean reserveDock = !narrowAuxiliary && (dockVisible || compactAuxiliary && sideColumn);
        int maximumDockWidth = DebuggerLayout.maximumDockWidth(graphics.guiWidth(), sideColumn);
        int dockWidth = Math.min(maximumDockWidth, panelResizing.requestedWidth("dock", preferences().inspectorWidth()));
        // Explicit compact Watch and Breakpoints, and any narrow drawer, use the workspace until closed.
        boolean showCommand = state.preferences().commandVisible()
            && !(narrowAuxiliary && shownPanel != AuxiliaryPanel.NONE
                || compactAuxiliary && (shownPanel == AuxiliaryPanel.WATCHES || shownPanel == AuxiliaryPanel.BREAKPOINTS));
        DebuggerLayout layout = DebuggerLayout.create(graphics.guiWidth(), graphics.guiHeight(), reserveDock,
            showCommand
                ? commandPanel.preferredHeight(graphics.guiWidth(), graphics.guiHeight(), snapshot) : 0,
            dockWidth, DebuggerHudInsets.bottom(preferences()));
        Bounds auxiliaryBounds = narrowAuxiliary
            ? new Bounds(layout.world().x(), layout.world().y(),
                Math.min(240, Math.max(0, layout.world().width() - 32)), layout.world().height())
            : layout.dock();
        navigationGroup = DebuggerNavigation.Group.TOOLBAR;
        renderToolbar(graphics, layout, input, snapshot);
        if (viewMenuOpen) renderViewMenu(graphics);
        if (viewMenuBounds.contains(mouseX, mouseY)) hoverX = hoverY = -1;
        navigationGroup = DebuggerNavigation.Group.WATCH;
        if (sideColumn) {
            Bounds column = compactAuxiliary ? auxiliaryBounds
                : WatchPanelLayout.available(layout, graphics.guiWidth(),
                    panelResizing.requestedWidth("watch", preferences().watchWidth()));
            // Wide layouts stack Breakpoints under Watches, leaving the list at least one row.
            int stacked = showWatches && showBreakpoints ? Math.min(breakpointPanel.preferredHeight(),
                Math.max(BreakpointPanel.MINIMUM_HEIGHT, column.height() / 2)) : 0;
            if (showWatches) {
                renderWatchSummary(graphics, stacked == 0 ? column : new Bounds(column.x(), column.y(), column.width(),
                    Math.max(0, column.height() - stacked - 4)), hoverX, hoverY, interactive, input);
            }
            if (showBreakpoints) {
                navigationGroup = DebuggerNavigation.Group.BREAKPOINT;
                int top = stacked == 0 || watchPanel.bounds().height() == 0 ? column.y()
                    : watchPanel.bounds().y() + watchPanel.bounds().height() + 4;
                controls.addAll(breakpointPanel.render(graphics,
                    new Bounds(column.x(), top, column.width(), Math.max(0, column.y() + column.height() - top)),
                    navigation, scrollbars));
            }
        }
        if (watchPanel.groupingMenuContains(mouseX, mouseY)) hoverX = hoverY = -1;
        navigationGroup = DebuggerNavigation.Group.WORLD;
        if (!narrowAuxiliary || shownPanel == AuxiliaryPanel.NONE)
            renderWorldLabels(graphics, layout.world(), snapshot);
        // After the world labels, so the selected context's details know whether it has a label.
        if (dockVisible) renderDock(graphics, auxiliaryBounds, snapshot, input);
        if (interactive && !compactAuxiliary) {
            if (dockVisible) panelResizing.add("dock", auxiliaryBounds, false,
                MIN_INSPECTOR_WIDTH, maximumDockWidth, preferences()::setInspectorWidth);
            if (showWatches) panelResizing.add("watch", watchPanel.bounds(), true,
                MIN_WATCH_WIDTH, WatchPanelLayout.maximumWidth(layout), preferences()::setWatchWidth);
            else if (showBreakpoints) panelResizing.add("watch", breakpointPanel.bounds(), true,
                MIN_WATCH_WIDTH, WatchPanelLayout.maximumWidth(layout), preferences()::setWatchWidth);
        }
        if (showCommand) {
            controls.addAll(commandPanel.render(graphics, layout.command(), snapshot, input, this, navigation));
        }
        buttonCache.keySet().retainAll(usedButtons);
        navigation.endFrame();
        scrollbars.endFrame();
        panelResizing.endFrame();
        for (DebuggerButton button : controls) {
            if (viewMenuButtons.contains(button) || watchPanel.isGroupingChoice(button)) continue;
            if (!interactive) button.setFocused(false);
            boolean covered = viewMenuBounds.contains(mouseX, mouseY) || watchPanel.groupingMenuContains(mouseX, mouseY);
            button.extractRenderState(graphics, interactive && !covered ? mouseX : -1,
                interactive && !covered ? mouseY : -1, partialTick);
        }
        boolean groupingCovered = viewMenuBounds.contains(mouseX, mouseY);
        watchPanel.paintGroupingMenu(graphics, groupingCovered ? -1 : mouseX,
            groupingCovered ? -1 : mouseY, partialTick, interactive);
        if (viewMenuOpen) {
            graphics.fill(viewMenuBounds.x(), viewMenuBounds.y(), viewMenuBounds.x() + viewMenuBounds.width(),
                viewMenuBounds.y() + viewMenuBounds.height(), PANEL);
            graphics.outline(viewMenuBounds.x(), viewMenuBounds.y(), viewMenuBounds.width(),
                viewMenuBounds.height(), BORDER);
            for (DebuggerButton button : viewMenuButtons) {
                if (!interactive) button.setFocused(false);
                button.extractRenderState(graphics, interactive ? mouseX : -1, interactive ? mouseY : -1, partialTick);
            }
            paintOpacityRow(graphics);
        }
        if (interactive && !viewMenuOpen && !watchPanel.groupingMenuOpen())
            panelResizing.paint(graphics, mouseX, mouseY);
        return List.copyOf(controls);
    }

    private String statusText() {
        String status = tr(DebuggerStatus.translationKey(state));
        // The pause reason stays primary; withheld details only qualify it.
        return state.isPaused() && state.withheldPauseDetails() != null
            ? status + " · " + tr("codon.ui.details_withheld") : status;
    }

    /** Pause-reason states the pill can show; the widest sets its slot so the toolbar never shifts. */
    private static final List<String> STATUS_KEYS = List.of("codon.ui.breakpoint_hit", "codon.ui.step_complete",
        "codon.ui.execution_complete", "codon.ui.running", "codon.ui.waiting", "codon.ui.watch_reads_failed");

    private void renderToolbar(GuiGraphicsExtractor graphics, DebuggerLayout layout, InputManager input,
                               @Nullable PauseSnapshot snapshot) {
        Bounds toolbar = layout.toolbar();
        graphics.fill(toolbar.x(), toolbar.y(), toolbar.x() + toolbar.width(), toolbar.y() + toolbar.height(), PANEL);
        graphics.outline(toolbar.x(), toolbar.y(), toolbar.width(), toolbar.height(), BORDER);
        long breakpointCount = state.breakpoints().definitions().stream().filter(BreakpointDefinition::enabled).count();
        Component breakpointLabel = Component.translatable("codon.breakpoint.short_count", breakpointCount);
        Component breakpointCompactLabel = Component.literal(Long.toString(breakpointCount));
        int pillPadding = 6;
        int pillWidth = STATUS_KEYS.stream().mapToInt(key -> client.font.width(tr(key))).max().orElse(0) + 2 * pillPadding;
        Component viewLabel = component("codon.ui.view");
        var bar = DebuggerToolbarLayout.create(toolbar, InputManager.Control.values().length, pillWidth,
            client.font.width(breakpointLabel) + DebuggerButton.TEXT_ICON_INSET + 12,
            client.font.width(breakpointCompactLabel) + DebuggerButton.TEXT_ICON_INSET + 12,
            client.font.width(viewLabel) + 10 + DebuggerButton.DISCLOSURE_WIDTH);

        int index = 0;
        for (InputManager.Control action : InputManager.Control.values()) {
            DebuggerIcon icon = switch (action) {
                case RESUME -> DebuggerIcon.CONTINUE;
                case OVER -> DebuggerIcon.STEP_OVER;
                case INTO -> DebuggerIcon.STEP_INTO;
                case OUT -> DebuggerIcon.STEP_OUT;
            };
            DebuggerButton control = iconButton("control-" + action, bar.controls().get(index++),
                component(action.translationKey()).copy().append(" ").append(keybind(input.keyLabel(action))),
                icon, snapshot != null && state.isPaused()
                    && (action == InputManager.Control.RESUME || !state.watchReadsFailed())
                    && (!state.controlPending() || action == InputManager.Control.RESUME && state.controlAwaitingReads()),
                () -> input.control(action));
            if (action == InputManager.Control.RESUME) control.withStatusColor(AMBER, AMBER_SURFACE);
            else control.withFlatChrome();
        }
        Bounds last = bar.controls().getLast();
        int divider = last.x() + last.width() + (DebuggerLayout.ICON_GROUP_GAP - 1) / 2 + 1;
        graphics.fill(divider, toolbar.y() + 5, divider + 1, toolbar.y() + toolbar.height() - 5, BORDER);
        renderPausePill(graphics, bar.pill(), pillPadding);

        DebuggerButton breakpoints = button("breakpoints", bar.breakpoints(), breakpointLabel,
            true, showBreakpoints, false, false, this::toggleBreakpoints)
            .withTextIcon(DebuggerIcon.BREAKPOINT_LIST);
        // Narrow toolbars paint only the count; the message keeps the full label for narration.
        if (bar.breakpointsCompact()) breakpoints.withDisplayText(breakpointCompactLabel);
        breakpoints.setTooltip(Tooltip.create(Component.translatable("codon.breakpoint.toolbar",
                breakpointCount, state.breakpoints().definitions().size())));

        boolean keepFreecam = state.preferences().keepFreecam();
        button("keep-freecam", bar.freecam(),
            component(keepFreecam ? "codon.ui.keep_freecam.on" : "codon.ui.keep_freecam.off")
                .copy().append(" ").append(keybind(input.keepFreecamKey.getTranslatedKeyMessage())),
            true, keepFreecam, false, false,
            input::toggleKeepFreecam)
            .withIcon(DebuggerIcon.FREECAM);
        iconButton("source", bar.source(), Component.translatable("codon.source.title"), DebuggerIcon.SOURCE_FILE,
            CodonClientMod.sources() != null, () -> {
                if (client.gui.screen() instanceof FunctionSourceScreen open) {
                    open.onClose();
                    return;
                }
                var sources = CodonClientMod.sources();
                if (sources != null && client.gui.screen() != null) {
                    if (state.selectedLocation() instanceof SourceLocation.Function function)
                        sources.selectAt(function.location());
                    client.gui.setScreen(new FunctionSourceScreen(client.gui.screen(), sources));
                }
            });
        viewTriggerBounds = bar.view();
        button("view", viewTriggerBounds, viewLabel, true, viewMenuOpen, false, false, () -> {
            if (viewMenuOpen) closeViewMenu();
            else viewMenuOpen = true;
        }).withDisclosure();
        iconButton("help", bar.help(), component("codon.ui.help"), DebuggerIcon.INFORMATION, true,
            () -> client.gui.setScreen(new DebuggerHelpScreen(new CodonScreen(input, this), input)));
    }

    /** Acknowledged server state only: the reason of the current snapshot, or the pending/running state. */
    private void renderPausePill(GuiGraphicsExtractor graphics, Bounds pill, int padding) {
        if (pill.width() < 2 * padding + client.font.width("…")) return;
        String key = DebuggerStatus.translationKey(state);
        int foreground = key.equals("codon.ui.watch_reads_failed") ? RED : state.isPaused() && state.snapshot() != null
            && !key.equals("codon.ui.waiting") ? AMBER : MUTED;
        int surface = foreground == RED ? RED_SURFACE : foreground == AMBER ? AMBER_SURFACE : RAISED;
        int top = pill.y() + 3;
        int height = pill.height() - 6;
        graphics.fill(pill.x(), top, pill.x() + pill.width(), top + height, surface);
        graphics.outline(pill.x(), top, pill.width(), height, foreground == MUTED ? BORDER : foreground);
        text(graphics, tr(key), pill.x() + padding, top + (height - client.font.lineHeight) / 2 + 1,
            pill.width() - 2 * padding, foreground, true);
    }

    private void toggleBreakpoints() {
        if (compactAuxiliary) auxiliaryPanel = showBreakpoints ? AuxiliaryPanel.NONE : AuxiliaryPanel.BREAKPOINTS;
        else breakpointsOpen = !showBreakpoints;
    }

    private static final int VIEW_MENU_ROWS = 8;
    private static final int VIEW_MENU_OPACITY_ROW = 6;

    private void renderViewMenu(GuiGraphicsExtractor graphics) {
        int menuWidth = Math.min(154, graphics.guiWidth() - 12);
        int menuHeight = VIEW_MENU_ROWS * 19 + 4;
        // Anchored under the trigger's right edge, since View sits at the toolbar's right end.
        int x = Math.clamp(viewTriggerBounds.x() + viewTriggerBounds.width() - menuWidth, 6,
            Math.max(6, graphics.guiWidth() - menuWidth - 6));
        int below = viewTriggerBounds.y() + viewTriggerBounds.height() + 2;
        int y = below + menuHeight <= graphics.guiHeight() - 6 ? below
            : Math.max(6, viewTriggerBounds.y() - menuHeight - 2);
        viewMenuBounds = new Bounds(x, y, menuWidth, menuHeight);
        navigationGroup = DebuggerNavigation.Group.VIEW_MENU;
        Component mode = Component.translatable("codon.ui.gizmo_mode",
            component("codon.ui.mode." + state.gizmoMode().name().toLowerCase(Locale.ROOT)));
        viewMenuItem(0, mode, false, () -> {
            state.setGizmoMode(state.gizmoMode().next());
            expandedGroup = List.of();
            sourceOffset = 0;
        });
        // Dock sections, in the dock's order; Breakpoints is the toolbar's count button.
        viewMenuItem(1, component("codon.ui.contexts"), showContexts, () -> {
            if (compactAuxiliary) auxiliaryPanel = showContexts ? AuxiliaryPanel.NONE : AuxiliaryPanel.CONTEXTS;
            else state.preferences().setInspectorVisible(!showContexts);
        });
        viewMenuItem(2, component("codon.watch.title"), showWatches, () -> {
            if (compactAuxiliary) auxiliaryPanel = showWatches ? AuxiliaryPanel.NONE : AuxiliaryPanel.WATCHES;
            else state.preferences().setWatchesVisible(!showWatches);
        });
        viewMenuItem(3, component("codon.ui.callstack.title"), showCallStack, () -> {
            if (compactAuxiliary) auxiliaryPanel = showCallStack ? AuxiliaryPanel.NONE : AuxiliaryPanel.CALL_STACK;
            else callStackOpen = !showCallStack;
        });
        boolean command = state.preferences().commandVisible();
        viewMenuItem(4, component("codon.ui.command"), command,
            () -> state.preferences().setCommandVisible(!command));
        boolean idleBadge = state.preferences().idleBadgeVisible();
        viewMenuItem(5, component("codon.ui.idle_badge"), idleBadge,
            () -> state.preferences().setIdleBadgeVisible(!idleBadge));
        Bounds opacity = viewMenuRow(VIEW_MENU_OPACITY_ROW);
        int labelWidth = client.font.width(component("codon.ui.opacity")) + 14;
        int percentWidth = client.font.width("100%") + 6;
        opacitySlider.position(opacity.x() + labelWidth, opacity.y() + 1,
            Math.max(16, opacity.width() - labelWidth - percentWidth));
        controls.add(opacitySlider);
        viewMenuButtons.add(opacitySlider);
        navigation.bind("background-opacity", DebuggerNavigation.Group.VIEW_MENU, opacitySlider);
        viewMenuItem(7, component("codon.ui.scale.title"), false,
            () -> { if (client.gui.screen() != null) client.gui.setScreen(new UiScaleScreen(client.gui.screen(), state.preferences())); });
    }

    /** The slider row's label and always-visible value, painted over the menu surface. */
    private void paintOpacityRow(GuiGraphicsExtractor graphics) {
        Bounds row = viewMenuRow(VIEW_MENU_OPACITY_ROW);
        int textY = row.y() + (row.height() - client.font.lineHeight) / 2 + 1;
        graphics.text(client.font, component("codon.ui.opacity"), row.x() + 14, textY, TEXT, false);
        String percent = state.preferences().backgroundOpacity() + "%";
        graphics.text(client.font, percent, row.x() + row.width() - 4 - client.font.width(percent), textY, TEXT, false);
    }

    private Bounds viewMenuRow(int row) {
        return new Bounds(viewMenuBounds.x() + 2, viewMenuBounds.y() + 2 + row * 19, viewMenuBounds.width() - 4, 18);
    }

    private void viewMenuItem(int row, Component label, boolean selected, Runnable action) {
        DebuggerButton button = button("view-option-" + row, viewMenuRow(row),
            Component.literal(selected ? "● " : "  ").append(label), true, selected, true, false, () -> {
                action.run();
                closeViewMenu();
            });
        viewMenuButtons.add(button);
    }

    private void renderWorldLabels(GuiGraphicsExtractor graphics, Bounds world, @Nullable PauseSnapshot snapshot) {
        visibleSources.clear();
        if (world.width() <= 0 || world.height() < 30 || snapshot == null || !state.isPaused()) return;
        var camera = client.gameRenderer.mainCamera();
        if (!camera.isInitialized()) return;
        String dimension = dimension();
        Bounds labelArea = new Bounds(world.x() + 2, world.y() + 2,
            Math.max(0, world.width() - 4), Math.max(0, world.height() - 18));
        List<Anchor> anchors = new ArrayList<>();
        List<PauseSource> displayedSources = state.worldSources();
        int selectedIndex = state.selectedWorldSourceIndex();
        int inspectorSourceCount = state.displayedSources().size();
        for (int index = 0; index < displayedSources.size(); index++) {
            PauseSource source = displayedSources.get(index);
            if (!dimension.equals(source.dimension())) continue;
            Vec3 point = new Vec3(source.anchor().x(), source.anchor().y(), source.anchor().z());
            Vec3 relative = point.subtract(camera.position());
            var forward = camera.forwardVector();
            if (relative.x * forward.x() + relative.y * forward.y() + relative.z * forward.z() <= 0.05) continue;
            Vec3 projected = client.gameRenderer.projectPointToScreen(point);
            if (!Double.isFinite(projected.x) || !Double.isFinite(projected.y) || !Double.isFinite(projected.z)
                || Math.abs(projected.x) > 1 || Math.abs(projected.y) > 1) continue;
            double x = (projected.x + 1) * 0.5 * graphics.guiWidth();
            double y = (1 - projected.y) * 0.5 * graphics.guiHeight();
            if (!labelArea.contains(x, y) || watchSummaryBounds.contains(x, y) || breakpointPanel.bounds().contains(x, y)) continue;
            // A transition's output prefix is exactly the current stage's inputs; appended drops
            // are historical markers, not live inspector/Watch/NBT indices.
            if (index < inspectorSourceCount) visibleSources.add(index);
            // Status glyphs share the existing text budget; grouping and label slots stay fixed.
            int labelWidth = Math.min(150,
                client.font.width(sourceLabel(source, index, state.isWorldSourceDropped(index))) + 14);
            anchors.add(new Anchor(index, x, y, labelWidth));
        }
        List<GizmoLabelLayout.Label> labels = GizmoLabelLayout.layout(anchors, labelArea,
            selectedIndex, state.gizmoMode() != ClientDebuggerState.GizmoMode.LABELS,
            Stream.of(watchSummaryBounds, breakpointPanel.bounds()).filter(bounds -> bounds.width() > 0).toList(),
            MAX_WORLD_LABELS);
        for (GizmoLabelLayout.Label label : labels) {
            List<Integer> indices = label.sourceIndices();
            boolean selected = indices.contains(selectedIndex);
            boolean group = indices.size() > 1;
            int index = indices.getFirst();
            // A mixed, unselected group has no single status; a selected group names its selected member.
            int statusIndex = group && !selected && indices.stream()
                .anyMatch(member -> worldSourceColor(member, TEXT) != worldSourceColor(index, TEXT)) ? -1 : index;
            Bounds bounds = label.bounds();
            String sourceTitle = sourceLabel(displayedSources.get(index), index, state.isWorldSourceDropped(index),
                state.isWorldSourceCreated(index));
            Component title;
            if (group) {
                String count = "  +" + (indices.size() - 1);
                title = Component.literal(trimmed(sourceTitle, Math.max(0, bounds.width() - 10 - client.font.width(count))) + count);
            } else {
                title = Component.literal(sourceTitle);
            }
            leader(graphics, (int) label.anchorX(), (int) label.anchorY(),
                bounds.x() + bounds.width() / 2, bounds.y() + bounds.height(), worldSourceColor(statusIndex, selected ? TEAL : MUTED));
            int groupId = indices.stream().mapToInt(Integer::intValue).min().orElse(index);
            DebuggerButton sourceButton = colorWorldSourceButton(button("label-" + groupId, bounds, title, true, selected, false, false, () -> {
                if (state.snapshot() != snapshot) return;
                state.selectWorldSource(index);
                if (group) {
                    expandedGroup = List.copyOf(indices);
                    sourceOffset = 0;
                } else {
                    expandedGroup = List.of();
                    sourceOffset = index;
                }
                if (compactAuxiliary) auxiliaryPanel = AuxiliaryPanel.CONTEXTS;
                else state.preferences().setInspectorVisible(true);
                contextExpanded = true;
            }), statusIndex);
            // A group names its representative; its status remains available even in a mixed group.
            if (state.isWorldSourceChanged(index)) sourceButton.withChangedDot(component("codon.ui.flow_changed"));
            sourceButton.setTooltip(state.isWorldSourceChanged(index) ? Tooltip.create(component("codon.ui.flow_changed")) : null);
        }

    }

    /** Saved pins first, followed by every change captured at this stop. */
    private void renderWatchSummary(GuiGraphicsExtractor graphics, Bounds available,
                                    int mouseX, int mouseY, boolean interactive, InputManager input) {
        controls.addAll(watchPanel.render(graphics, available, mouseX, mouseY, interactive, input, this, navigation, scrollbars));
        watchSummaryBounds = watchPanel.bounds();
    }

    /**
     * The left dock: Contexts above Call stack. Each visible section keeps its header and one row; an
     * expanded NBT tree then reserves its first rows, and spare height grows both toward their content.
     */
    private void renderDock(GuiGraphicsExtractor graphics, Bounds dock, @Nullable PauseSnapshot snapshot, InputManager input) {
        List<AuxiliaryPanel> kinds = new ArrayList<>();
        List<DockLayout.Section> sections = new ArrayList<>();
        if (showContexts) {
            boolean tree = snapshot != null && contextExpanded && nbtPanel.hasSelectedSource()
                && contextIndices().contains(state.selectedSourceIndex());
            kinds.add(AuxiliaryPanel.CONTEXTS);
            sections.add(new DockLayout.Section(CONTEXTS_MINIMUM, tree ? NBT_MINIMUM : 0,
                Math.max(CONTEXTS_MINIMUM, contextsPreferredHeight(dock.width(), snapshot, input))));
        }
        if (showCallStack) {
            kinds.add(AuxiliaryPanel.CALL_STACK);
            sections.add(section(CallStackPanel.MINIMUM_HEIGHT, callStackPanel.preferredHeight()));
        }
        int[] heights = DockLayout.heights(dock.height(), DOCK_GAP, sections);
        // Height left below both sections goes to Contexts, whose list and tree can always use it.
        int used = 0, placed = 0;
        for (int height : heights) if (height > 0) { used += height; placed++; }
        int spare = dock.height() - used - Math.max(0, placed - 1) * DOCK_GAP;
        if (spare > 0 && heights.length > 0 && heights[0] > 0) heights[0] += spare;
        int y = dock.y();
        int carry = 0;
        for (int i = 0; i < kinds.size(); i++) {
            if (heights[i] == 0) continue;
            Bounds area = new Bounds(dock.x(), y, dock.width(),
                Math.max(0, Math.min(heights[i] + carry, dock.y() + dock.height() - y)));
            int rendered;
            if (kinds.get(i) == AuxiliaryPanel.CONTEXTS) rendered = renderContexts(graphics, area, snapshot, input);
            else {
                controls.addAll(callStackPanel.render(graphics, area, navigation, scrollbars));
                rendered = callStackPanel.bounds().height();
            }
            carry = area.height() - rendered;
            y += rendered + (rendered > 0 ? DOCK_GAP : 0);
        }
    }

    private static DockLayout.Section section(int minimum, int preferred) {
        return new DockLayout.Section(minimum, Math.max(minimum, preferred));
    }

    /** Displayed context indices: the expanded world-label group, or every context of the stage. */
    private List<Integer> contextIndices() {
        int sourceCount = state.displayedSources().size();
        // Any selection path that bypasses the reset must never index past the displayed list.
        if (expandedGroup.stream().anyMatch(index -> index >= sourceCount)) expandedGroup = List.of();
        if (!expandedGroup.isEmpty()) return expandedGroup;
        List<Integer> all = new ArrayList<>(sourceCount);
        for (int i = 0; i < sourceCount; i++) all.add(i);
        return all;
    }

    private Component idleHint(InputManager input) {
        // The icon matches the toolbar's Source button so the control is easy to find.
        Component sourceButton = Component.empty()
            .append(Component.literal(Character.toString(ICON_SLOT)).withStyle(style -> style.withFont(ICON_SLOT_FONT)))
            .append(component("codon.source.title"));
        return Component.translatable("codon.ui.idle_hint",
            keybind(input.breakpointKey.getTranslatedKeyMessage()), sourceButton,
            keybind(input.menuKey.getTranslatedKeyMessage()));
    }

    private int contextsPreferredHeight(int width, @Nullable PauseSnapshot snapshot, InputManager input) {
        if (snapshot == null)
            return CONTEXT_HEADER + client.font.split(idleHint(input), Math.max(1, width - 16)).size() * 11 + 6;
        int rows = Math.max(1, contextIndices().size());
        PauseSource source = state.selectedSource();
        int expansion = 0;
        if (contextExpanded && source != null && contextIndices().contains(state.selectedSourceIndex()))
            expansion = detailLines(source, width - 14).size() * 11 + 6 + nbtPreferredHeight(source);
        return CONTEXT_HEADER + rows * CONTEXT_ROW + expansion + CONTEXT_PADDING;
    }

    private int nbtPreferredHeight(PauseSource source) {
        if (nbtPanel.hasSelectedSource()) return NBT_PREFERRED;
        return historyOnly(source) ? NBT_MESSAGE : 0;
    }

    /** A recorded entity context of an earlier stage has no NBT of its own; reads use the actual pause. */
    private boolean historyOnly(PauseSource source) {
        return !nbtPanel.hasSelectedSource() && state.selectedPauseSourceIndex() < 0 && source.entity() != null;
    }

    /**
     * Contexts section: a header with the count and where the contexts were recorded, one row per context,
     * and the selected row expanded in place into its details and NBT tree. Returns the height drawn.
     */
    private int renderContexts(GuiGraphicsExtractor graphics, Bounds area, @Nullable PauseSnapshot snapshot,
                               InputManager input) {
        navigationGroup = DebuggerNavigation.Group.SOURCES;
        if (area.width() < 72 || area.height() < CONTEXTS_MINIMUM) return 0;
        int headingY = area.y() + (CONTEXT_HEADER - client.font.lineHeight) / 2 + 1;
        if (snapshot == null) {
            int height = Math.min(area.height(), contextsPreferredHeight(area.width(), null, input));
            contextsBounds = new Bounds(area.x(), area.y(), area.width(), height);
            panel(graphics, contextsBounds);
            text(graphics, tr("codon.ui.contexts.count", 0), area.x() + 7, headingY, area.width() - 14, TEXT);
            wrapped(graphics, idleHint(input), new Bounds(area.x() + 8, area.y() + CONTEXT_HEADER + 1,
                area.width() - 16, height - CONTEXT_HEADER - 3), MUTED, DebuggerIcon.SOURCE_FILE);
            return height;
        }
        List<Integer> indices = contextIndices();
        int viewport = Math.max(0, area.height() - CONTEXT_HEADER - CONTEXT_PADDING);
        int selected = state.selectedSourceIndex();
        PauseSource selectedSource = state.selectedSource();
        int selectedRow = contextExpanded && selectedSource != null ? indices.indexOf(selected) : -1;
        // The expansion is a viewport allocation: tree loading and NBT expansion never move the rows.
        List<DetailLine> details = List.of();
        int expansion = 0, detailHeight = 0, nbtHeight = 0;
        if (selectedRow >= 0) {
            details = detailLines(selectedSource, area.width() - 14);
            int detailPreferred = details.size() * 11 + 6;
            expansion = Math.min(detailPreferred + nbtPreferredHeight(selectedSource), Math.max(0, viewport - CONTEXT_ROW));
            nbtHeight = nbtPanel.hasSelectedSource()
                ? Math.max(Math.min(NBT_MINIMUM, expansion), expansion - detailPreferred)
                : Math.max(0, expansion - detailPreferred);
            detailHeight = expansion - nbtHeight;
            if (detailHeight < 17) {
                nbtHeight += detailHeight;
                detailHeight = 0;
            }
            // The tree's heading is a fixed control; too little room shows details only, never a clipped heading.
            if (nbtHeight < NBT_HEADING) {
                detailHeight += nbtHeight;
                nbtHeight = 0;
            }
        }
        int count = indices.size();
        List<Integer> expansions = new ArrayList<>(java.util.Collections.nCopies(count, 0));
        if (selectedRow >= 0) expansions.set(selectedRow, expansion);
        var rows = new WatchPanelLayout.Rows(java.util.Collections.nCopies(count, true),
            java.util.Collections.nCopies(count, 0), java.util.Collections.nCopies(count, false), expansions);
        maxSourceOffset = rows.maximumOffset(viewport);
        if (viewport != lastSourceRows || selected != lastSelectedSource || contextExpanded != lastContextExpanded) {
            // Keep the inspected context identifiable after a resize, an external selection or re-expansion.
            // Clicking an already visible row and ordinary wheel scrolling keep their positions.
            int row = indices.indexOf(selected);
            if (row >= 0) sourceOffset = rows.reveal(row, sourceOffset, viewport);
        }
        lastSourceRows = viewport;
        lastSelectedSource = selected;
        lastContextExpanded = contextExpanded;
        for (int row = 0; row < count; row++) {
            int index = indices.get(row);
            int logicalRow = row;
            Runnable reveal = () -> sourceOffset = Math.clamp(rows.reveal(logicalRow, sourceOffset, viewport), 0, maxSourceOffset);
            navigation.add("source-" + index, DebuggerNavigation.Group.SOURCES, row, 0, reveal);
            if (state.displayedSources().get(index).entity() != null)
                navigation.add("copy-uuid-" + index, DebuggerNavigation.Group.SOURCES, row, 1, reveal);
            if (index == selected) navigation.add("move-to-source", DebuggerNavigation.Group.SOURCES, row, 2, reveal);
        }
        navigation.revealFocus(DebuggerNavigation.Group.SOURCES);
        sourceOffset = Math.clamp(sourceOffset, 0, maxSourceOffset);
        int end = rows.visibleEnd(sourceOffset, viewport);
        int content = count == 0 ? CONTEXT_ROW : rows.height(sourceOffset, end);
        int height = Math.min(area.height(), CONTEXT_HEADER + content + CONTEXT_PADDING);
        contextsBounds = new Bounds(area.x(), area.y(), area.width(), height);
        sourceScrollBounds = new Bounds(area.x(), area.y() + CONTEXT_HEADER, area.width(), height - CONTEXT_HEADER);
        panel(graphics, contextsBounds);

        var withheld = state.isPaused() ? state.withheldPauseDetails() : null;
        int actionWidth = expandedGroup.isEmpty() ? 0 : 39;
        String heading = expandedGroup.isEmpty()
            ? tr("codon.ui.contexts.count", count == 0 && withheld != null ? withheld.pauseSourceCount() : count)
            : tr("codon.ui.group", expandedGroup.size());
        // The Flow detail band owns the selected stage; the header only says which contexts are listed.
        int headingWidth = Math.min(client.font.width(heading), area.width() - 14 - actionWidth);
        text(graphics, heading, area.x() + 7, headingY, headingWidth, TEXT);
        text(graphics, " · " + contextProvenance(), area.x() + 7 + headingWidth, headingY,
            area.width() - 14 - actionWidth - headingWidth, MUTED);
        if (!expandedGroup.isEmpty()) {
            navigation.add("all-sources", DebuggerNavigation.Group.SOURCES, -1, 0, () -> { });
            button("all-sources", new Bounds(area.x() + area.width() - 39, area.y() + 3, 35, 15),
                component("codon.ui.all"), true, false, false, false, () -> { expandedGroup = List.of(); sourceOffset = 0; });
        }
        if (count == 0) {
            String empty = withheld == null ? tr("codon.ui.no_sources")
                : Component.translatable("codon.ui.details_withheld_counts",
                    withheld.pauseSourceCount(), withheld.callStackSize()).getString();
            text(graphics, empty, area.x() + 7, area.y() + CONTEXT_HEADER + 5, area.width() - 14, MUTED);
        }
        for (int row = sourceOffset; row < end; row++) {
            int index = indices.get(row);
            int y = area.y() + CONTEXT_HEADER + rows.height(sourceOffset, row);
            PauseSource source = state.displayedSources().get(index);
            boolean isSelected = index == selected;
            int actions = (source.entity() != null ? CONTEXT_ACTION + 2 : 0) + (isSelected ? CONTEXT_ACTION + 2 : 0);
            Bounds rowArea = new Bounds(area.x() + 4, y, area.width() - 10, CONTEXT_ROW - 1);
            // The row draws its title (a clipped one is echoed by the button); the tooltip adds only status and dimension.
            List<Component> notes = new ArrayList<>();
            if (state.isDisplayedSourceChanged(index)) notes.add(component("codon.ui.flow_changed"));
            if (!source.dimension().equals(dimension())) notes.add(Component.literal(shortDimension(source.dimension())));
            DebuggerButton rowButton = colorSourceButton(button("source-" + index,
                new Bounds(rowArea.x(), y, rowArea.width() - actions, CONTEXT_ROW - 1),
                Component.literal(sourceLabel(source, index, state.isDisplayedSourceDropped(index),
                    state.isDisplayedSourceCreated(index))), true, isSelected, true, false, () -> {
                    if (state.snapshot() != snapshot) return;
                    // Clicking the selected row folds its details; any other row selects and expands.
                    if (state.selectedSourceIndex() == index) contextExpanded = !contextExpanded;
                    else {
                        state.selectSource(index);
                        contextExpanded = true;
                    }
                }).withFlatChrome(), index);
            rowButton.setTooltip(notes.isEmpty() ? null : Tooltip.create(joinLines(notes)));
            Runnable menu = () -> openContextMenu(snapshot, index, rowArea);
            rowButton.withSecondaryAction(menu).withNarrationHint(component("codon.ui.contexts.menu_hint"));
            contextMenus.put(rowButton, menu);
            renderContextActions(rowArea, source, index, isSelected);
            if (row == selectedRow && expansion > 0) {
                int expansionY = y + CONTEXT_ROW;
                if (detailHeight > 0)
                    renderContextDetails(graphics, new Bounds(area.x() + 4, expansionY, area.width() - 8, detailHeight), details);
                navigationGroup = DebuggerNavigation.Group.NBT;
                // Narrower than the list, so the tree's scrollbar never shares hits with the Contexts scrollbar.
                Bounds nbtBounds = new Bounds(area.x() + 3, expansionY + detailHeight, area.width() - 10, nbtHeight);
                if (nbtHeight > 0 && historyOnly(selectedSource)) {
                    wrapped(graphics, component("codon.nbt.history_unavailable"),
                        new Bounds(nbtBounds.x() + 4, nbtBounds.y() + 3, nbtBounds.width() - 8, nbtBounds.height() - 3), MUTED);
                }
                if (nbtHeight > 0) nbtPanel.render(graphics, nbtBounds, hoverX, hoverY, navigation, scrollbars,
                    (id, bounds, label, active, selectedButton, action) ->
                        button(id, bounds, label, active, selectedButton, true, false, action).withFlatChrome());
                navigationGroup = DebuggerNavigation.Group.SOURCES;
            }
        }
        if (maxSourceOffset > 0 && end > sourceOffset) {
            int top = area.y() + CONTEXT_HEADER;
            int visible = rows.height(sourceOffset, end);
            int total = rows.height(0, count);
            scrollbar(graphics, "sources", value -> sourceOffset = value, area.x() + area.width() - 4, top,
                Math.max(1, visible - 1), sourceOffset, maxSourceOffset, visible, total);
        }
        return height;
    }

    /** Row actions on the right: copy an entity's UUID, and move the camera to the selected context. */
    private void renderContextActions(Bounds row, PauseSource source, int index, boolean selected) {
        int x = row.x() + row.width();
        int y = row.y() + (row.height() - CONTEXT_ACTION) / 2;
        if (selected) {
            x -= CONTEXT_ACTION;
            var freecam = CodonClientMod.freecam();
            String status = freecam == null ? "unavailable" : freecam.selectedAnchorStatus(client);
            iconButton("move-to-source", new Bounds(x, y, CONTEXT_ACTION, CONTEXT_ACTION),
                component("codon.ui.move_to_source"), DebuggerIcon.FREECAM, status.equals("ready"),
                () -> { if (freecam != null) freecam.moveToSelectedAnchor(client); })
                .withoutChrome()
                // Ready needs no sentence beyond the icon label; only an unavailable state has a reason to show.
                .setTooltip(status.equals("ready") ? null : Tooltip.create(component("codon.ui.move_to_source." + status)));
            x -= 2;
        }
        if (source.entity() == null) return;
        x -= CONTEXT_ACTION;
        String uuid = source.entity().uuid().toString();
        boolean copied = uuid.equals(copiedUuid) && System.nanoTime() < copiedUuidUntil;
        DebuggerButton copy = iconButton("copy-uuid-" + index, new Bounds(x, y, CONTEXT_ACTION, CONTEXT_ACTION),
            Component.literal("UUID: " + uuid + "\n" + tr(copied ? "codon.ui.copied" : "codon.ui.copy")),
            copied ? DebuggerIcon.CONFIRM : DebuggerIcon.COPY_UUID, true, () -> copyUuid(uuid)).withoutChrome();
        // The selected row keeps its actions visible; other rows reveal theirs on hover or focus.
        if (!selected && !copied) copy.revealOnHover(row.x(), row.y(), row.width(), row.height());
    }

    private void copyUuid(String uuid) {
        client.keyboardHandler.setClipboard(uuid);
        copiedUuid = uuid;
        copiedUuidUntil = System.nanoTime() + 4_000_000_000L;
    }

    /** Right-click or Shift+F10: the menu acts on its row, so it first selects that row as a click would. */
    private void openContextMenu(@Nullable PauseSnapshot snapshot, int index, Bounds anchor) {
        var parent = client.gui.screen();
        if (parent == null || state.snapshot() != snapshot || index >= state.displayedSources().size()) return;
        if (state.selectedSourceIndex() != index) {
            state.selectSource(index);
            contextExpanded = true;
        }
        PauseSource source = state.displayedSources().get(index);
        closeViewMenu();
        scrollbars.release();
        panelResizing.cancel();
        var menu = new DebuggerContextMenu(parent, component("codon.ui.contexts"), anchor,
            Component.literal(sourceLabel(source, index, state.isDisplayedSourceDropped(index))), () -> {
                if (state.snapshot() != snapshot || state.selectedSourceIndex() != index) return List.of();
                List<DebuggerContextMenu.Item> items = new ArrayList<>();
                if (source.entity() != null) {
                    String uuid = source.entity().uuid().toString();
                    items.add(new DebuggerContextMenu.Item(component("codon.ui.copy_uuid"), DebuggerIcon.COPY_UUID, true,
                        Component.literal(uuid), () -> copyUuid(uuid)));
                }
                var freecam = CodonClientMod.freecam();
                String status = freecam == null ? "unavailable" : freecam.selectedAnchorStatus(client);
                items.add(new DebuggerContextMenu.Item(component("codon.ui.move_to_source"), DebuggerIcon.FREECAM,
                    status.equals("ready"), status.equals("ready") ? null : component("codon.ui.move_to_source." + status),
                    () -> { if (freecam != null) freecam.moveToSelectedAnchor(client); }));
                items.add(new DebuggerContextMenu.Item(component(contextExpanded
                    ? "codon.ui.contexts.collapse" : "codon.ui.contexts.expand"),
                    contextExpanded ? DebuggerIcon.DETAILS_CLOSED : DebuggerIcon.DETAILS_OPEN, true, null,
                    () -> contextExpanded = !contextExpanded));
                return items;
            }, event -> false, () -> navigation.requestFocus("source-" + index));
        ScreenLayers.open(parent, menu);
    }

    private String contextProvenance() {
        if (state.selectedUnobservedStageIndex() >= 0)
            return tr("codon.ui.contexts.not_recorded");
        ExecutionFlowStage stage = state.displayedSourceStage();
        if (stage == null) return tr(state.displayedSources().isEmpty()
            ? "codon.ui.contexts.not_recorded" : "codon.ui.contexts.pause_inputs");
        // Match ExecutionFlowStage.displayContexts exactly, including its input fallback.
        boolean inputs = stage.terminal() || !stage.complete() || !stage.lineageComplete();
        return tr(inputs ? "codon.ui.contexts.inputs" : "codon.ui.contexts.outputs", stage.index() + 1);
    }

    /** One detail line; a before/after pair shares a scale so its values stay comparable. */
    private record DetailLine(String text, int color, float scale) { }

    private List<DetailLine> detailLines(PauseSource source, int width) {
        List<DetailLine> lines = new ArrayList<>();
        ExecutionFlowContext parent = state.selectedSourceDropped() ? null : state.selectedFlowParent();
        PauseSource before = parent == null ? null : parent.source();
        if (before != null && !Objects.equals(before.entity(), source.entity()))
            lines.add(new DetailLine("← " + name(before), MUTED, 1));
        lines.add(new DetailLine(shortDimension(source.dimension()), TEXT, 1));
        if (before != null && !before.dimension().equals(source.dimension()))
            lines.add(new DetailLine("← " + shortDimension(before.dimension()), MUTED, 1));
        pair(lines, SourceDetailsFormatting.position(source), SourceDetailsFormatting.previousPosition(before, source), width);
        String yaw = tr("codon.ui.rotation.yaw"), pitch = tr("codon.ui.rotation.pitch");
        pair(lines, SourceDetailsFormatting.rotation(source, yaw, pitch),
            SourceDetailsFormatting.previousRotation(before, source, yaw, pitch), width);
        return lines;
    }

    private void pair(List<DetailLine> lines, String current, String previous, int width) {
        int widest = Math.max(client.font.width(current), client.font.width(previous));
        float scale = Math.min(1.0f, Math.max(1, width) / (float) Math.max(1, widest));
        lines.add(new DetailLine(current, TEXT, scale));
        if (!previous.isEmpty()) lines.add(new DetailLine(previous, MUTED, scale));
    }

    /** The selected context's details, under its row; status icons share the first line. */
    private void renderContextDetails(GuiGraphicsExtractor graphics, Bounds area, List<DetailLine> lines) {
        PauseSource source = state.selectedSource();
        if (source == null) return;
        int y = area.y() + 4;
        int iconX = area.x() + area.width() - 18;
        if (!visibleSources.contains(state.selectedSourceIndex())) {
            sourceStatusIcon(graphics, iconX, y - 4, DebuggerIcon.OUTSIDE_VIEWPORT, AMBER,
                component(source.dimension().equals(dimension()) ? "codon.ui.offscreen" : "codon.ui.other_dimension"));
            iconX -= 18;
        }
        if (state.selectedSourceDropped() || state.selectedSourceCreated() || state.selectedSourceChanged()) {
            boolean dropped = state.selectedSourceDropped();
            boolean created = state.selectedSourceCreated();
            sourceStatusIcon(graphics, iconX, y - 4,
                dropped ? DebuggerIcon.SOURCE_EXCLUDED : created ? DebuggerIcon.SOURCE_CREATED : DebuggerIcon.SOURCE_CHANGED,
                dropped ? RED : created ? GREEN : PURPLE,
                component(dropped ? "codon.ui.flow_removed" : created ? "codon.ui.flow_created" : "codon.ui.flow_changed"));
            iconX -= 18;
        }
        for (int i = 0; i < lines.size() && y + 9 <= area.y() + area.height(); i++) {
            DetailLine line = lines.get(i);
            int width = (i == 0 ? iconX + 18 - area.x() : area.width()) - 8;
            if (line.scale() < 1) sourceTransformText(graphics, line.text(), area.x() + 4, y, line.scale(), line.color());
            else text(graphics, line.text(), area.x() + 4, y, width, line.color());
            y += 11;
        }
    }

    private void sourceTransformText(GuiGraphicsExtractor graphics, String value, int x, int y, float scale, int color) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y + (client.font.lineHeight * (1.0f - scale)) / 2.0f);
        graphics.pose().scale(scale, scale);
        graphics.text(client.font, value, 0, 0, DebuggerTheme.foreground(color), false);
        graphics.pose().popMatrix();
    }

    private void sourceStatusIcon(GuiGraphicsExtractor graphics, int x, int y, DebuggerIcon icon,
                                  int color, Component description) {
        icon.draw(graphics, x + 2, y + 2, DebuggerTheme.foreground(color));
        if (hoverX >= x && hoverX < x + 16 && hoverY >= y && hoverY < y + 16
            && HoverDelay.elapsed(List.of("overlay.status", x, y))) {
            graphics.setTooltipForNextFrame(client.font, description, hoverX, hoverY);
        }
    }

    private int sourceColor(int index, int fallback) {
        return state.isDisplayedSourceDropped(index) ? RED : state.isDisplayedSourceCreated(index) ? GREEN
            : state.isDisplayedSourceChanged(index) ? PURPLE : fallback;
    }

    private int worldSourceColor(int index, int fallback) {
        return state.isWorldSourceDropped(index) ? RED : state.isWorldSourceCreated(index) ? GREEN
            : state.isWorldSourceChanged(index) ? PURPLE : fallback;
    }

    private DebuggerButton colorWorldSourceButton(DebuggerButton button, int index) {
        if (state.isWorldSourceDropped(index)) return button.withStatusColor(RED, RED_SURFACE);
        if (state.isWorldSourceCreated(index)) return button.withStatusColor(GREEN, GREEN_SURFACE);
        if (state.isWorldSourceChanged(index)) return button.withStatusColor(PURPLE, PURPLE_SURFACE);
        return button;
    }

    private DebuggerButton colorSourceButton(DebuggerButton button, int index) {
        if (state.isDisplayedSourceDropped(index)) return button.withStatusColor(RED, RED_SURFACE);
        if (state.isDisplayedSourceCreated(index)) return button.withStatusColor(GREEN, GREEN_SURFACE);
        if (state.isDisplayedSourceChanged(index)) return button.withStatusColor(PURPLE, PURPLE_SURFACE)
            .withChangedDot(component("codon.ui.flow_changed"));
        return button;
    }

    private static Component joinLines(List<Component> lines) {
        var joined = Component.empty();
        for (int i = 0; i < lines.size(); i++) joined.append(i == 0 ? lines.get(i) : Component.literal("\n").append(lines.get(i)));
        return joined;
    }

    public boolean scroll(double x, double y, double scrollX, double amount) {
        if (watchPanel.scroll(x, y, amount) || breakpointPanel.scroll(x, y, amount)
            || callStackPanel.scroll(x, y, amount)) return true;
        if (commandPanel.scroll(x, y, scrollX, amount) || nbtPanel.scroll(x, y, amount)) return true;
        int delta = amount > 0 ? -1 : amount < 0 ? 1 : 0;
        if (delta == 0) return false;
        if (sourceScrollBounds.contains(x, y)) {
            sourceOffset = Math.clamp(sourceOffset + delta, 0, maxSourceOffset);
        } else return false;
        return true;
    }

    private DebuggerButton button(String id, Bounds bounds, Component label, boolean active, boolean selected,
                                  boolean leftAligned, boolean subdued, Runnable action) {
        DebuggerButton button = buttonCache.computeIfAbsent(id, ignored -> new DebuggerButton());
        button.configure(bounds.x(), bounds.y(), bounds.width(), bounds.height(), label, active,
            selected, leftAligned, subdued, action);
        if (navigationGroup == DebuggerNavigation.Group.TOOLBAR) {
            button.withOpaqueColors();
            if (!id.startsWith("control-")) button.withFlatChrome();
        }
        usedButtons.add(id);
        controls.add(button);
        navigation.bind(id, navigationGroup, button);
        return button;
    }

    static int revealRow(int row, int offset, int rows, int maximum) {
        if (row < offset) offset = row;
        else if (row >= offset + rows) offset = row - rows + 1;
        return Math.clamp(offset, 0, maximum);
    }

    private DebuggerButton iconButton(String id, Bounds bounds, Component label, DebuggerIcon icon,
                                     boolean active, Runnable action) {
        DebuggerButton button = button(id, bounds, label, active, false, false, false, action).withIcon(icon);
        return button;
    }

    private void text(GuiGraphicsExtractor graphics, String value, int x, int y, int width, int color) {
        text(graphics, value, x, y, width, color, false);
    }

    private void text(GuiGraphicsExtractor graphics, String value, int x, int y, int width, int color, boolean opaque) {
        if (width <= 0) return;
        graphics.enableScissor(x, y, x + width, y + client.font.lineHeight + 1);
        if (opaque) graphics.text(client.font, trimmed(value, width), x, y, color, false);
        else graphics.text(client.font, trimmed(value, width), x, y, DebuggerTheme.foreground(color), false);
        graphics.disableScissor();
        if (client.font.width(value) > width && hoverX >= x && hoverX < x + width
            && hoverY >= y && hoverY < y + client.font.lineHeight + 1 && HoverDelay.elapsed(List.of("overlay.text", x, y))) {
            graphics.setTooltipForNextFrame(client.font, Component.literal(value), hoverX, hoverY);
        }
    }

    private String trimmed(String value, int width) {
        if (client.font.width(value) <= width) return value;
        if (width < client.font.width("…")) return "";
        return client.font.plainSubstrByWidth(value, width - client.font.width("…")) + "…";
    }

    private void wrapped(GuiGraphicsExtractor graphics, Component value, Bounds bounds, int color) {
        wrapped(graphics, value, bounds, color, null);
    }

    /** Draws {@code slotIcon} over the {@link #ICON_SLOT} reserved in {@code value}, wherever it wraps. */
    private void wrapped(GuiGraphicsExtractor graphics, Component value, Bounds bounds, int color,
                         @Nullable DebuggerIcon slotIcon) {
        if (bounds.width() <= 0) return;
        int y = bounds.y();
        for (FormattedCharSequence line : client.font.split(value, bounds.width())) {
            if (y + client.font.lineHeight > bounds.y() + bounds.height()) break;
            graphics.text(client.font, line, bounds.x(), y, DebuggerTheme.foreground(color), false);
            int slotX = slotIcon == null ? -1 : slotOffset(line);
            if (slotX >= 0) slotIcon.draw(graphics, bounds.x() + slotX, y - 1, DebuggerTheme.foreground(color));
            y += 11;
        }
    }

    /** Pixel offset of the icon slot within {@code line}, or -1 when the line has none. */
    private int slotOffset(FormattedCharSequence line) {
        int[] offset = {0};
        boolean[] found = {false};
        line.accept((index, style, codePoint) -> {
            found[0] = codePoint == ICON_SLOT;
            if (!found[0]) offset[0] += client.font.width(FormattedCharSequence.codepoint(codePoint, style));
            return !found[0];
        });
        return found[0] ? offset[0] : -1;
    }

    private static void sectionDivider(GuiGraphicsExtractor graphics, Bounds area) {
        graphics.fill(area.x(), area.y(), area.x() + area.width(), area.y() + 1, DebuggerTheme.color(BORDER));
    }

    private static void panel(GuiGraphicsExtractor graphics, Bounds bounds) {
        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + bounds.width(), bounds.y() + bounds.height(), DebuggerTheme.color(PANEL));
        graphics.outline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), DebuggerTheme.color(BORDER));
    }

    private static void leader(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int color) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        if (steps < 1) return;
        // GUI extraction exposes rectangles, not arbitrary lines. One pixel per step is enough.
        for (int i = 0; i <= steps; i += 2) {
            int x = x1 + (x2 - x1) * i / steps;
            int y = y1 + (y2 - y1) * i / steps;
            graphics.fill(x, y, x + 1, y + 1, DebuggerTheme.color(color));
        }
    }

    private void scrollbar(GuiGraphicsExtractor graphics, String id, java.util.function.IntConsumer setter, int x, int y, int height,
                                  int offset, int maxOffset, int rows, int total) {
        if (height <= 0 || total <= rows || maxOffset <= 0) return;
        graphics.fill(x, y, x + 2, y + height, DebuggerTheme.color(BORDER));
        int thumb = Math.min(height, Math.max(6, height * rows / total));
        scrollbars.add(id, false, x, y, height, 2, thumb, offset, maxOffset, setter);
        int top = y + (height - thumb) * offset / maxOffset;
        graphics.fill(x, top, x + 2, top + thumb, DebuggerTheme.color(SCROLLBAR));
    }

    private String dimension() {
        return client.level == null ? "" : client.level.dimension().identifier().toString();
    }

    private static String shortDimension(String dimension) {
        return dimension.startsWith("minecraft:") ? dimension.substring(10) : dimension;
    }

    private static String sourceLabel(PauseSource source, int index, boolean dropped) {
        String prefix = dropped ? "× " : source.entity() == null ? "[" + (index + 1) + "] " : "#" + (index + 1) + " ";
        return prefix + name(source);
    }

    private static String sourceLabel(PauseSource source, int index, boolean dropped, boolean created) {
        return (dropped ? "" : created ? "+ " : "") + sourceLabel(source, index, dropped);
    }

    private static String name(PauseSource source) {
        return source.entity() == null ? tr("codon.ui.position_source") : source.entity().name();
    }

    private static MutableComponent component(String key) {
        return Component.translatable(key);
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }
}
