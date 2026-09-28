package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import works.nuty.codon.mixin.client.EditBoxAccessor;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;

/** Soft wrapping only: command text and vanilla editing/completion offsets stay unchanged. */
public final class WrappedCommandEditBox extends EditBox {
    private static final int LINE_HEIGHT = 14;
    private static final int MARKER_WIDTH = 12;
    private static final int MARKER_RADIUS = 4;
    private final Font font;
    private final List<Line> lines = new ArrayList<>();
    private String laidOutValue;
    private int laidOutWidth;
    private int firstRow;
    private int lastCursor = -1;
    private List<BreakpointTarget> laidOutMarkers = List.of();
    private List<Marker> shownMarkers = List.of();
    private @Nullable BreakpointTarget hoveredTarget;
    private @Nullable BreakpointTarget focusedTarget;
    private int hoverLeft, hoverRight, hoverTop, hoverBottom;

    private String markerCommand = "";
    private List<Marker> markers = List.of();

    public record Marker(BreakpointTarget target, int start, int end,
                         @Nullable BreakpointDefinition definition) {
        public boolean enabled() { return definition != null && definition.enabled(); }
    }
    public record MarkerPosition(int x, int y, boolean visible) { }
    private record Line(int start, int end) { }

    public void setBreakpointMarkers(String command, List<Marker> markers) {
        markerCommand = command;
        this.markers = List.copyOf(markers);
    }

    public void focusBreakpoint(BreakpointTarget target) { focusedTarget = target; layout(); }
    public void clearBreakpointFocus(BreakpointTarget target) {
        if (target.equals(focusedTarget)) focusedTarget = null;
    }

    private int markerOffset(Marker marker) { return Math.max(0, marker.start()); }
    private int wholeMarkerX() { return Math.max(MARKER_RADIUS + 1, getX() - 10); }

    private int slotWidth(int start, int end) {
        int width = 0;
        for (Marker marker : shownMarkers) {
            if (marker.start() < 0) continue;
            int offset = markerOffset(marker);
            if (offset >= start && offset < end) width += MARKER_WIDTH;
        }
        return width;
    }

    private int textX(Line line, int position) {
        return font.width(getValue().substring(line.start(), position))
            + slotWidth(line.start(), Math.min(position + 1, line.end()));
    }

    public MarkerPosition markerPosition(Marker marker) {
        layout();
        if (marker.start() < 0) return new MarkerPosition(wholeMarkerX(), getY() + 8,
            shownMarkers.stream().anyMatch(shown -> shown.target().equals(marker.target())));
        int offset = markerOffset(marker);
        int row = rowAt(offset);
        Line line = lines.get(row);
        int x = font.width(getValue().substring(line.start(), offset));
        for (Marker shown : shownMarkers) {
            if (shown.target().equals(marker.target())) break;
            if (shown.start() < 0) continue;
            int before = markerOffset(shown);
            if (before >= line.start() && before <= offset) x += MARKER_WIDTH;
        }
        return new MarkerPosition(getX() + 4 + x + MARKER_RADIUS, getY() + 8 + (row - firstRow) * LINE_HEIGHT,
            shownMarkers.stream().anyMatch(shown -> shown.target().equals(marker.target()))
                && row >= firstRow && row < firstRow + visibleRows());
    }

    public @Nullable Marker markerAt(double x, double y) {
        layout();
        for (Marker marker : shownMarkers) {
            MarkerPosition point = markerPosition(marker);
            if (point.visible() && x >= point.x() - MARKER_RADIUS && x <= point.x() + MARKER_RADIUS
                    && y >= point.y() - MARKER_RADIUS && y <= point.y() + MARKER_RADIUS) return marker;
        }
        return null;
    }

    private int hoveredTextPosition(int mouseX, int mouseY) {
        layout();
        int localY = mouseY - getY() - 4;
        int localX = mouseX - getX() - 4;
        if (!isMouseOver(mouseX, mouseY) || localY < 0 || localX < 0
                || localY % LINE_HEIGHT >= 9) return -1;
        int row = firstRow + localY / LINE_HEIGHT;
        if (row >= lines.size() || row >= firstRow + visibleRows()) return -1;
        Line line = lines.get(row);
        if (localX >= textX(line, line.end())) return -1;
        return positionAt(row, localX);
    }

    /** Keep the original hover region while inserting its slot, so reflow cannot flicker it away. */
    public void updateMarkerHover(int mouseX, int mouseY) {
        layout();
        if (hoveredTarget != null && mouseX >= hoverLeft && mouseX < hoverRight
                && mouseY >= hoverTop && mouseY < hoverBottom) return;
        Marker hovered = markerAt(mouseX, mouseY);
        int position = hoveredTextPosition(mouseX, mouseY);
        if (hovered == null && getValue().equals(markerCommand)) {
            for (Marker marker : markers) {
                boolean whole = marker.start() < 0 && mouseX >= wholeMarkerX() - MARKER_RADIUS
                    && mouseX <= wholeMarkerX() + MARKER_RADIUS
                    && mouseY >= getY() + 4 && mouseY <= getY() + 12;
                if (whole || (marker.start() >= 0 && position >= marker.start() && position < marker.end())) {
                    hovered = marker;
                    break;
                }
            }
        }
        if (hovered == null) {
            hoveredTarget = null;
        } else if (hovered.start() < 0) {
            hoveredTarget = hovered.target();
            hoverLeft = wholeMarkerX() - MARKER_RADIUS;
            hoverRight = wholeMarkerX() + MARKER_RADIUS + 1;
            hoverTop = getY() + 4;
            hoverBottom = getY() + 13;
        } else {
            int row = firstRow + Math.max(0, (mouseY - getY() - 4) / LINE_HEIGHT);
            Line line = lines.get(Math.min(row, lines.size() - 1));
            int start = Math.max(line.start(), markerOffset(hovered));
            int end = Math.max(start, Math.min(line.end(), hovered.end()));
            hoverLeft = getX() + (hovered.start() < 0 ? 0 : 4 + textX(line, start));
            hoverRight = Math.max(hoverLeft + MARKER_WIDTH, getX() + 4 + textX(line, end));
            hoverTop = getY() + 4 + (row - firstRow) * LINE_HEIGHT;
            hoverBottom = hoverTop + 9;
            hoveredTarget = hovered.target();
        }
        layout();
    }

    private void renderMarkers(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Marker hovered = markerAt(mouseX, mouseY);
        for (Marker marker : shownMarkers) {
            MarkerPosition point = markerPosition(marker);
            if (!point.visible()) continue;
            int color = marker.enabled() ? DebuggerTheme.RED : DebuggerTheme.MUTED;
            BreakpointUi.icon(marker.definition()).drawSmall(graphics,
                point.x() - MARKER_RADIUS, point.y() - MARKER_RADIUS, color);
            if (marker.target().equals(focusedTarget))
                graphics.outline(point.x() - 6, point.y() - 6, 13, 13, DebuggerTheme.TEAL);
            if (marker == hovered || marker.target().equals(focusedTarget)) {
                String label = marker.target().wholeCommand()
                    ? Component.translatable("codon.breakpoint.block_stop").getString()
                    : Component.translatable("codon.breakpoint.stage_target", marker.target().stageIndex() + 1).getString();
                if (marker.definition() != null) label += " · " + BreakpointUi.condition(marker.definition().condition());
                graphics.setTooltipForNextFrame(font, font.split(Component.literal(label).append("\n")
                    .append(Component.translatable(marker.target().equals(focusedTarget)
                        ? "codon.breakpoint.inline_keyboard_help" : "codon.breakpoint.inline_help")),
                    Math.min(240, graphics.guiWidth() - 24)),
                    marker.target().equals(focusedTarget) ? point.x() : mouseX,
                    marker.target().equals(focusedTarget) ? point.y() : mouseY);
            }
        }
    }

    public WrappedCommandEditBox(Font font, int x, int y, int width, int height, Component label) {
        super(font, x, y, width, height, label);
        this.font = font;
    }

    private int visibleRows() { return Math.max(1, (getHeight() - 8) / LINE_HEIGHT); }

    private void layout() {
        String value = getValue();
        int width = Math.max(1, getWidth() - 12);
        if (!value.equals(laidOutValue)) hoveredTarget = null;
        shownMarkers = value.equals(markerCommand) ? markers.stream()
            .filter(marker -> marker.target().wholeCommand() || marker.enabled() || marker.target().equals(hoveredTarget)
                || marker.target().equals(focusedTarget))
            .sorted(java.util.Comparator.comparingInt(this::markerOffset)).toList() : List.of();
        List<BreakpointTarget> visibleTargets = shownMarkers.stream().map(Marker::target).toList();
        boolean changed = !value.equals(laidOutValue) || width != laidOutWidth || !visibleTargets.equals(laidOutMarkers);
        if (changed) {
            lines.clear();
            int start = 0;
            while (start < value.length()) {
                String fitting = font.plainSubstrByWidth(value.substring(start), width);
                int end = start + fitting.length();
                while (end > start && font.width(value.substring(start, end)) + slotWidth(start, end) > width)
                    end = value.offsetByCodePoints(end, -1);
                if (end == start) end = value.offsetByCodePoints(start, 1);
                if (end < value.length()) {
                    int space = value.lastIndexOf(' ', end - 1);
                    if (space > start) end = space + 1;
                }
                lines.add(new Line(start, end));
                start = end;
            }
            if (lines.isEmpty()) lines.add(new Line(0, 0));
            laidOutValue = value;
            laidOutWidth = width;
            laidOutMarkers = visibleTargets;
        }
        int cursor = getCursorPosition();
        if (changed || cursor != lastCursor) {
            int row = rowAt(cursor);
            if (row < firstRow) firstRow = row;
            if (row >= firstRow + visibleRows()) firstRow = row - visibleRows() + 1;
            lastCursor = cursor;
        }
        if (focusedTarget != null) {
            markers.stream().filter(marker -> marker.target().equals(focusedTarget)).findFirst().ifPresent(marker -> {
                int row = rowAt(markerOffset(marker));
                if (row < firstRow) firstRow = row;
                if (row >= firstRow + visibleRows()) firstRow = row - visibleRows() + 1;
            });
        }
        firstRow = Math.clamp(firstRow, 0, Math.max(0, lines.size() - visibleRows()));
    }

    private int rowAt(int position) {
        for (int row = 0; row < lines.size() - 1; row++) {
            if (position < lines.get(row).end()) return row;
        }
        return lines.size() - 1;
    }

    private int positionAt(int row, double localX) {
        Line line = lines.get(Math.clamp(row, 0, lines.size() - 1));
        int position = line.start();
        while (position < line.end()) {
            int next = getValue().offsetByCodePoints(position, 1);
            int textEnd = textX(line, position) + font.width(getValue().substring(position, next));
            if (localX < textEnd) return position;
            position = next;
        }
        return line.end();
    }

    private int clickedPosition(MouseButtonEvent event) {
        layout();
        int row = firstRow + (int) Math.floor((event.y() - getY() - 4) / LINE_HEIGHT);
        return positionAt(row, event.x() - getX() - 4);
    }

    @Override public void onClick(MouseButtonEvent event, boolean doubleClick) {
        moveCursorTo(clickedPosition(event), event.hasShiftDown());
        if (doubleClick) {
            int end = getWordPosition(1);
            moveCursorTo(getWordPosition(-1), false);
            moveCursorTo(end, true);
        }
    }

    @Override protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        moveCursorTo(clickedPosition(event), true);
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (isActive() && isFocused()
                && (event.key() == InputConstants.KEY_UP || event.key() == InputConstants.KEY_DOWN)) {
            layout();
            int row = rowAt(getCursorPosition());
            int x = textX(lines.get(row), getCursorPosition());
            moveCursorTo(positionAt(row + (event.key() == InputConstants.KEY_UP ? -1 : 1), x), event.hasShiftDown());
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (!isMouseOver(x, y)) return false;
        layout();
        hoveredTarget = null;
        firstRow = Math.clamp(firstRow - (int) Math.signum(scrollY) * 2, 0,
            Math.max(0, lines.size() - visibleRows()));
        return true;
    }

    @Override public int getScreenX(int charIndex) {
        layout();
        int index = Math.clamp(charIndex, 0, getValue().length());
        return getX() + 4 + textX(lines.get(rowAt(index)), index);
    }

    private void renderSegment(GuiGraphicsExtractor graphics, EditBoxAccessor access, Line line,
                               int start, int end, int y, int selectedStart, int selectedEnd) {
        if (start >= end) return;
        int x = getX() + 4 + textX(line, start);
        graphics.text(font, access.codon$applyFormat(getValue().substring(start, end), start), x, y, DEFAULT_TEXT_COLOR);
        int from = Math.max(start, selectedStart);
        int to = Math.min(end, selectedEnd);
        if (from < to) {
            int x1 = x + font.width(getValue().substring(start, from));
            int x2 = x + font.width(getValue().substring(start, to));
            graphics.textHighlight(x1, y, x2, y + 9, true);
        }
    }

    @Override public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (!isVisible()) return;
        updateMarkerHover(mouseX, mouseY);
        layout();
        EditBoxAccessor access = (EditBoxAccessor) (Object) this;
        graphics.fill(getX(), getY(), getRight(), getBottom(), 0xFF101010);
        graphics.outline(getX(), getY(), getWidth(), getHeight(), isFocused() ? 0xFFFFFFFF : 0xFFA0A0A0);
        graphics.enableScissor(getX() + 2, getY() + 2, getRight() - 2, getBottom() - 2);
        int cursor = getCursorPosition();
        int selectedStart = Math.min(cursor, access.codon$highlightPos());
        int selectedEnd = Math.max(cursor, access.codon$highlightPos());
        for (int row = firstRow; row < Math.min(lines.size(), firstRow + visibleRows()); row++) {
            Line line = lines.get(row);
            int y = getY() + 4 + (row - firstRow) * LINE_HEIGHT;
            int segmentStart = line.start();
            for (Marker marker : shownMarkers) {
                int offset = markerOffset(marker);
                if (offset > segmentStart && offset < line.end()) {
                    renderSegment(graphics, access, line, segmentStart, offset, y, selectedStart, selectedEnd);
                    segmentStart = offset;
                }
            }
            renderSegment(graphics, access, line, segmentStart, line.end(), y, selectedStart, selectedEnd);
            if (isFocused() && row == rowAt(cursor)) {
                int x = getScreenX(cursor);
                if ((System.currentTimeMillis() / 500) % 2 == 0)
                    graphics.fill(x, y - 1, x + 1, y + 9, 0xFFFFFFFF);
                var preedit = access.codon$preeditOverlay();
                if (preedit != null) {
                    preedit.updateInputPosition(x, y);
                    graphics.setPreeditOverlay(preedit);
                }
            }
        }
        if (lines.size() > visibleRows()) {
            int track = getHeight() - 4;
            int thumb = Math.max(3, track * visibleRows() / lines.size());
            int y = getY() + 2 + (track - thumb) * firstRow / (lines.size() - visibleRows());
            graphics.fill(getRight() - 3, y, getRight() - 2, y + thumb, 0xFFA0A0A0);
        }
        graphics.disableScissor();
        renderMarkers(graphics, mouseX, mouseY);
        if (isHovered()) graphics.requestCursor(CursorTypes.IBEAM);
    }
}
