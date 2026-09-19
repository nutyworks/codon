package works.nuty.codon.client.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntConsumer;

/** Pointer capture for the thin HUD scrollbars; geometry matches their rendered thumbs. */
final class ScrollbarInput {
    private final Map<String, Track> tracks = new LinkedHashMap<>();
    private String captured;
    private double grab;

    void beginFrame() { tracks.clear(); }
    void endFrame() { if (!tracks.containsKey(captured)) release(); }

    void add(String id, boolean horizontal, int x, int y, int length, int thickness,
             int thumb, int offset, int maximum, IntConsumer setter) {
        if (maximum <= 0 || length <= 1) return;
        tracks.put(id, new Track(horizontal, x, y, length, thickness, thumb, offset, maximum, setter));
    }

    boolean click(double x, double y) {
        release();
        for (var entry : tracks.entrySet()) {
            Track t = entry.getValue();
            double axis = t.horizontal ? x - t.x : y - t.y;
            double cross = t.horizontal ? y - t.y : x - t.x;
            if (axis < 0 || axis >= t.length || cross < -1 || cross >= t.thickness + 2) continue;
            if (t.length <= t.thumb) continue;
            captured = entry.getKey();
            int top = (int) ((long) (t.length - t.thumb) * t.offset / t.maximum);
            grab = axis >= top && axis < top + t.thumb ? axis - top : t.thumb / 2.0;
            if (axis < top || axis >= top + t.thumb) drag(x, y);
            return true;
        }
        return false;
    }

    boolean drag(double x, double y) {
        Track t = tracks.get(captured);
        if (t == null) { release(); return false; }
        double axis = t.horizontal ? x - t.x : y - t.y;
        int travel = t.length - t.thumb;
        if (travel > 0) t.setter.accept((int) Math.round(Math.clamp((axis - grab) / travel, 0, 1) * t.maximum));
        return true;
    }

    boolean release() {
        boolean active = captured != null;
        captured = null;
        return active;
    }

    private record Track(boolean horizontal, int x, int y, int length, int thickness,
                         int thumb, int offset, int maximum, IntConsumer setter) { }
}
