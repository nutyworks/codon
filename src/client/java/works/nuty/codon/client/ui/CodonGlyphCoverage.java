package works.nuty.codon.client.ui;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** Adds half a framebuffer pixel of sampling room around a glyph, preserving its UV mapping. */
public final class CodonGlyphCoverage implements VertexConsumer {
    private final VertexConsumer target;
    private final float margin;
    private final Vertex[] quad = new Vertex[4];
    private int count;

    public CodonGlyphCoverage(VertexConsumer target, double gameScale) {
        this.target = target;
        margin = (float) (0.5 / gameScale);
    }

    @Override public VertexConsumer addVertex(float x, float y, float z) {
        if (count == 4) finish();
        quad[count++] = new Vertex(x, y, z);
        return this;
    }

    private Vertex current() { return quad[count - 1]; }
    @Override public VertexConsumer setColor(int color) { current().color = color; return this; }
    @Override public VertexConsumer setColor(int r, int g, int b, int a) { return setColor(a << 24 | r << 16 | g << 8 | b); }
    @Override public VertexConsumer setUv(float u, float v) { current().u = u; current().v = v; return this; }
    @Override public VertexConsumer setUv1(int u, int v) { current().overlay = new int[]{u, v}; return this; }
    @Override public VertexConsumer setUv2(int u, int v) { current().light = new int[]{u, v}; return this; }
    @Override public VertexConsumer setUv3(float u, float v) { current().uv3 = new float[]{u, v}; return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { current().normal = new float[]{x, y, z}; return this; }
    @Override public VertexConsumer setLineWidth(float width) { current().lineWidth = width; return this; }

    public void finish() {
        if (count == 0) return;
        // Sheet glyphs emit TL, BL, BR, TR, including individual shadow/effect quads.
        var origin = quad[0];
        float ux = count == 4 ? quad[3].x - origin.x : 0;
        float uy = count == 4 ? quad[3].y - origin.y : 0;
        float vx = count == 4 ? quad[1].x - origin.x : 0;
        float vy = count == 4 ? quad[1].y - origin.y : 0;
        double width = Math.hypot(ux, uy), height = Math.hypot(vx, vy);
        boolean expand = count == 4 && width > 0 && height > 0;
        float padU = expand ? (float) (margin / width) : 0;
        float padV = expand ? (float) (margin / height) : 0;
        for (int i = 0; i < count; i++) {
            var vertex = quad[i];
            float a = (i >= 2 ? 1 + padU : -padU);
            float b = (i == 1 || i == 2 ? 1 + padV : -padV);
            float x = expand ? origin.x + a * ux + b * vx : vertex.x;
            float y = expand ? origin.y + a * uy + b * vy : vertex.y;
            float u = expand ? origin.u + a * (quad[3].u - origin.u) + b * (quad[1].u - origin.u) : vertex.u;
            float v = expand ? origin.v + a * (quad[3].v - origin.v) + b * (quad[1].v - origin.v) : vertex.v;
            target.addVertex(x, y, vertex.z).setColor(vertex.color).setUv(u, v);
            if (vertex.overlay != null) target.setUv1(vertex.overlay[0], vertex.overlay[1]);
            if (vertex.light != null) target.setUv2(vertex.light[0], vertex.light[1]);
            if (vertex.uv3 != null) target.setUv3(vertex.uv3[0], vertex.uv3[1]);
            if (vertex.normal != null) target.setNormal(vertex.normal[0], vertex.normal[1], vertex.normal[2]);
            if (vertex.lineWidth != null) target.setLineWidth(vertex.lineWidth);
        }
        count = 0;
    }

    private static final class Vertex {
        final float x, y, z;
        int color = 0xffffffff;
        float u, v;
        int[] overlay, light;
        float[] uv3, normal;
        Float lineWidth;
        Vertex(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
    }
}
