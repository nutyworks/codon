package works.nuty.bastion.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import works.nuty.bastion.client.input.InputManager;
import works.nuty.bastion.client.state.ClientDebuggerState;
import works.nuty.bastion.client.state.DebuggerPreferences.InspectorTab;
import works.nuty.bastion.client.ui.layout.DebuggerLayout;
import works.nuty.bastion.client.ui.layout.GizmoLabelLayout;
import works.nuty.bastion.client.ui.layout.GizmoLabelLayout.Anchor;
import works.nuty.bastion.client.ui.layout.GizmoLabelLayout.Bounds;
import works.nuty.bastion.core.model.CallFrame;
import works.nuty.bastion.core.model.CommandSnippet;
import works.nuty.bastion.core.model.PauseReason;
import works.nuty.bastion.core.model.PauseSnapshot;
import works.nuty.bastion.core.model.PauseSource;
import works.nuty.bastion.core.model.SourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static works.nuty.bastion.client.ui.DebuggerTheme.*;

/** Shared HUD/screen presentation. Only the B-key screen registers the rendered controls. */
public final class DebuggerOverlay {
    private static final Bounds EMPTY = new Bounds(0, 0, 0, 0);
    private final ClientDebuggerState state;
    private final Minecraft client = Minecraft.getInstance();
    private final Map<String, DebuggerButton> buttonCache = new HashMap<>();
    private final List<DebuggerButton> controls = new ArrayList<>();
    private final Set<String> usedButtons = new HashSet<>();
    private final Set<Integer> visibleSources = new HashSet<>();
    private @Nullable PauseSnapshot lastSnapshot;
    private List<Integer> expandedGroup = List.of();
    private int sourceOffset;
    private int stackOffset;
    private int commandOffset;
    private int maxSourceOffset;
    private int maxStackOffset;
    private int maxCommandOffset;
    private Bounds sourceScrollBounds = EMPTY;
    private Bounds stackScrollBounds = EMPTY;
    private Bounds commandScrollBounds = EMPTY;
    private boolean showInspector;

    public DebuggerOverlay(ClientDebuggerState state) {
        this.state = state;
    }

    public List<DebuggerButton> render(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                       float partialTick, boolean interactive, InputManager input) {
        controls.clear();
        usedButtons.clear();
        sourceScrollBounds = stackScrollBounds = commandScrollBounds = EMPTY;
        if (client.level == null || client.player == null) {
            buttonCache.clear();
            return List.of();
        }
        PauseSnapshot snapshot = state.snapshot();
        if (snapshot != lastSnapshot) {
            expandedGroup = List.of();
            sourceOffset = Math.max(0, state.selectedSourceIndex());
            stackOffset = commandOffset = 0;
            lastSnapshot = snapshot;
        }
        Font font = client.font;
        if ((!state.isPaused() || snapshot == null) && !interactive) {
            if (!state.blockBreakpoints().isEmpty()) {
                String text = "BASTION · " + tr("bastion.ui.ready") + "  [" + input.menuKey.getTranslatedKeyMessage().getString() + "]";
                graphics.fill(6, 6, Math.min(graphics.guiWidth() - 6, font.width(text) + 18), 23, PANEL);
                text(graphics, text, 12, 11, Math.max(0, graphics.guiWidth() - 24), MUTED);
            }
            buttonCache.clear();
            return List.of();
        }

        showInspector = state.preferences().inspectorVisible() != null ? state.preferences().inspectorVisible()
            : graphics.guiWidth() >= 420 && graphics.guiHeight() >= 220;
        DebuggerLayout layout = DebuggerLayout.create(graphics.guiWidth(), graphics.guiHeight(), showInspector);
        renderHeader(graphics, layout, input, snapshot);
        renderWorldLabels(graphics, layout.world(), snapshot);
        if (showInspector) renderInspector(graphics, layout.inspector(), snapshot);
        renderCommand(graphics, layout.command(), snapshot);
        if (layout.footer().height() > 0) {
            Bounds footer = layout.footer();
            String hint = state.isPaused()
                ? Component.translatable("bastion.ui.freecam_shortcuts", input.menuKey.getTranslatedKeyMessage(),
                    client.options.keyUp.getTranslatedKeyMessage(), client.options.keyLeft.getTranslatedKeyMessage(),
                    client.options.keyDown.getTranslatedKeyMessage(), client.options.keyRight.getTranslatedKeyMessage(),
                    client.options.keyJump.getTranslatedKeyMessage(), client.options.keyShift.getTranslatedKeyMessage(),
                    client.options.keySprint.getTranslatedKeyMessage(), input.breakpointKey.getTranslatedKeyMessage()).getString()
                : Component.translatable("bastion.ui.shortcuts", input.menuKey.getTranslatedKeyMessage(),
                    input.breakpointKey.getTranslatedKeyMessage()).getString();
            text(graphics, hint, footer.x() + 3, footer.y() + 3, footer.width(), MUTED);
        }

        buttonCache.keySet().retainAll(usedButtons);
        for (DebuggerButton button : controls) {
            if (!interactive) button.setFocused(false);
            button.extractRenderState(graphics, interactive ? mouseX : -1, interactive ? mouseY : -1, partialTick);
        }
        return List.copyOf(controls);
    }

    private void renderHeader(GuiGraphicsExtractor graphics, DebuggerLayout layout, InputManager input,
                              @Nullable PauseSnapshot snapshot) {
        String status = state.controlPending() ? tr("bastion.ui.waiting")
            : snapshot == null ? tr("bastion.ui.running")
            : tr(snapshot.reason() == PauseReason.STEP ? "bastion.ui.step_complete" : "bastion.ui.breakpoint_hit");
        Bounds toolbar = layout.controls();
        Bounds header = new Bounds(layout.header().x(), layout.header().y(),
            Math.min(layout.header().width(), Math.max(toolbar.width(), 72 + client.font.width(status))),
            layout.header().height());
        panel(graphics, header);
        panel(graphics, toolbar);
        graphics.fill(header.x(), header.y(), header.x() + 2, header.y() + header.height(), TEAL);
        text(graphics, "BASTION", header.x() + 7, header.y() + 5, 52, TEXT);
        text(graphics, status, header.x() + 64, header.y() + 5, Math.max(0, header.width() - 69),
            snapshot == null ? MUTED : AMBER);

        int gap = DebuggerLayout.ICON_BUTTON_GAP;
        int width = Math.min(DebuggerLayout.ICON_BUTTON_SIZE,
            Math.max(1, (toolbar.width() - 6 - 4 * gap - DebuggerLayout.ICON_GROUP_GAP) / 6));
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
                component(action.translationKey()), icon, snapshot != null && state.isPaused() && !state.controlPending(),
                () -> input.control(action));
            control.setTooltip(Tooltip.create(component(action.translationKey()).append("  ").append(input.keyLabel(action))));
            x += width + gap;
        }
        x += DebuggerLayout.ICON_GROUP_GAP - gap;
        graphics.fill(x - 4, toolbar.y() + 5, x - 3, toolbar.y() + toolbar.height() - 5, BORDER);
        Component mode = Component.literal("Gizmo: ")
            .append(component("bastion.ui.mode." + state.gizmoMode().name().toLowerCase(Locale.ROOT)));
        DebuggerIcon modeIcon = switch (state.gizmoMode()) {
            case GROUPED -> DebuggerIcon.GIZMO_GROUPED;
            case LABELS -> DebuggerIcon.GIZMO_LABELS;
            case FOCUS -> DebuggerIcon.GIZMO_FOCUS;
        };
        iconButton("mode", new Bounds(x, toolbar.y() + 2, width, DebuggerLayout.ICON_BUTTON_SIZE),
            mode, modeIcon, true, () -> {
                state.setGizmoMode(state.gizmoMode().next());
                expandedGroup = List.of();
                sourceOffset = 0;
            });
        iconButton("inspector", new Bounds(x + width + gap, toolbar.y() + 2, width, DebuggerLayout.ICON_BUTTON_SIZE),
            component("bastion.ui.details"), showInspector ? DebuggerIcon.DETAILS_OPEN : DebuggerIcon.DETAILS_CLOSED,
            true, () -> state.preferences().setInspectorVisible(!showInspector));
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
        for (int index = 0; index < snapshot.pauseSources().size(); index++) {
            PauseSource source = snapshot.pauseSources().get(index);
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
            if (!labelArea.contains(x, y)) continue;
            visibleSources.add(index);
            if (state.gizmoMode() == ClientDebuggerState.GizmoMode.FOCUS && index != state.selectedSourceIndex()) continue;
            int labelWidth = Math.min(150, client.font.width(sourceLabel(source, index)) + 14);
            anchors.add(new Anchor(index, x, y, labelWidth));
        }
        List<GizmoLabelLayout.Label> labels = GizmoLabelLayout.layout(anchors, labelArea,
            state.selectedSourceIndex(), state.gizmoMode() != ClientDebuggerState.GizmoMode.LABELS);
        for (GizmoLabelLayout.Label label : labels) {
            List<Integer> indices = label.sourceIndices();
            boolean selected = indices.contains(state.selectedSourceIndex());
            boolean group = indices.size() > 1;
            int index = indices.getFirst();
            Bounds bounds = label.bounds();
            String sourceTitle = sourceLabel(snapshot.pauseSources().get(index), index);
            Component title;
            if (group && selected) {
                String count = "  +" + (indices.size() - 1);
                title = Component.literal(trimmed(sourceTitle, Math.max(0, bounds.width() - 10 - client.font.width(count))) + count);
            } else {
                title = group ? Component.translatable("bastion.ui.group", indices.size()) : Component.literal(sourceTitle);
            }
            leader(graphics, (int) label.anchorX(), (int) label.anchorY(),
                bounds.x() + bounds.width() / 2, bounds.y() + bounds.height(), selected ? TEAL : MUTED);
            button("label-" + index, bounds, title, true, selected, false, false, () -> {
                if (state.snapshot() != snapshot) return;
                if (group) {
                    expandedGroup = List.copyOf(indices);
                    if (!indices.contains(state.selectedSourceIndex())) state.selectSource(index);
                    sourceOffset = 0;
                } else {
                    expandedGroup = List.of();
                    state.selectSource(index);
                    sourceOffset = index;
                }
                state.preferences().setInspectorVisible(true);
                state.preferences().setInspectorTab(group ? InspectorTab.SOURCES : InspectorTab.DETAILS);
            }).setTooltip(Tooltip.create(group ? component("bastion.ui.group_hint") : title));
        }
        String legend = tr("bastion.ui.legend");
        int legendWidth = Math.min(world.width(), client.font.width(legend) + 10);
        graphics.fill(world.x(), world.y() + world.height() - 13, world.x() + legendWidth, world.y() + world.height(), PANEL);
        text(graphics, legend, world.x() + 4, world.y() + world.height() - 10, world.width() - 8, MUTED);
    }

    private void renderInspector(GuiGraphicsExtractor graphics, Bounds area, @Nullable PauseSnapshot snapshot) {
        panel(graphics, area);
        if (area.height() < 40) return;
        if (snapshot == null) {
            wrapped(graphics, component("bastion.ui.no_snapshot"), new Bounds(area.x() + 8, area.y() + 9,
                area.width() - 16, area.height() - 18), MUTED);
            return;
        }
        if (area.height() >= 265) {
            int sourcesHeight = Math.min(106, 30 + Math.max(1, snapshot.pauseSources().size()) * 19);
            Bounds sources = new Bounds(area.x(), area.y(), area.width(), sourcesHeight);
            renderSources(graphics, sources, snapshot);
            renderSourceDetails(graphics, new Bounds(area.x(), area.y() + sourcesHeight, area.width(), 98));
            renderStack(graphics, new Bounds(area.x(), area.y() + sourcesHeight + 98, area.width(),
                area.height() - sourcesHeight - 98), snapshot);
        } else {
            boolean small = area.height() < 190;
            int tabCount = small ? 3 : 2;
            int tabWidth = (area.width() - 3 * (tabCount + 1)) / tabCount;
            button("tab-sources", new Bounds(area.x() + 3, area.y() + 3, tabWidth, 18), component("bastion.ui.contexts"),
                true, state.preferences().inspectorTab() != InspectorTab.STACK
                    && (!small || state.preferences().inspectorTab() != InspectorTab.DETAILS), false, false,
                () -> state.preferences().setInspectorTab(InspectorTab.SOURCES));
            if (small) button("tab-detail", new Bounds(area.x() + 6 + tabWidth, area.y() + 3, tabWidth, 18),
                component("bastion.ui.details"), true, state.preferences().inspectorTab() == InspectorTab.DETAILS, false, false,
                () -> state.preferences().setInspectorTab(InspectorTab.DETAILS));
            button("tab-stack", new Bounds(area.x() + 3 + (tabCount - 1) * (tabWidth + 3), area.y() + 3, tabWidth, 18), component("bastion.ui.stack"),
                true, state.preferences().inspectorTab() == InspectorTab.STACK, false, false,
                () -> state.preferences().setInspectorTab(InspectorTab.STACK));
            Bounds body = new Bounds(area.x(), area.y() + 25, area.width(), area.height() - 25);
            if (state.preferences().inspectorTab() == InspectorTab.STACK) {
                renderStack(graphics, body, snapshot);
            } else if (small && state.preferences().inspectorTab() == InspectorTab.DETAILS) {
                renderSourceDetails(graphics, body);
            } else {
                int detailHeight = body.height() >= 155 ? 94 : body.height() >= 112 ? 70 : 0;
                renderSources(graphics, new Bounds(body.x(), body.y(), body.width(), body.height() - detailHeight), snapshot);
                if (detailHeight > 0) renderSourceDetails(graphics,
                    new Bounds(body.x(), body.y() + body.height() - detailHeight, body.width(), detailHeight));
            }
        }
    }

    private void renderSources(GuiGraphicsExtractor graphics, Bounds area, PauseSnapshot snapshot) {
        String heading = expandedGroup.isEmpty() ? tr("bastion.ui.contexts")
            : Component.translatable("bastion.ui.group", expandedGroup.size()).getString();
        text(graphics, heading, area.x() + 7, area.y() + 6, area.width() - 42, TEXT);
        if (!expandedGroup.isEmpty()) {
            button("all-sources", new Bounds(area.x() + area.width() - 39, area.y() + 2, 35, 15),
                component("bastion.ui.all"), true, false, false, false, () -> { expandedGroup = List.of(); sourceOffset = 0; });
        }
        List<Integer> indices = expandedGroup;
        if (indices.isEmpty()) {
            List<Integer> all = new ArrayList<>(snapshot.pauseSources().size());
            for (int i = 0; i < snapshot.pauseSources().size(); i++) all.add(i);
            indices = all;
        }
        int rows = Math.max(0, (area.height() - 30) / 19);
        maxSourceOffset = Math.max(0, indices.size() - Math.max(1, rows));
        sourceOffset = Math.max(0, Math.min(sourceOffset, maxSourceOffset));
        sourceScrollBounds = area;
        if (indices.isEmpty()) text(graphics, tr("bastion.ui.no_sources"), area.x() + 7, area.y() + 24, area.width() - 14, MUTED);
        for (int row = 0; row < rows && sourceOffset + row < indices.size(); row++) {
            int index = indices.get(sourceOffset + row);
            PauseSource source = snapshot.pauseSources().get(index);
            String name = sourceLabel(source, index);
            if (!source.dimension().equals(dimension())) name += " · " + shortDimension(source.dimension());
            button("source-" + index, new Bounds(area.x() + 5, area.y() + 21 + row * 19, area.width() - 13, 17),
                Component.literal(name), true, index == state.selectedSourceIndex(), true, false, () -> {
                    if (state.snapshot() == snapshot) {
                        state.selectSource(index);
                        state.preferences().setInspectorTab(InspectorTab.DETAILS);
                    }
                }).setTooltip(Tooltip.create(Component.literal(name)));
        }
        if (rows > 0 && indices.size() > rows) {
            text(graphics, (sourceOffset + 1) + "–" + Math.min(indices.size(), sourceOffset + rows) + " / " + indices.size(),
                area.x() + 7, area.y() + area.height() - 8, area.width() - 14, MUTED);
            scrollbar(graphics, area.x() + area.width() - 5, area.y() + 21, Math.max(1, rows * 19 - 2),
                sourceOffset, maxSourceOffset, rows, indices.size());
        }
    }

    private void renderSourceDetails(GuiGraphicsExtractor graphics, Bounds area) {
        PauseSource source = state.selectedSource();
        if (source == null) return;
        graphics.fill(area.x() + 5, area.y(), area.x() + area.width() - 5, area.y() + 1, BORDER);
        int y = area.y() + 6;
        text(graphics, "#" + (state.selectedSourceIndex() + 1) + " · " + name(source), area.x() + 7, y, area.width() - 14, TEAL);
        y += 13;
        text(graphics, tr("bastion.ui.anchor"), area.x() + 7, y, area.width() - 14, MUTED);
        y += 10;
        text(graphics, String.format(Locale.ROOT, "%.2f, %.2f, %.2f", source.anchor().x(), source.anchor().y(), source.anchor().z()),
            area.x() + 7, y, area.width() - 14, TEXT);
        y += 11;
        text(graphics, shortDimension(source.dimension()), area.x() + 7, y, area.width() - 14,
            source.dimension().equals(dimension()) ? MUTED : AMBER);
        y += 11;
        if (y + 9 < area.y() + area.height()) {
            text(graphics, String.format(Locale.ROOT, "yaw %.1f° / pitch %.1f°", source.yaw(), source.pitch()),
                area.x() + 7, y, area.width() - 14, TEXT);
            y += 11;
        }
        if (source.entity() != null && y + 16 <= area.y() + area.height()) {
            String uuid = source.entity().uuid().toString();
            button("copy-uuid", new Bounds(area.x() + 7, y, area.width() - 14, 16),
                Component.literal("UUID " + uuid.substring(0, 4) + "..." + uuid.substring(uuid.length() - 4)
                    + " " + tr("bastion.ui.copy")),
                true, false, true, false, () -> client.keyboardHandler.setClipboard(uuid))
                .setTooltip(Tooltip.create(Component.literal(uuid)));
            y += 18;
        }
        if (!visibleSources.contains(state.selectedSourceIndex()) && y + 9 <= area.y() + area.height()) {
            text(graphics, tr(source.dimension().equals(dimension()) ? "bastion.ui.offscreen" : "bastion.ui.other_dimension"),
                area.x() + 7, y, area.width() - 14, AMBER);
        }
    }

    private void renderStack(GuiGraphicsExtractor graphics, Bounds area, PauseSnapshot snapshot) {
        if (area.height() < 29) return;
        graphics.fill(area.x() + 5, area.y(), area.x() + area.width() - 5, area.y() + 1, BORDER);
        text(graphics, tr("bastion.ui.stack"), area.x() + 7, area.y() + 6, area.width() - 14, TEXT);
        int rows = Math.max(0, (area.height() - 31) / 28);
        maxStackOffset = Math.max(0, snapshot.callStack().size() - Math.max(1, rows));
        stackOffset = Math.max(0, Math.min(stackOffset, maxStackOffset));
        stackScrollBounds = area;
        if (snapshot.callStack().isEmpty()) text(graphics, tr("bastion.ui.no_frames"), area.x() + 7,
            area.y() + 22, area.width() - 14, MUTED);
        for (int row = 0; row < rows && stackOffset + row < snapshot.callStack().size(); row++) {
            int index = stackOffset + row;
            CallFrame frame = snapshot.callStack().get(index);
            int y = area.y() + 20 + row * 28;
            String label = (index == 0 ? "> " : "  ") + location(frame.location());
            button("frame-" + index, new Bounds(area.x() + 5, y, area.width() - 13, 17),
                Component.literal(label), true, index == state.selectedFrameIndex(), true, false, () -> {
                    if (state.snapshot() == snapshot) { state.selectFrame(index); commandOffset = 0; }
                }).setTooltip(Tooltip.create(ClientFormatting.sourceLocation(frame.location())));
            text(graphics, frame.command().text(), area.x() + 10, y + 19, area.width() - 20, MUTED);
        }
        if (rows > 0 && snapshot.callStack().size() > rows) {
            text(graphics, (stackOffset + 1) + "–" + Math.min(snapshot.callStack().size(), stackOffset + rows)
                    + " / " + snapshot.callStack().size(), area.x() + 7, area.y() + area.height() - 8, area.width() - 14, MUTED);
            scrollbar(graphics, area.x() + area.width() - 5, area.y() + 20, Math.max(1, rows * 28 - 1),
                stackOffset, maxStackOffset, rows, snapshot.callStack().size());
        }
    }

    private void renderCommand(GuiGraphicsExtractor graphics, Bounds area, @Nullable PauseSnapshot snapshot) {
        if (area.height() < 20) return;
        panel(graphics, area);
        if (snapshot == null) {
            text(graphics, tr("bastion.ui.no_snapshot"), area.x() + 8, area.y() + 9, area.width() - 16, MUTED);
            return;
        }
        int index = state.selectedFrameIndex();
        CallFrame frame = index >= 0 && index < snapshot.callStack().size() ? snapshot.callStack().get(index) : null;
        SourceLocation location = frame == null ? snapshot.location() : frame.location();
        CommandSnippet command = frame == null ? snapshot.command() : frame.command();
        String caption = tr(index == 0 ? "bastion.ui.current_command" : "bastion.ui.caller_command") + " · " + location(location);
        text(graphics, caption, area.x() + 8, area.y() + 5, area.width() - (index == 0 ? 16 : 87), index == 0 ? AMBER : MUTED);
        if (index > 0) button("current-frame", new Bounds(area.x() + area.width() - 75, area.y() + 2, 70, 15),
            component("bastion.ui.current_frame"), true, false, false, false, () -> { state.selectFrame(0); stackOffset = commandOffset = 0; });
        Bounds content = new Bounds(area.x() + 8, area.y() + 19, Math.max(1, area.width() - 21), area.height() - 21);
        graphics.fill(area.x() + 4, content.y() - 1, area.x() + area.width() - 4, area.y() + area.height() - 3, AMBER_SURFACE);
        graphics.fill(area.x() + 4, content.y() - 1, area.x() + 6, area.y() + area.height() - 3, index == 0 ? AMBER : BORDER);
        List<FormattedCharSequence> lines = client.font.split(ClientFormatting.command(command), content.width());
        int rows = Math.max(1, content.height() / 10);
        maxCommandOffset = Math.max(0, lines.size() - rows);
        commandOffset = Math.max(0, Math.min(commandOffset, maxCommandOffset));
        commandScrollBounds = area;
        graphics.enableScissor(content.x(), content.y(), content.x() + content.width(), area.y() + area.height() - 3);
        for (int i = 0; i < rows && commandOffset + i < lines.size(); i++) {
            graphics.text(client.font, lines.get(commandOffset + i), content.x() + 2, content.y() + i * 10, TEXT, false);
        }
        graphics.disableScissor();
        if (maxCommandOffset > 0) scrollbar(graphics, area.x() + area.width() - 7, content.y(), content.height(),
            commandOffset, maxCommandOffset, rows, lines.size());
    }

    public boolean scroll(double x, double y, double amount) {
        int delta = amount > 0 ? -1 : amount < 0 ? 1 : 0;
        if (delta == 0) return false;
        if (sourceScrollBounds.contains(x, y)) {
            sourceOffset = Math.clamp(sourceOffset + delta, 0, maxSourceOffset);
        } else if (stackScrollBounds.contains(x, y)) {
            stackOffset = Math.clamp(stackOffset + delta, 0, maxStackOffset);
        } else if (commandScrollBounds.contains(x, y)) {
            commandOffset = Math.clamp(commandOffset + delta, 0, maxCommandOffset);
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
        button.setTooltip(Tooltip.create(label));
        return button;
    }

    private void text(GuiGraphicsExtractor graphics, String value, int x, int y, int width, int color) {
        if (width <= 0) return;
        graphics.enableScissor(x, y, x + width, y + client.font.lineHeight + 1);
        graphics.text(client.font, trimmed(value, width), x, y, color, false);
        graphics.disableScissor();
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

    private static String sourceLabel(PauseSource source, int index) {
        return (source.entity() == null ? "[" + (index + 1) + "] " : "#" + (index + 1) + " ") + name(source);
    }

    private static String name(PauseSource source) {
        return source.entity() == null ? tr("bastion.ui.position_source") : source.entity().name();
    }

    private static String location(SourceLocation location) {
        return switch (location) {
            case SourceLocation.Function function -> function.location().function() + ":" + function.location().line();
            case SourceLocation.Block block -> tr("bastion.ui.command_block") + " " + block.block().x() + ", " + block.block().y() + ", " + block.block().z();
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
