#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) flat in ivec4 glyphBounds;
layout(location = 0) out vec4 fragColor;

void main() {
    // Integrate the texels covered by a framebuffer pixel. Bilinear center
    // sampling can still miss an isolated stroke when dense fonts are minified.
    vec2 size = vec2(textureSize(Sampler0, 0));
    vec2 footprint = max(fwidth(texCoord0) * size, vec2(0.0001));
    vec2 low = texCoord0 * size - footprint * 0.5;
    vec2 high = low + footprint;
    vec4 accumulated = vec4(0.0);
    for (int y = int(floor(low.y)); y < int(ceil(high.y)); y++) {
        for (int x = int(floor(low.x)); x < int(ceil(high.x)); x++) {
            ivec2 texel = ivec2(x, y);
            // Outside this glyph is transparent, including atlas gaps and adjacent glyphs.
            // Retain the full footprint denominator so edge coverage is not brightened.
            if (any(lessThan(texel, glyphBounds.xy)) || any(greaterThan(texel, glyphBounds.zw))) continue;
            vec4 sampleColor = texelFetch(Sampler0, texel, 0);
#ifdef IS_GRAYSCALE
            sampleColor = vec4(1.0, 1.0, 1.0, sampleColor.r);
#endif
            vec2 overlap = max(min(high, vec2(x, y) + 1.0) - max(low, vec2(x, y)), vec2(0.0));
            float weight = overlap.x * overlap.y / (footprint.x * footprint.y);
            // Filter premultiplied colors to retain contrast and color glyph edges.
            accumulated += vec4(sampleColor.rgb * sampleColor.a, sampleColor.a) * weight;
        }
    }
    vec4 texColor = vec4(accumulated.rgb / max(accumulated.a, 0.00001), accumulated.a);
    vec4 color = texColor * vertexColor * ColorModulator;
    if (color.a < 0.1) discard;
    fragColor = color;
}
