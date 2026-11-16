// Shared vertex stage of the entity and block-entity programs: passes the attributes the fragment stage
// visualises (normal, tangent with handedness, mid texture coordinate).

attribute vec4 at_tangent;
attribute vec4 mc_midTexCoord;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 normal;
varying vec4 tangent;
varying vec2 midcoord;

void main() {
    gl_Position = ftransform();
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    glcolor = gl_Color;
    normal = normalize(gl_NormalMatrix * gl_Normal);
    tangent = vec4(gl_NormalMatrix * at_tangent.xyz, at_tangent.w);
    midcoord = mc_midTexCoord.xy;
}
