package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntConsumer;

/** Shared edge capture. Preview stays local; only a completed drag writes preferences. */
final class PanelResizeInput {
    private record Edge(Bounds panel, boolean left, int minimum, int maximum, IntConsumer save) {
        int x() { return left ? panel.x() : panel.x() + panel.width() - 1; }
        boolean contains(double x, double y) {
            // Stay clear of row hitboxes and the inspector's inset scrollbar.
            return x >= x() - (left ? 2 : 0) && x < x() + 3 && y >= panel.y() && y < panel.y() + panel.height();
        }
    }

    private final Map<String, Edge> edges = new LinkedHashMap<>();
    private String captured;
    private Edge start;
    private double startX;
    private int previewWidth;
    private boolean swallowRelease;
    private int screenWidth = -1;
    private int screenHeight = -1;

    void beginFrame(int width, int height) {
        if (width != screenWidth || height != screenHeight) cancel();
        screenWidth = width;
        screenHeight = height;
        edges.clear();
    }

    void endFrame() { if (captured != null && !edges.containsKey(captured)) cancel(); }

    int requestedWidth(String id, int saved) { return id.equals(captured) ? previewWidth : saved; }

    void add(String id, Bounds panel, boolean left, int minimum, int maximum, IntConsumer save) {
        if (panel.height() > 0 && maximum > minimum) edges.put(id, new Edge(panel, left, minimum, maximum, save));
    }

    boolean click(double x, double y) {
        cancel();
        swallowRelease = false; // A fresh press starts a new gesture even after a lost release.
        for (var entry : edges.entrySet()) {
            Edge edge = entry.getValue();
            if (!edge.contains(x, y)) continue;
            captured = entry.getKey();
            start = edge;
            startX = x;
            previewWidth = edge.panel.width();
            return true;
        }
        return false;
    }

    boolean drag(double x) {
        if (captured == null) return swallowRelease;
        double requested = start.panel.width() + (start.left ? startX - x : x - startX);
        previewWidth = (int) Math.round(Math.clamp(requested, start.minimum, start.maximum));
        return true;
    }

    boolean release() {
        if (captured == null) {
            boolean consumed = swallowRelease;
            swallowRelease = false;
            return consumed;
        }
        Edge completed = start;
        int width = previewWidth;
        captured = null;
        start = null;
        swallowRelease = false;
        if (width != completed.panel.width()) completed.save.accept(width);
        return true;
    }

    boolean cancel() {
        boolean active = captured != null;
        swallowRelease |= active;
        captured = null;
        start = null;
        return active;
    }

    void paint(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        for (var entry : edges.entrySet()) {
            Edge edge = entry.getValue();
            boolean active = entry.getKey().equals(captured);
            if (!active && !edge.contains(mouseX, mouseY)) continue;
            graphics.fill(edge.x(), edge.panel.y(), edge.x() + 1, edge.panel.y() + edge.panel.height(), DebuggerTheme.MUTED);
            graphics.requestCursor(CursorTypes.RESIZE_EW);
            if (!active) graphics.setTooltipForNextFrame(font, Component.translatable("codon.ui.resize_panel"), mouseX, mouseY);
        }
    }
}
