#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;

layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;
layout(location = 4) flat out ivec4 glyphBounds;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    texCoord0 = UV0;
    // Mask after shifting: UV2 is signed 16-bit vertex storage.
    glyphBounds = ivec4(UV2.x & 255, UV2.y & 255, (UV2.x >> 8) & 255, (UV2.y >> 8) & 255);
}
