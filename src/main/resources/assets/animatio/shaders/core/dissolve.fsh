#version 150

#moj_import <fog.glsl>

// Example custom material shader: dissolves the model as AnimatioParams.x goes from 0 to 1,
// with a glowing edge coloured by AnimatioParams.yzw.
// Use: MaterialOverride.none().withShader(animatio:dissolve).withParams(progress, r, g, b)

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in vec3 modelPos;
flat in vec4 animatioParams;
flat in float animatioEmissive;

out vec4 fragColor;

float hash(vec3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

void main() {
    float noise = hash(floor(modelPos * 16.0));
    float progress = animatioParams.x;
    if (noise < progress) {
        discard;
    }
    vec4 color = texture(Sampler0, texCoord0);
    if (color.a < 0.1) {
        discard;
    }
    color *= vertexColor * ColorModulator;
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= lightMapColor;
    if (progress > 0.0 && noise < progress + 0.08) {
        vec3 edge = animatioParams.yzw;
        if (dot(edge, edge) == 0.0) edge = vec3(1.0, 0.55, 0.1);
        color.rgb = edge;
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
