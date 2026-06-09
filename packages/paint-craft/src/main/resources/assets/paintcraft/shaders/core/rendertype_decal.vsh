#version 150

#moj_import <fog.glsl>
#moj_import <light.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
uniform float DepthBias;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    // Constant NDC-z offset toward the camera, independent of distance.
    // Multiplying by w converts a constant NDC delta into clip-space units,
    // so the resulting depth-buffer shift is uniform at every range.
    // This eliminates Z-fighting with the underlying block surface without
    // moving geometry in world space (no parallax, no peeking around edges).
    gl_Position.z -= DepthBias * gl_Position.w;

    vertexDistance = fog_distance(Position, FogShape);
    vertexColor = Color * minecraft_sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
}
