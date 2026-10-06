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
import works.nuty.codon.client.state.DebuggerPreferences.InspectorTab;
import works.nuty.codon.client.ui.layout.DebuggerLayout;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout;
import works.nuty.codon.client.ui.layout.WatchPanelLayout;
import works.nuty.codon.client.ui.layout.DebuggerHeaderLayout;
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

import static works.nuty.codon.client.ui.DebuggerTheme.*;
import static works.nuty.codon.client.state.DebuggerPreferences.*;

/** Shared HUD/screen presentation. Only the menu screen registers the rendered controls. */
public final class DebuggerOverlay {
    private enum AuxiliaryPanel { NONE, INSPECTOR, WATCHES }
    private static final Bounds EMPTY = new Bounds(0, 0, 0, 0);
    private static final int MAX_WORLD_LABELS = 20;
    private static final int SOURCE_LIST_MIN_HEIGHT = 62;
    private static final int SOURCE_COMPACT_MIN_HEIGHT = 40;
    private static final int SOURCE_LIST_MAX_HEIGHT = 109;
    private static final int SOURCE_ROWS_TOP = 43;
    private static final int SOURCE_DETAILS_VIEWPORT_HEIGHT = 102;
    private static final int NBT_HEADER_VIEWPORT_HEIGHT = 20;
    private static final int NBT_MIN_VIEWPORT_HEIGHT = 54;
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
    private Bounds watchSummaryBounds = EMPTY;
    private final WatchPanel watchPanel;
    private Bounds sourceScrollBounds = EMPTY;
    private Bounds viewTriggerBounds = EMPTY;
    private Bounds viewMenuBounds = EMPTY;
    private final List<DebuggerButton> viewMenuButtons = new ArrayList<>();
    private boolean viewMenuOpen;
    private boolean showInspector;
    private boolean showWatches;
    private boolean compactAuxiliary;
    private boolean narrowAuxiliary;
    private int lastAuxiliarySize = -1;
    private AuxiliaryPanel auxiliaryPanel = AuxiliaryPanel.NONE;
    private int hoverX = -1;
    private int hoverY = -1;

    public DebuggerOverlay(ClientDebuggerState state) {
        this.state = state;
        this.opacitySlider = new BackgroundOpacitySlider(state.preferences());
        this.nbtPanel = new NbtTreePanel(state);
        this.watchPanel = new WatchPanel(state);
        this.commandPanel = new CommandPanel(state, () -> { sourceOffset = 0; expandedGroup = List.of(); });
    }

    public works.nuty.codon.client.state.DebuggerPreferences preferences() { return state.preferences(); }

    void commitBackgroundOpacity() { opacitySlider.commitPreview(); }

    ScrollbarInput scrollbars() { return scrollbars; }
    PanelResizeInput panelResizing() { return panelResizing; }

    public DebuggerNavigation navigation() { return navigation; }
    public WatchPanel watchPanel() { return watchPanel; }
    void revealSelectedFlow(CodonScreen screen, String focusId) {
        preferences().setCommandVisible(true);
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
    boolean openFlowContextMenu(net.minecraft.client.gui.components.events.GuiEventListener focused) {
        boolean opened = commandPanel.openContextMenu(focused);
        if (opened) panelResizing.cancel();
        return opened;
    }

    boolean viewMenuOpen() { return viewMenuOpen; }
    boolean viewTriggerContains(double x, double y) { return viewTriggerBounds.contains(x, y); }
    boolean viewMenuContains(double x, double y) { return viewMenuBounds.contains(x, y); }
    void closeViewMenu() { viewMenuOpen = false; }
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
        watchSummaryBounds = EMPTY;
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
            Component text = Component.literal("CODON · " + statusText() + " ")
                .append(keybind(Component.literal("[").append(input.menuKey.getTranslatedKeyMessage()).append("]")));
            Bounds header = DebuggerLayout.create(graphics.guiWidth(), graphics.guiHeight(), false).header();
            Bounds badge = new Bounds(header.x(), header.y(),
                Math.min(graphics.guiWidth() - 2 * header.x(), font.width(text) + 14), header.height());
            graphics.fill(badge.x(), badge.y(), badge.x() + badge.width(), badge.y() + badge.height(), PANEL);
            graphics.outline(badge.x(), badge.y(), badge.width(), badge.height(), BORDER);
            graphics.enableScissor(header.x() + 7, header.y(),
                header.x() + Math.max(7, badge.width() - 7), header.y() + header.height());
            graphics.text(font, text, header.x() + 7, header.y() + 5, MUTED, false);
            graphics.disableScissor();
            buttonCache.clear();
            navigation.endFrame();
            scrollbars.endFrame();
            panelResizing.endFrame();
            return List.of();
        }

        int auxiliarySize = graphics.guiWidth() < 420 ? 2 : graphics.guiWidth() < 600 ? 1 : 0;
        if (auxiliarySize != lastAuxiliarySize) {
            auxiliaryPanel = auxiliarySize == 1 ? AuxiliaryPanel.INSPECTOR : AuxiliaryPanel.NONE;
            lastAuxiliarySize = auxiliarySize;
        }
        compactAuxiliary = auxiliarySize != 0;
        narrowAuxiliary = auxiliarySize == 2;
        showInspector = compactAuxiliary ? auxiliaryPanel == AuxiliaryPanel.INSPECTOR
            : state.preferences().inspectorVisible() != null ? state.preferences().inspectorVisible()
                : graphics.guiWidth() >= 420 && graphics.guiHeight() >= 220;
        showWatches = compactAuxiliary ? auxiliaryPanel == AuxiliaryPanel.WATCHES
            : state.preferences().watchesVisible();
        boolean reserveSide = narrowAuxiliary ? false : showInspector || compactAuxiliary && showWatches;
        int maximumInspectorWidth = DebuggerLayout.maximumInspectorWidth(graphics.guiWidth(), showWatches);
        int inspectorWidth = Math.min(maximumInspectorWidth,
            panelResizing.requestedWidth("inspector", preferences().inspectorWidth()));
        DebuggerLayout layout = DebuggerLayout.create(graphics.guiWidth(), graphics.guiHeight(), reserveSide,
            state.preferences().commandVisible()
                ? commandPanel.preferredHeight(graphics.guiWidth(), graphics.guiHeight(), snapshot) : 0,
            inspectorWidth);
        Bounds auxiliaryBounds = narrowAuxiliary
            ? new Bounds(layout.world().x(), layout.world().y(),
                Math.min(240, Math.max(0, layout.world().width() - 32)), layout.world().height())
            : layout.inspector();
        navigationGroup = DebuggerNavigation.Group.TOOLBAR;
        renderHeader(graphics, layout, input, snapshot);
        if (viewMenuOpen) renderViewMenu(graphics);
        if (viewMenuBounds.contains(mouseX, mouseY)) hoverX = hoverY = -1;
        navigationGroup = DebuggerNavigation.Group.WATCH;
        if (showWatches) {
            Bounds available = compactAuxiliary ? auxiliaryBounds
                : WatchPanelLayout.available(layout, graphics.guiWidth(),
                    panelResizing.requestedWidth("watch", preferences().watchWidth()));
            renderWatchSummary(graphics, available, hoverX, hoverY, interactive, input);
        }
        if (watchPanel.groupingMenuContains(mouseX, mouseY)) hoverX = hoverY = -1;
        navigationGroup = DebuggerNavigation.Group.WORLD;
        if (!narrowAuxiliary || auxiliaryPanel == AuxiliaryPanel.NONE)
            renderWorldLabels(graphics, layout.world(), snapshot);
        if (showInspector) renderInspector(graphics, auxiliaryBounds, snapshot, input);
        if (interactive && !compactAuxiliary) {
            if (showInspector) panelResizing.add("inspector", auxiliaryBounds, false,
                MIN_INSPECTOR_WIDTH, maximumInspectorWidth, preferences()::setInspectorWidth);
            if (showWatches) panelResizing.add("watch", watchPanel.bounds(), true,
                MIN_WATCH_WIDTH, WatchPanelLayout.maximumWidth(layout), preferences()::setWatchWidth);
        }
        if (state.preferences().commandVisible()) {
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
        }
        if (interactive && !viewMenuOpen && !watchPanel.groupingMenuOpen())
            panelResizing.paint(graphics, mouseX, mouseY);
        return List.copyOf(controls);
    }

    private String statusText() {
        return tr(DebuggerStatus.translationKey(state));
    }

    private void renderHeader(GuiGraphicsExtractor graphics, DebuggerLayout layout, InputManager input,
                              @Nullable PauseSnapshot snapshot) {
        String status = statusText();
        String prefix = "CODON · ";
        Component menuKey = keybind(Component.literal("[").append(input.menuKey.getTranslatedKeyMessage()).append("]"));
        int menuKeyWidth = client.font.width(menuKey);
        int menuKeyGap = client.font.width(" ");
        int rightControlsWidth = 52 + menuKeyWidth + menuKeyGap;
        int prefixWidth = client.font.width(prefix);
        Bounds toolbar = layout.controls();
        Bounds header = new Bounds(layout.header().x(), layout.header().y(),
            Math.min(layout.header().width(), Math.max(toolbar.width(), rightControlsWidth + prefixWidth + client.font.width(status))),
            layout.header().height());
        Bounds headerPanel = new Bounds(header.x(), header.y(), Math.max(header.width(), toolbar.width()),
            toolbar.y() + toolbar.height() - header.y());
        graphics.fill(headerPanel.x(), headerPanel.y(), headerPanel.x() + headerPanel.width(),
            headerPanel.y() + headerPanel.height(), PANEL);
        graphics.outline(headerPanel.x(), headerPanel.y(), headerPanel.width(), headerPanel.height(), BORDER);
        opacitySlider.position(header.x() + header.width() - 39, header.y() + 1, 34);
        controls.add(opacitySlider);
        navigation.bind("background-opacity", DebuggerNavigation.Group.TOOLBAR, opacitySlider);
        var headerText = DebuggerHeaderLayout.create(header, prefixWidth, client.font.width(status), menuKeyWidth, menuKeyGap);
        text(graphics, prefix, header.x() + 7, header.y() + 5, headerText.prefixWidth(), TEXT, true);
        text(graphics, status, headerText.statusX(), header.y() + 5, headerText.statusWidth(),
            state.isPaused() ? AMBER : MUTED, true);

        graphics.text(client.font, menuKey, headerText.menuKeyX(),
            header.y() + 5, MUTED, false);

        int gap = DebuggerLayout.ICON_BUTTON_GAP;
        int width = Math.min(DebuggerLayout.ICON_BUTTON_SIZE,
            Math.max(1, (toolbar.width() - 6 - 10 * gap - DebuggerLayout.ICON_GROUP_GAP) / 12));
        int x = toolbar.x() + 3;
        for (InputManager.Control action : InputManager.Control.values()) {
            DebuggerIcon icon = switch (action) {
                case RESUME -> DebuggerIcon.CONTINUE;
                case OVER -> DebuggerIcon.STEP_OVER;
                case INTO -> DebuggerIcon.STEP_INTO;
                case OUT -> DebuggerIcon.STEP_OUT;
            };
            DebuggerButton control = iconButton("control-" + action,
                new Bounds(x, toolbar.y() + 2, width, DebuggerLayout.ICON_BUTTON_SIZE),
                component(action.translationKey()).copy().append(" ").append(keybind(input.keyLabel(action))),
                icon, snapshot != null && state.isPaused()
                    && (action == InputManager.Control.RESUME || !state.watchReadsFailed())
                    && (!state.controlPending() || action == InputManager.Control.RESUME && state.controlAwaitingReads()),
                () -> input.control(action));
            if (action == InputManager.Control.RESUME) control.withStatusColor(AMBER, AMBER_SURFACE);
            else control.withFlatChrome();

            x += width + gap;
        }
        x += DebuggerLayout.ICON_GROUP_GAP - gap;
        graphics.fill(x - 4, toolbar.y() + 5, x - 3, toolbar.y() + toolbar.height() - 5, BORDER);
        viewTriggerBounds = new Bounds(x, toolbar.y() + 2, 42, DebuggerLayout.ICON_BUTTON_SIZE);
        button("view", viewTriggerBounds, component("codon.ui.view"), true, viewMenuOpen, false, false,
            () -> viewMenuOpen = !viewMenuOpen);
        int auxiliaryX = x + viewTriggerBounds.width() + gap;
        boolean keepFreecam = state.preferences().keepFreecam();
        button("keep-freecam", new Bounds(auxiliaryX, toolbar.y() + 2,
                width, DebuggerLayout.ICON_BUTTON_SIZE),
            component(keepFreecam ? "codon.ui.keep_freecam.on" : "codon.ui.keep_freecam.off")
                .copy().append(" ").append(keybind(input.keepFreecamKey.getTranslatedKeyMessage())),
            true, keepFreecam, false, false,
            input::toggleKeepFreecam)
            .withIcon(DebuggerIcon.FREECAM);
        iconButton("information", new Bounds(auxiliaryX + width + gap, toolbar.y() + 2, width, DebuggerLayout.ICON_BUTTON_SIZE),
            component("codon.ui.information"), DebuggerIcon.INFORMATION, true,
            () -> client.gui.setScreen(new DebuggerHelpScreen(new CodonScreen(input, this), input)));

        iconButton("source", new Bounds(auxiliaryX + 2 * (width + gap), toolbar.y() + 2, width, DebuggerLayout.ICON_BUTTON_SIZE),
            Component.translatable("codon.source.title"), DebuggerIcon.SOURCE_FILE, CodonClientMod.sources() != null,
            () -> {
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
        int breakpointX = auxiliaryX + 3 * (width + gap);
        int breakpointWidth = Math.max(width,
            Math.min(70, toolbar.x() + toolbar.width() - breakpointX - 3));
        long breakpointCount = state.breakpoints().definitions().stream().filter(BreakpointDefinition::enabled).count();
        button("breakpoints", new Bounds(breakpointX, toolbar.y() + 2, breakpointWidth,
                DebuggerLayout.ICON_BUTTON_SIZE),
            Component.translatable("codon.breakpoint.short_count", breakpointCount),
            true, false, false, false,
            () -> { if (client.gui.screen() != null) client.gui.setScreen(new BreakpointListScreen(client.gui.screen(), state)); })
            .withTextIcon(DebuggerIcon.BREAKPOINT_LIST)
            .setTooltip(Tooltip.create(Component.translatable("codon.breakpoint.toolbar",
                breakpointCount)));

    }

    private void renderViewMenu(GuiGraphicsExtractor graphics) {
        int menuWidth = Math.min(154, graphics.guiWidth() - 12);
        int menuHeight = 5 * 19 + 4;
        int x = Math.clamp(viewTriggerBounds.x(), 6, graphics.guiWidth() - menuWidth - 6);
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
        viewMenuItem(1, component("codon.ui.details"), showInspector, () -> {
            if (compactAuxiliary) auxiliaryPanel = showInspector ? AuxiliaryPanel.NONE : AuxiliaryPanel.INSPECTOR;
            else state.preferences().setInspectorVisible(!showInspector);
        });
        viewMenuItem(2, component("codon.watch.title"), showWatches, () -> {
            if (compactAuxiliary) auxiliaryPanel = showWatches ? AuxiliaryPanel.NONE : AuxiliaryPanel.WATCHES;
            else state.preferences().setWatchesVisible(!showWatches);
        });
        boolean command = state.preferences().commandVisible();
        viewMenuItem(3, component("codon.ui.command"), command,
            () -> state.preferences().setCommandVisible(!command));
        viewMenuItem(4, component("codon.ui.scale.title"), false,
            () -> { if (client.gui.screen() != null) client.gui.setScreen(new UiScaleScreen(client.gui.screen(), state.preferences())); });
    }

    private void viewMenuItem(int row, Component label, boolean selected, Runnable action) {
        Bounds bounds = new Bounds(viewMenuBounds.x() + 2, viewMenuBounds.y() + 2 + row * 19,
            viewMenuBounds.width() - 4, 18);
        DebuggerButton button = button("view-option-" + row, bounds,
            Component.literal(selected ? "● " : "  ").append(label), true, selected, true, false, () -> {
                action.run();
                viewMenuOpen = false;
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
            if (!labelArea.contains(x, y) || watchSummaryBounds.contains(x, y)) continue;
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
            watchSummaryBounds.width() > 0 ? List.of(watchSummaryBounds) : List.of(), MAX_WORLD_LABELS);
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
                if (compactAuxiliary) auxiliaryPanel = AuxiliaryPanel.INSPECTOR;
                else state.preferences().setInspectorVisible(true);
                state.preferences().setInspectorTab(InspectorTab.SOURCES);
            }), statusIndex);
            // A group names its representative; its status remains available even in a mixed group.
            if (state.isWorldSourceChanged(index)) sourceButton.withChangedDot(component("codon.ui.flow_changed"));
            sourceButton.setTooltip(Tooltip.create(worldSourceTooltip(title, index)));
        }

    }

    /** Saved pins first, followed by every change captured at this stop. */
    private void renderWatchSummary(GuiGraphicsExtractor graphics, Bounds available,
                                    int mouseX, int mouseY, boolean interactive, InputManager input) {
        controls.addAll(watchPanel.render(graphics, available, mouseX, mouseY, interactive, input, this, navigation, scrollbars));
        watchSummaryBounds = watchPanel.bounds();
    }

    private void renderInspector(GuiGraphicsExtractor graphics, Bounds area, @Nullable PauseSnapshot snapshot, InputManager input) {
        panel(graphics, area);
        if (area.height() < 40) return;
        if (snapshot == null) {
            // The icon matches the toolbar's Source button so the control is easy to find.
            Component sourceButton = Component.empty()
                .append(Component.literal(Character.toString(ICON_SLOT)).withStyle(style -> style.withFont(ICON_SLOT_FONT)))
                .append(component("codon.source.title"));
            wrapped(graphics, Component.translatable("codon.ui.idle_hint",
                keybind(input.breakpointKey.getTranslatedKeyMessage()), sourceButton,
                keybind(input.menuKey.getTranslatedKeyMessage())), new Bounds(area.x() + 8, area.y() + 9,
                area.width() - 16, area.height() - 18), MUTED, DebuggerIcon.SOURCE_FILE);
            return;
        }
        renderSourcesWithDetails(graphics, area, snapshot);
    }

    private void renderSourcesWithDetails(GuiGraphicsExtractor graphics, Bounds body,
                                          PauseSnapshot snapshot) {
        sectionDivider(graphics, body);
        renderSourcesWithDetails(graphics, body, snapshot, 0);
    }

    private void renderSourcesWithDetails(GuiGraphicsExtractor graphics, Bounds body,
                                          PauseSnapshot snapshot, int headingInset) {
        // Inspector regions are viewport allocations. Tree loading and expansion must never move controls.
        boolean hasNbt = nbtPanel.hasSelectedSource();
        int listHeight = Math.min(body.height(),
            Math.max(SOURCE_LIST_MIN_HEIGHT, Math.min(SOURCE_LIST_MAX_HEIGHT, body.height() / 3)));
        // The compact caption and one source row need 40 pixels. Reserve the rest
        // for actual NBT rows when the taller Flow detail band leaves a short inspector.
        if (hasNbt) listHeight = Math.min(listHeight,
            Math.max(SOURCE_COMPACT_MIN_HEIGHT, body.height() - NBT_MIN_VIEWPORT_HEIGHT));
        int remainingHeight = body.height() - listHeight;
        if (hasNbt && remainingHeight < NBT_HEADER_VIEWPORT_HEIGHT) {
            listHeight = body.height();
            remainingHeight = 0;
        }
        int nbtMinimum = hasNbt ? NBT_MIN_VIEWPORT_HEIGHT : 0;
        int detailHeight = Math.min(SOURCE_DETAILS_VIEWPORT_HEIGHT,
            Math.max(0, remainingHeight - nbtMinimum));
        if (detailHeight < 22) detailHeight = 0;
        int nbtHeight = Math.max(0, remainingHeight - detailHeight);
        navigationGroup = DebuggerNavigation.Group.SOURCES;
        renderSources(graphics, new Bounds(body.x(), body.y(), body.width(), listHeight), snapshot, headingInset);
        navigationGroup = DebuggerNavigation.Group.SOURCE_DETAILS;
        if (detailHeight > 0) renderSourceDetails(graphics,
            new Bounds(body.x(), body.y() + listHeight, body.width(), detailHeight), 0);
        navigationGroup = DebuggerNavigation.Group.NBT;
        Bounds nbtBounds = new Bounds(body.x() + 3, body.y() + listHeight + detailHeight, body.width() - 6, nbtHeight);
        if (nbtHeight > 0 && !hasNbt && state.selectedPauseSourceIndex() < 0
            && state.selectedSource() != null && state.selectedSource().entity() != null) {
            wrapped(graphics, component("codon.nbt.history_unavailable"),
                new Bounds(nbtBounds.x() + 4, nbtBounds.y() + 5, nbtBounds.width() - 8, nbtBounds.height() - 5), MUTED);
        }
        if (nbtHeight > 0) nbtPanel.render(graphics, nbtBounds,
            hoverX, hoverY, navigation, scrollbars,
            (id, bounds, label, active, selected, action) -> button(id, bounds, label, active, selected, true, false, action)
                .withFlatChrome());
    }

    private void renderSources(GuiGraphicsExtractor graphics, Bounds area, PauseSnapshot snapshot, int headingInset) {
        boolean compactCaption = area.height() < SOURCE_ROWS_TOP + 19;
        int rowTop = compactCaption ? 21 : SOURCE_ROWS_TOP;
        String heading = expandedGroup.isEmpty() ? tr("codon.ui.contexts")
            : Component.translatable("codon.ui.group", expandedGroup.size()).getString();
        if (compactCaption) heading = contextProvenance();
        text(graphics, heading, area.x() + 7 + headingInset, area.y() + 6, area.width() - 42 - headingInset, TEXT);
        if (!compactCaption) {
            text(graphics, contextSelection(), area.x() + 7, area.y() + 18, area.width() - 14,
                state.isViewingCurrentCommand() ? AMBER : TEAL);
            text(graphics, contextProvenance(), area.x() + 7, area.y() + 29, area.width() - 14, MUTED);
        }
        if (!expandedGroup.isEmpty()) {
            navigation.add("all-sources", DebuggerNavigation.Group.SOURCES, -1, 0, () -> { });
            button("all-sources", new Bounds(area.x() + area.width() - 39, area.y() + 2, 35, 15),
                component("codon.ui.all"), true, false, false, false, () -> { expandedGroup = List.of(); sourceOffset = 0; });
        }
        List<Integer> indices = expandedGroup;
        if (indices.isEmpty()) {
            List<Integer> all = new ArrayList<>(state.displayedSources().size());
            for (int i = 0; i < state.displayedSources().size(); i++) all.add(i);
            indices = all;
        }
        int rows = Math.max(0, (area.height() - rowTop) / 19);
        maxSourceOffset = Math.max(0, indices.size() - Math.max(1, rows));
        int selectedSource = state.selectedSourceIndex();
        if (rows > 0 && (rows != lastSourceRows || selectedSource != lastSelectedSource)) {
            int selectedRow = indices.indexOf(selectedSource);
            // Keep the inspected entity identifiable after a resize or an external selection.
            // Clicking an already visible row and ordinary wheel scrolling keep their positions.
            if (selectedRow >= 0 && selectedRow < sourceOffset) sourceOffset = selectedRow;
            else if (selectedRow >= sourceOffset + rows) sourceOffset = selectedRow - rows + 1;
        }
        lastSourceRows = rows;
        lastSelectedSource = selectedSource;
        sourceOffset = Math.max(0, Math.min(sourceOffset, maxSourceOffset));
        sourceScrollBounds = area;
        if (rows > 0) {
            for (int row = 0; row < indices.size(); row++) {
                int logicalRow = row;
                navigation.add("source-" + indices.get(row), DebuggerNavigation.Group.SOURCES, row, 0,
                    () -> sourceOffset = revealRow(logicalRow, sourceOffset, rows, maxSourceOffset));
            }
        }
        navigation.revealFocus(DebuggerNavigation.Group.SOURCES);
        if (indices.isEmpty() && area.height() >= rowTop + 12)
            text(graphics, tr("codon.ui.no_sources"), area.x() + 7, area.y() + rowTop + 3, area.width() - 14, MUTED);
        for (int row = 0; row < rows && sourceOffset + row < indices.size(); row++) {
            int index = indices.get(sourceOffset + row);
            PauseSource source = state.displayedSources().get(index);
            String name = sourceLabel(source, index, state.isDisplayedSourceDropped(index),
                state.isDisplayedSourceCreated(index));
            Component title = Component.literal(name);
            Component tooltip = sourceTooltip(title, index);
            if (!source.dimension().equals(dimension())) tooltip = tooltip.copy().append("\n" + shortDimension(source.dimension()));
            int labelWidth = area.width() - 13;
            colorSourceButton(button("source-" + index, new Bounds(area.x() + 5, area.y() + rowTop + row * 19, labelWidth, 17),
                title, true, index == state.selectedSourceIndex(), true, false, () -> {
                    if (state.snapshot() == snapshot) {
                        state.selectSource(index);
                        state.preferences().setInspectorTab(InspectorTab.SOURCES);
                    }
                }).withFlatChrome(), index).setTooltip(Tooltip.create(tooltip));
        }
        if (rows > 0 && indices.size() > rows) {
            if (area.height() - (rowTop + rows * 19) >= 9) {
                text(graphics, (sourceOffset + 1) + "–" + Math.min(indices.size(), sourceOffset + rows) + " / " + indices.size(),
                    area.x() + 7, area.y() + area.height() - 8, area.width() - 14, MUTED);
            }
            scrollbar(graphics, "sources", value -> sourceOffset = value, area.x() + area.width() - 5, area.y() + rowTop, Math.max(1, rows * 19 - 2),
                sourceOffset, maxSourceOffset, rows, indices.size());
        }
    }

    private String contextSelection() {
        if (state.selectedUnobservedStageIndex() >= 0)
            return tr("codon.ui.contexts.unobserved", state.selectedUnobservedStageIndex() + 1);
        ExecutionFlowStage stage = state.selectedExecutionFlowStage();
        if (stage == null) return tr(state.displayedSources().isEmpty()
            ? "codon.ui.contexts.no_stage" : "codon.ui.contexts.pause_packet");
        return tr(state.isViewingCurrentCommand() ? "codon.ui.contexts.current" : "codon.ui.contexts.history", stage.index() + 1);
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

    private void renderSourceDetails(GuiGraphicsExtractor graphics, Bounds area, int headingInset) {
        PauseSource source = state.selectedSource();
        if (source == null) return;
        int y = area.y() + 6;
        int accent = sourceColor(state.selectedSourceIndex(), TEAL);
        int iconX = area.x() + area.width() - 23;
        var freecam = CodonClientMod.freecam();
        String status = freecam == null ? "unavailable" : freecam.selectedAnchorStatus(client);
        iconButton("move-to-source", new Bounds(iconX, y - 3, 16, 16),
            component("codon.ui.move_to_source"), DebuggerIcon.FREECAM, status.equals("ready"),
            () -> { if (freecam != null) freecam.moveToSelectedAnchor(client); })
            .withoutChrome()
            .setTooltip(Tooltip.create(component("codon.ui.move_to_source." + status)));
        iconX -= 18;
        boolean copied = source.entity() != null && source.entity().uuid().toString().equals(copiedUuid)
            && System.nanoTime() < copiedUuidUntil;
        if (source.entity() != null) {
            String uuid = source.entity().uuid().toString();
            iconButton("copy-uuid", new Bounds(iconX, y - 3, 16, 16),
                Component.literal("UUID: " + uuid + "\n" + tr(copied ? "codon.ui.copied" : "codon.ui.copy")),
                copied ? DebuggerIcon.CONFIRM : DebuggerIcon.COPY_UUID, true, () -> {
                    client.keyboardHandler.setClipboard(uuid);
                    copiedUuid = uuid;
                    copiedUuidUntil = System.nanoTime() + 4_000_000_000L;
                }).withoutChrome();
            iconX -= 18;
        }
        if (!visibleSources.contains(state.selectedSourceIndex())) {
            sourceStatusIcon(graphics, iconX, y - 3, DebuggerIcon.OUTSIDE_VIEWPORT, AMBER,
                component(source.dimension().equals(dimension()) ? "codon.ui.offscreen" : "codon.ui.other_dimension"));
            iconX -= 18;
        }
        if (state.selectedSourceDropped() || state.selectedSourceCreated() || state.selectedSourceChanged()) {
            boolean dropped = state.selectedSourceDropped();
            boolean created = state.selectedSourceCreated();
            sourceStatusIcon(graphics, iconX, y - 3,
                dropped ? DebuggerIcon.SOURCE_EXCLUDED : created ? DebuggerIcon.SOURCE_CREATED : DebuggerIcon.SOURCE_CHANGED,
                dropped ? RED : created ? GREEN : PURPLE,
                component(dropped ? "codon.ui.flow_removed" : created ? "codon.ui.flow_created" : "codon.ui.flow_changed"));
            iconX -= 18;
        }
        text(graphics, copied ? tr("codon.ui.copied")
            : sourceLabel(source, state.selectedSourceIndex(), state.selectedSourceDropped(),
                state.selectedSourceCreated()),
            area.x() + 7 + headingInset, y, iconX + 16 - area.x() - 9 - headingInset, accent);
        y += 16;
        ExecutionFlowContext parent = state.selectedSourceDropped() ? null : state.selectedFlowParent();
        PauseSource before = parent == null ? null : parent.source();
        if (before != null && !Objects.equals(before.entity(), source.entity())) {
            if (y + 9 > area.y() + area.height()) return;
            text(graphics, "← " + name(before), area.x() + 7, y, area.width() - 14, MUTED);
            y += 11;
        }
        if (y + 9 > area.y() + area.height()) return;
        text(graphics, shortDimension(source.dimension()), area.x() + 7, y, area.width() - 14, TEXT);
        y += 11;
        if (before != null && !before.dimension().equals(source.dimension())) {
            if (y + 9 > area.y() + area.height()) return;
            text(graphics, "← " + shortDimension(before.dimension()), area.x() + 7, y, area.width() - 14, MUTED);
            y += 11;
        }
        y = sourceValueRows(graphics, area, y, SourceDetailsFormatting.position(source),
            SourceDetailsFormatting.previousPosition(before, source));
        sourceValueRows(graphics, area, y, SourceDetailsFormatting.rotation(source),
            SourceDetailsFormatting.previousRotation(before, source));
    }

    private int sourceValueRows(GuiGraphicsExtractor graphics, Bounds area, int y,
                                String current, String previous) {
        // Keep each pair at the same scale so the before/after values remain comparable.
        int widest = Math.max(client.font.width(current), client.font.width(previous));
        float scale = Math.min(1.0f, Math.max(1, area.width() - 14) / (float) Math.max(1, widest));
        if (y + 9 > area.y() + area.height()) return y;
        sourceTransformText(graphics, current, area, y, scale, TEXT);
        y += 11;
        if (!previous.isEmpty() && y + 9 <= area.y() + area.height()) {
            sourceTransformText(graphics, previous, area, y, scale, MUTED);
            y += 11;
        }
        return y;
    }

    private void sourceTransformText(GuiGraphicsExtractor graphics, String value, Bounds area,
                                     int y, float scale, int color) {
        int x = area.x() + 7;
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

    private Component worldSourceTooltip(Component title, int index) {
        return state.isWorldSourceChanged(index) ? title.copy().append("\n").append(component("codon.ui.flow_changed")) : title;
    }

    private DebuggerButton colorSourceButton(DebuggerButton button, int index) {
        if (state.isDisplayedSourceDropped(index)) return button.withStatusColor(RED, RED_SURFACE);
        if (state.isDisplayedSourceCreated(index)) return button.withStatusColor(GREEN, GREEN_SURFACE);
        if (state.isDisplayedSourceChanged(index)) return button.withStatusColor(PURPLE, PURPLE_SURFACE)
            .withChangedDot(component("codon.ui.flow_changed"));
        return button;
    }

    private Component sourceTooltip(Component title, int index) {
        return state.isDisplayedSourceChanged(index) ? title.copy().append("\n").append(component("codon.ui.flow_changed")) : title;
    }

    public boolean scroll(double x, double y, double scrollX, double amount) {
        if (watchPanel.scroll(x, y, amount)) return true;
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

    private static String location(SourceLocation location) {
        return switch (location) {
            case SourceLocation.Function function -> function.location().function() + ":" + function.location().line();
            case SourceLocation.Block block -> tr("codon.ui.command_block") + " " + block.block().x() + ", " + block.block().y() + ", " + block.block().z();
            case SourceLocation.Player player -> player.name();
        };
    }

    private static MutableComponent component(String key) {
        return Component.translatable(key);
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }
}
