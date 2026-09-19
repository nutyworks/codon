package works.nuty.codon.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.DebuggerPreferences.InspectorTab;
import works.nuty.codon.client.ui.layout.DebuggerLayout;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Anchor;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import works.nuty.codon.core.model.ExecutionFlowContext;
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

/** Shared HUD/screen presentation. Only the menu screen registers the rendered controls. */
public final class DebuggerOverlay {
    private static final Bounds EMPTY = new Bounds(0, 0, 0, 0);
    private final ClientDebuggerState state;
    private final NbtTreePanel nbtPanel;
    private final CommandPanel commandPanel;
    private final Minecraft client = Minecraft.getInstance();
    private final Map<String, DebuggerButton> buttonCache = new HashMap<>();
    private final List<DebuggerButton> controls = new ArrayList<>();
    private final Set<String> usedButtons = new HashSet<>();
    private final Set<Integer> visibleSources = new HashSet<>();
    private @Nullable PauseSnapshot lastSnapshot;
    private List<Integer> expandedGroup = List.of();
    private int sourceOffset;
    private int maxSourceOffset;
    private Bounds watchSummaryBounds = EMPTY;
    private int watchSummaryOffset;
    private int maxWatchSummaryOffset;
    private Bounds sourceScrollBounds = EMPTY;
    private Bounds watchSummaryScrollBounds = EMPTY;
    private boolean showInspector;
    private int hoverX = -1;
    private int hoverY = -1;

    public DebuggerOverlay(ClientDebuggerState state) {
        this.state = state;
        this.nbtPanel = new NbtTreePanel(state);
        this.commandPanel = new CommandPanel(state, () -> { sourceOffset = 0; expandedGroup = List.of(); });
    }

    public List<DebuggerButton> render(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                       float partialTick, boolean interactive, InputManager input) {
        controls.clear();
        hoverX = interactive ? mouseX : -1;
        hoverY = interactive ? mouseY : -1;
        usedButtons.clear();
        sourceScrollBounds = EMPTY;
        commandPanel.clearBounds();
        watchSummaryScrollBounds = EMPTY;
        nbtPanel.clearBounds();
        if (client.level == null || client.player == null) {
            buttonCache.clear();
            return List.of();
        }
        // The live debugger panels must not present retained history after resume.
        PauseSnapshot snapshot = state.snapshot();
        if (snapshot != lastSnapshot) {
            expandedGroup = List.of();
            sourceOffset = Math.max(0, state.selectedSourceIndex());
            lastSnapshot = snapshot;
        }
        Font font = client.font;
        if ((!state.isPaused() || snapshot == null) && !interactive) {
            if (!state.blockBreakpoints().isEmpty()) {
                Component text = Component.literal("CODON · " + statusText() + "  ")
                    .append(keybind(Component.literal("[").append(input.menuKey.getTranslatedKeyMessage()).append("]")));
                Bounds header = DebuggerLayout.create(graphics.guiWidth(), graphics.guiHeight(), false).header();
                Bounds badge = new Bounds(header.x(), header.y(),
                    Math.min(graphics.guiWidth() - 2 * header.x(), font.width(text) + 14), header.height());
                panel(graphics, badge);
                graphics.enableScissor(header.x() + 7, header.y(),
                    header.x() + Math.max(7, badge.width() - 7), header.y() + header.height());
                graphics.text(font, text, header.x() + 7, header.y() + 5, MUTED, false);
                graphics.disableScissor();
            }
            buttonCache.clear();
            return List.of();
        }

        showInspector = state.preferences().inspectorVisible() != null ? state.preferences().inspectorVisible()
            : graphics.guiWidth() >= 420 && graphics.guiHeight() >= 220;
        DebuggerLayout layout = DebuggerLayout.create(graphics.guiWidth(), graphics.guiHeight(), showInspector,
            commandPanel.preferredHeight(graphics.guiWidth(), graphics.guiHeight(), snapshot));
        renderHeader(graphics, layout, input, snapshot);
        renderWatchSummary(graphics, layout, mouseX, mouseY, interactive, input);
        renderWorldLabels(graphics, layout.world(), snapshot);
        if (showInspector) renderInspector(graphics, layout.inspector(), snapshot);
        controls.addAll(commandPanel.render(graphics, layout.command(), snapshot, input, this));

        buttonCache.keySet().retainAll(usedButtons);
        for (DebuggerButton button : controls) {
            if (!interactive) button.setFocused(false);
            button.extractRenderState(graphics, interactive ? mouseX : -1, interactive ? mouseY : -1, partialTick);
        }
        return List.copyOf(controls);
    }

    private String statusText() {
        return tr(DebuggerStatus.translationKey(state));
    }

    private void renderHeader(GuiGraphicsExtractor graphics, DebuggerLayout layout, InputManager input,
                              @Nullable PauseSnapshot snapshot) {
        String status = statusText();
        String prefix = "CODON · ";
        int prefixWidth = client.font.width(prefix);
        Bounds toolbar = layout.controls();
        Bounds header = new Bounds(layout.header().x(), layout.header().y(),
            Math.min(layout.header().width(), Math.max(toolbar.width(), 14 + prefixWidth + client.font.width(status))),
            layout.header().height());
        Bounds headerPanel = new Bounds(header.x(), header.y(), Math.max(header.width(), toolbar.width()),
            toolbar.y() + toolbar.height() - header.y());
        panel(graphics, headerPanel);
        graphics.fill(header.x(), header.y(), header.x() + 2, header.y() + headerPanel.height(), TEAL);
        text(graphics, prefix, header.x() + 7, header.y() + 5, Math.max(0, header.width() - 14), TEXT);
        text(graphics, status, header.x() + 7 + prefixWidth, header.y() + 5, Math.max(0, header.width() - 14 - prefixWidth),
            state.isPaused() ? AMBER : MUTED);

        int gap = DebuggerLayout.ICON_BUTTON_GAP;
        int width = Math.min(DebuggerLayout.ICON_BUTTON_SIZE,
            Math.max(1, (toolbar.width() - 6 - 5 * gap - DebuggerLayout.ICON_GROUP_GAP) / 7));
        int x = toolbar.x() + 3;
        for (InputManager.Control action : InputManager.Control.values()) {
            DebuggerIcon icon = switch (action) {
                case RESUME -> DebuggerIcon.CONTINUE;
                case OVER -> DebuggerIcon.STEP_OVER;
                case INTO -> DebuggerIcon.STEP_INTO;
                case OUT -> DebuggerIcon.STEP_OUT;
            };
            iconButton("control-" + action,
                new Bounds(x, toolbar.y() + 2, width, DebuggerLayout.ICON_BUTTON_SIZE),
                component(action.translationKey()).copy().append(" ").append(keybind(input.keyLabel(action))),
                icon, snapshot != null && state.isPaused() && !state.controlPending(),
                () -> input.control(action));

            x += width + gap;
        }
        x += DebuggerLayout.ICON_GROUP_GAP - gap;
        graphics.fill(x - 4, toolbar.y() + 5, x - 3, toolbar.y() + toolbar.height() - 5, BORDER);
        Component mode = Component.literal("Gizmo: ")
            .append(component("codon.ui.mode." + state.gizmoMode().name().toLowerCase(Locale.ROOT)));
        DebuggerIcon modeIcon = switch (state.gizmoMode()) {
            case GROUPED -> DebuggerIcon.GIZMO_GROUPED;
            case LABELS -> DebuggerIcon.GIZMO_LABELS;
        };
        iconButton("mode", new Bounds(x, toolbar.y() + 2, width, DebuggerLayout.ICON_BUTTON_SIZE),
            mode, modeIcon, true, () -> {
                state.setGizmoMode(state.gizmoMode().next());
                expandedGroup = List.of();
                sourceOffset = 0;
            });
        iconButton("inspector", new Bounds(x + width + gap, toolbar.y() + 2, width, DebuggerLayout.ICON_BUTTON_SIZE),
            component("codon.ui.details"), showInspector ? DebuggerIcon.DETAILS_OPEN : DebuggerIcon.DETAILS_CLOSED,
            true, () -> state.preferences().setInspectorVisible(!showInspector));
        iconButton("information", new Bounds(x + 2 * (width + gap), toolbar.y() + 2, width, DebuggerLayout.ICON_BUTTON_SIZE),
            component("codon.ui.information"), DebuggerIcon.INFORMATION, true,
            () -> client.gui.setScreen(new DebuggerHelpScreen(new CodonScreen(input, this), input)));
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
            int labelWidth = Math.min(150,
                client.font.width(sourceLabel(source, index, state.isWorldSourceDropped(index))) + 14);
            anchors.add(new Anchor(index, x, y, labelWidth));
        }
        List<GizmoLabelLayout.Label> labels = GizmoLabelLayout.layout(anchors, labelArea,
            selectedIndex, state.gizmoMode() != ClientDebuggerState.GizmoMode.LABELS,
            watchSummaryBounds.width() > 0 ? List.of(watchSummaryBounds) : List.of());
        for (GizmoLabelLayout.Label label : labels) {
            List<Integer> indices = label.sourceIndices();
            boolean selected = indices.contains(selectedIndex);
            boolean group = indices.size() > 1;
            int index = indices.getFirst();
            // A mixed, unselected group has no single status; a selected group names its selected member.
            int statusIndex = group && !selected && indices.stream()
                .anyMatch(member -> worldSourceColor(member, TEXT) != worldSourceColor(index, TEXT)) ? -1 : index;
            Bounds bounds = label.bounds();
            String sourceTitle = sourceLabel(displayedSources.get(index), index, state.isWorldSourceDropped(index));
            Component title;
            if (group && selected) {
                String count = "  +" + (indices.size() - 1);
                title = Component.literal(trimmed(sourceTitle, Math.max(0, bounds.width() - 10 - client.font.width(count))) + count);
            } else {
                title = group ? Component.translatable("codon.ui.group", indices.size()) : Component.literal(sourceTitle);
            }
            leader(graphics, (int) label.anchorX(), (int) label.anchorY(),
                bounds.x() + bounds.width() / 2, bounds.y() + bounds.height(), worldSourceColor(statusIndex, selected ? TEAL : MUTED));
            colorWorldSourceButton(button("label-" + index, bounds, title, true, selected, false, false, () -> {
                if (state.snapshot() != snapshot) return;
                state.selectWorldSource(index);
                if (group) {
                    expandedGroup = List.copyOf(indices);
                    sourceOffset = 0;
                } else {
                    expandedGroup = List.of();
                    sourceOffset = index;
                }
                state.preferences().setInspectorVisible(true);
                state.preferences().setInspectorTab(InspectorTab.SOURCES);
            }), statusIndex).setTooltip(Tooltip.create(group ? title : worldSourceTooltip(title, index)));
        }

    }

    /** A passive, compact reminder keeps pinned values visible without taking over the inspector. */
    private void renderWatchSummary(GuiGraphicsExtractor graphics, DebuggerLayout layout,
                                    int mouseX, int mouseY, boolean interactive, InputManager input) {
        watchSummaryBounds = EMPTY;
        var entries = state.watches().entries();
        Bounds world = layout.world();
        if (world.width() < 60 || world.height() < 40) return;
        int availableHeight = Math.max(0, world.height() - 24);
        int rows = entries.isEmpty() || availableHeight < 32 ? 0
            : Math.max(1, Math.min(entries.size(), (availableHeight - 20) / 12));
        int panelHeight = 20 + rows * 12;
        maxWatchSummaryOffset = Math.max(0, entries.size() - rows);
        watchSummaryOffset = Math.clamp(watchSummaryOffset, 0, maxWatchSummaryOffset);
        int width = Math.min(270, Math.max(1, world.width() - 8));
        Bounds panelBounds = new Bounds(world.x() + Math.max(0, world.width() - width - 4), world.y() + 4,
            width, panelHeight);
        watchSummaryBounds = panelBounds;
        panel(graphics, panelBounds);
        String title = tr("codon.watch.title");
        text(graphics, title, panelBounds.x() + 5, panelBounds.y() + 5, panelBounds.width() - 45, TEAL);
        int addX = panelBounds.x() + Math.min(client.font.width(title) + 10, panelBounds.width() - 40);
        button("watch-add", new Bounds(addX, panelBounds.y() + 2, 16, 15), Component.literal("+"),
            true, false, false, false, () -> client.gui.setScreen(new WatchScreen(input, state, this)));
        watchSummaryScrollBounds = rows == 0 ? EMPTY : new Bounds(panelBounds.x() + 3, panelBounds.y() + 19,
            panelBounds.width() - 6, rows * 12);
        for (int row = 0; row < rows; row++) {
            var entry = entries.get(watchSummaryOffset + row);
            int color = entry.displayedChange().isValueChange() ? AMBER : TEXT;
            int rowY = panelBounds.y() + 19 + row * 12;
            WatchRowRenderer.render(graphics, client.font, entry, state.isPaused(), panelBounds.x() + 5,
                rowY, panelBounds.width() - 10, color, color);
            if (interactive && mouseX >= panelBounds.x() + 5 && mouseX < panelBounds.x() + panelBounds.width() - 5
                && mouseY >= rowY && mouseY < rowY + 12) {
                graphics.setComponentTooltipForNextFrame(Minecraft.getInstance().font,
                    WatchFormatting.tooltip(entry, state.isPaused()), mouseX, mouseY);
            }
        }
        if (rows > 0 && maxWatchSummaryOffset > 0) scrollbar(graphics,
            panelBounds.x() + panelBounds.width() - 4, panelBounds.y() + 19, rows * 12,
            watchSummaryOffset, maxWatchSummaryOffset, rows, entries.size());
    }

    private void renderInspector(GuiGraphicsExtractor graphics, Bounds area, @Nullable PauseSnapshot snapshot) {
        panel(graphics, area);
        if (area.height() < 40) return;
        if (snapshot == null) {
            wrapped(graphics, component("codon.ui.no_snapshot"), new Bounds(area.x() + 8, area.y() + 9,
                area.width() - 16, area.height() - 18), MUTED);
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
        // Keep source selection accessible while its details and NBT share the inspector.
        int sourceCount = expandedGroup.isEmpty() ? state.displayedSources().size() : expandedGroup.size();
        int preferredListHeight = 30 + Math.clamp(sourceCount, 1, 3) * 19;
        int nbtPreferred = nbtPanel.preferredHeight(Math.min(220, Math.max(0, body.height() - 49 - 22)));
        int nbtFloor = nbtPreferred > 18 ? 54 : nbtPreferred;
        int nbtHeight = Math.min(nbtPreferred, Math.max(nbtFloor,
            body.height() - preferredListHeight - sourceDetailsHeight()));
        int detailHeight = Math.min(sourceDetailsHeight(), Math.max(0, body.height() - 49 - nbtHeight));
        if (detailHeight < 22) detailHeight = 0;
        int listHeight = body.height() - detailHeight - nbtHeight;
        renderSources(graphics, new Bounds(body.x(), body.y(), body.width(), listHeight), snapshot, headingInset);
        if (detailHeight > 0) renderSourceDetails(graphics,
            new Bounds(body.x(), body.y() + listHeight, body.width(), detailHeight), 0);
        if (nbtHeight > 0) nbtPanel.render(graphics,
            new Bounds(body.x() + 3, body.y() + listHeight + detailHeight, body.width() - 6, nbtHeight),
            hoverX, hoverY,
            (id, bounds, label, active, selected, action) -> button(id, bounds, label, active, selected, true, false, action));
    }

    private void renderSources(GuiGraphicsExtractor graphics, Bounds area, PauseSnapshot snapshot, int headingInset) {
        String heading = expandedGroup.isEmpty() ? tr("codon.ui.contexts")
            : Component.translatable("codon.ui.group", expandedGroup.size()).getString();
        text(graphics, heading, area.x() + 7 + headingInset, area.y() + 6, area.width() - 42 - headingInset, TEXT);
        if (!expandedGroup.isEmpty()) {
            button("all-sources", new Bounds(area.x() + area.width() - 39, area.y() + 2, 35, 15),
                component("codon.ui.all"), true, false, false, false, () -> { expandedGroup = List.of(); sourceOffset = 0; });
        }
        List<Integer> indices = expandedGroup;
        if (indices.isEmpty()) {
            List<Integer> all = new ArrayList<>(state.displayedSources().size());
            for (int i = 0; i < state.displayedSources().size(); i++) all.add(i);
            indices = all;
        }
        int rows = Math.max(0, (area.height() - 30) / 19);
        maxSourceOffset = Math.max(0, indices.size() - Math.max(1, rows));
        sourceOffset = Math.max(0, Math.min(sourceOffset, maxSourceOffset));
        sourceScrollBounds = area;
        if (indices.isEmpty()) text(graphics, tr("codon.ui.no_sources"), area.x() + 7, area.y() + 24, area.width() - 14, MUTED);
        for (int row = 0; row < rows && sourceOffset + row < indices.size(); row++) {
            int index = indices.get(sourceOffset + row);
            PauseSource source = state.displayedSources().get(index);
            String name = sourceLabel(source, index, state.isDisplayedSourceDropped(index));
            Component title = Component.literal(name);
            Component tooltip = sourceTooltip(title, index);
            if (!source.dimension().equals(dimension())) tooltip = tooltip.copy().append("\n" + shortDimension(source.dimension()));
            int labelWidth = area.width() - 13;
            colorSourceButton(button("source-" + index, new Bounds(area.x() + 5, area.y() + 21 + row * 19, labelWidth, 17),
                title, true, index == state.selectedSourceIndex(), true, false, () -> {
                    if (state.snapshot() == snapshot) {
                        state.selectSource(index);
                        state.preferences().setInspectorTab(InspectorTab.SOURCES);
                    }
                }), index).setTooltip(Tooltip.create(tooltip));
        }
        if (rows > 0 && indices.size() > rows) {
            text(graphics, (sourceOffset + 1) + "–" + Math.min(indices.size(), sourceOffset + rows) + " / " + indices.size(),
                area.x() + 7, area.y() + area.height() - 8, area.width() - 14, MUTED);
            scrollbar(graphics, area.x() + area.width() - 5, area.y() + 21, Math.max(1, rows * 19 - 2),
                sourceOffset, maxSourceOffset, rows, indices.size());
        }
    }

    private void renderSourceDetails(GuiGraphicsExtractor graphics, Bounds area, int headingInset) {
        PauseSource source = state.selectedSource();
        if (source == null) return;
        int y = area.y() + 6;
        int accent = sourceColor(state.selectedSourceIndex(), TEAL);
        int iconX = area.x() + area.width() - 23;
        if (source.entity() != null) {
            String uuid = source.entity().uuid().toString();
            iconButton("copy-uuid", new Bounds(iconX, y - 3, 16, 16),
                Component.literal("UUID: " + uuid + "\n" + tr("codon.ui.copy")), DebuggerIcon.COPY_UUID,
                true, () -> client.keyboardHandler.setClipboard(uuid)).withoutChrome();
            iconX -= 18;
        }
        if (!visibleSources.contains(state.selectedSourceIndex())) {
            sourceStatusIcon(graphics, iconX, y - 3, DebuggerIcon.OUTSIDE_VIEWPORT, AMBER,
                component(source.dimension().equals(dimension()) ? "codon.ui.offscreen" : "codon.ui.other_dimension"));
            iconX -= 18;
        }
        if (state.selectedSourceDropped() || state.selectedSourceCreated()) {
            boolean dropped = state.selectedSourceDropped();
            sourceStatusIcon(graphics, iconX, y - 3,
                dropped ? DebuggerIcon.SOURCE_EXCLUDED : DebuggerIcon.SOURCE_CREATED,
                dropped ? RED : GREEN, component(dropped ? "codon.ui.flow_excluded" : "codon.ui.flow_created"));
            iconX -= 18;
        }
        text(graphics, sourceLabel(source, state.selectedSourceIndex(), state.selectedSourceDropped()),
            area.x() + 7 + headingInset, y, iconX + 16 - area.x() - 9 - headingInset, accent);
        y += 16;
        if (!state.selectedSourceDropped()) {
            ExecutionFlowContext parent = state.selectedFlowParent();
            if (parent != null) y = renderFlowChanges(graphics, area, y, parent.source(), source);
        }
        if (y + 9 > area.y() + area.height()) return;
        text(graphics, tr("codon.ui.anchor"), area.x() + 7, y, area.width() - 14, MUTED);
        y += 10;
        if (y + 9 > area.y() + area.height()) return;
        text(graphics, String.format(Locale.ROOT, "%.2f, %.2f, %.2f", source.anchor().x(), source.anchor().y(), source.anchor().z()),
            area.x() + 7, y, area.width() - 14, TEXT);
        y += 11;
        if (y + 9 > area.y() + area.height()) return;
        text(graphics, shortDimension(source.dimension()), area.x() + 7, y, area.width() - 14,
            source.dimension().equals(dimension()) ? MUTED : AMBER);
        y += 11;
        if (y + 9 < area.y() + area.height()) {
            text(graphics, String.format(Locale.ROOT, "yaw %.1f° / pitch %.1f°", source.yaw(), source.pitch()),
                area.x() + 7, y, area.width() - 14, TEXT);
            y += 11;
        }
    }

    private void sourceStatusIcon(GuiGraphicsExtractor graphics, int x, int y, DebuggerIcon icon,
                                  int color, Component description) {
        icon.draw(graphics, x + 2, y + 2, color);
        if (hoverX >= x && hoverX < x + 16 && hoverY >= y && hoverY < y + 16) {
            graphics.setTooltipForNextFrame(client.font, description, hoverX, hoverY);
        }
    }

    private int sourceDetailsHeight() {
        PauseSource source = state.selectedSource();
        if (source == null) return 20;
        int height = 6 + 16 + 10 + 11 + 11 + 11 + 5;
        if (!state.selectedSourceDropped()) {
            ExecutionFlowContext parent = state.selectedFlowParent();
            if (parent != null) {
                if (!Objects.equals(parent.source().entity(), source.entity())) height += 11;
                if (!parent.source().dimension().equals(source.dimension())) height += 11;
                if (!parent.source().anchor().equals(source.anchor())) height += 11;
            }
        }
        return height;
    }

    private int sourceColor(int index, int fallback) {
        return state.isDisplayedSourceDropped(index) ? RED : state.isDisplayedSourceCreated(index) ? GREEN : fallback;
    }

    private int worldSourceColor(int index, int fallback) {
        return state.isWorldSourceDropped(index) ? RED : state.isWorldSourceCreated(index) ? GREEN : fallback;
    }

    private DebuggerButton colorWorldSourceButton(DebuggerButton button, int index) {
        if (state.isWorldSourceDropped(index)) return button.withStatusColor(RED, RED_SURFACE);
        if (state.isWorldSourceCreated(index)) return button.withStatusColor(GREEN, GREEN_SURFACE);
        return button;
    }

    private Component worldSourceTooltip(Component title, int index) {
        return title;
    }

    private DebuggerButton colorSourceButton(DebuggerButton button, int index) {
        if (state.isDisplayedSourceDropped(index)) return button.withStatusColor(RED, RED_SURFACE);
        if (state.isDisplayedSourceCreated(index)) return button.withStatusColor(GREEN, GREEN_SURFACE);
        return button;
    }

    private Component sourceTooltip(Component title, int index) {
        return title;
    }

    private int renderFlowChanges(GuiGraphicsExtractor graphics, Bounds area, int y,
                                  PauseSource before, PauseSource after) {
        String beforeEntity = before.entity() == null ? tr("codon.ui.position_source") : before.entity().name();
        String afterEntity = after.entity() == null ? tr("codon.ui.position_source") : after.entity().name();
        if (!Objects.equals(before.entity(), after.entity()) && y + 9 <= area.y() + area.height()) {
            text(graphics, Component.translatable("codon.ui.flow_executor_change", beforeEntity, afterEntity).getString(),
                area.x() + 7, y, area.width() - 14, TEXT);
            y += 11;
        }
        if (!before.dimension().equals(after.dimension()) && y + 9 <= area.y() + area.height()) {
            text(graphics, Component.translatable("codon.ui.flow_dimension_change",
                shortDimension(before.dimension()), shortDimension(after.dimension())).getString(),
                area.x() + 7, y, area.width() - 14, AMBER);
            y += 11;
        }
        if (!before.anchor().equals(after.anchor()) && y + 9 <= area.y() + area.height()) {
            String from = String.format(Locale.ROOT, "%.1f,%.1f,%.1f",
                before.anchor().x(), before.anchor().y(), before.anchor().z());
            String to = String.format(Locale.ROOT, "%.1f,%.1f,%.1f",
                after.anchor().x(), after.anchor().y(), after.anchor().z());
            text(graphics, Component.translatable("codon.ui.flow_position_change", from, to).getString(),
                area.x() + 7, y, area.width() - 14, TEXT);
            y += 11;
        }
        return y;
    }

    public boolean scroll(double x, double y, double scrollX, double amount) {
        if (commandPanel.scroll(x, y, scrollX, amount) || nbtPanel.scroll(x, y, amount)) return true;
        int delta = amount > 0 ? -1 : amount < 0 ? 1 : 0;
        if (delta == 0) return false;
        if (watchSummaryScrollBounds.contains(x, y)) {
            watchSummaryOffset = Math.clamp(watchSummaryOffset + delta, 0, maxWatchSummaryOffset);
        } else if (sourceScrollBounds.contains(x, y)) {
            sourceOffset = Math.clamp(sourceOffset + delta, 0, maxSourceOffset);
        } else return false;
        return true;
    }

    private DebuggerButton button(String id, Bounds bounds, Component label, boolean active, boolean selected,
                                  boolean leftAligned, boolean subdued, Runnable action) {
        DebuggerButton button = buttonCache.computeIfAbsent(id, ignored -> new DebuggerButton());
        button.configure(bounds.x(), bounds.y(), bounds.width(), bounds.height(), label, active,
            selected, leftAligned, subdued, action);
        usedButtons.add(id);
        controls.add(button);
        return button;
    }

    private DebuggerButton iconButton(String id, Bounds bounds, Component label, DebuggerIcon icon,
                                     boolean active, Runnable action) {
        DebuggerButton button = button(id, bounds, label, active, false, false, false, action).withIcon(icon);
        return button;
    }

    private void text(GuiGraphicsExtractor graphics, String value, int x, int y, int width, int color) {
        if (width <= 0) return;
        graphics.enableScissor(x, y, x + width, y + client.font.lineHeight + 1);
        graphics.text(client.font, trimmed(value, width), x, y, color, false);
        graphics.disableScissor();
        if (client.font.width(value) > width && hoverX >= x && hoverX < x + width
            && hoverY >= y && hoverY < y + client.font.lineHeight + 1) {
            graphics.setTooltipForNextFrame(client.font, Component.literal(value), hoverX, hoverY);
        }
    }

    private String trimmed(String value, int width) {
        if (client.font.width(value) <= width) return value;
        if (width < client.font.width("…")) return "";
        return client.font.plainSubstrByWidth(value, width - client.font.width("…")) + "…";
    }

    private void wrapped(GuiGraphicsExtractor graphics, Component value, Bounds bounds, int color) {
        if (bounds.width() <= 0) return;
        int y = bounds.y();
        for (FormattedCharSequence line : client.font.split(value, bounds.width())) {
            if (y + client.font.lineHeight > bounds.y() + bounds.height()) break;
            graphics.text(client.font, line, bounds.x(), y, color, false);
            y += 11;
        }
    }

    private static void sectionDivider(GuiGraphicsExtractor graphics, Bounds area) {
        graphics.fill(area.x(), area.y(), area.x() + area.width(), area.y() + 1, BORDER);
    }

    private static void panel(GuiGraphicsExtractor graphics, Bounds bounds) {
        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + bounds.width(), bounds.y() + bounds.height(), PANEL);
        graphics.outline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), BORDER);
    }

    private static void leader(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int color) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        if (steps < 1) return;
        // GUI extraction exposes rectangles, not arbitrary lines. One pixel per step is enough.
        for (int i = 0; i <= steps; i += 2) {
            int x = x1 + (x2 - x1) * i / steps;
            int y = y1 + (y2 - y1) * i / steps;
            graphics.fill(x, y, x + 1, y + 1, color);
        }
    }

    private static void scrollbar(GuiGraphicsExtractor graphics, int x, int y, int height,
                                  int offset, int maxOffset, int rows, int total) {
        if (height <= 0 || total <= rows || maxOffset <= 0) return;
        graphics.fill(x, y, x + 2, y + height, BORDER);
        int thumb = Math.min(height, Math.max(6, height * rows / total));
        int top = y + (height - thumb) * offset / maxOffset;
        graphics.fill(x, top, x + 2, top + thumb, TEAL);
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

    private static String tr(String key) {
        return component(key).getString();
    }
}
