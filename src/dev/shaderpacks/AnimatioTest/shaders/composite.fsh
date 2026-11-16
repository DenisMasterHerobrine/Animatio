#version 120

// Darkens every pixel the shadow map says is occluded from the sun: models must show up in the shadow pass to
// cast a shadow here (the tilted sun path throws the shadows sideways at noon).

const int shadowMapResolution = 2048;
const float shadowDistance = 32.0;
const float sunPathRotation = 35.0;

uniform sampler2D colortex0;
uniform sampler2D depthtex0;
uniform sampler2D shadowtex0;
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;

varying vec2 texcoord;

void main() {
    vec3 color = texture2D(colortex0, texcoord).rgb;
    float depth = texture2D(depthtex0, texcoord).r;
    if (depth < 1.0) {
        vec4 view = gbufferProjectionInverse * vec4(vec3(texcoord, depth) * 2.0 - 1.0, 1.0);
        view /= view.w;
        vec4 shadowClip = shadowProjection * (shadowModelView * (gbufferModelViewInverse * view));
        vec3 shadowPos = shadowClip.xyz / shadowClip.w * 0.5 + 0.5;
        if (all(greaterThan(shadowPos, vec3(0.0))) && all(lessThan(shadowPos, vec3(1.0)))
                && texture2D(shadowtex0, shadowPos.xy).r < shadowPos.z - 0.001) {
            color *= 0.45;
        }
    }
/* DRAWBUFFERS:0 */
    gl_FragData[0] = vec4(color, 1.0);
}
