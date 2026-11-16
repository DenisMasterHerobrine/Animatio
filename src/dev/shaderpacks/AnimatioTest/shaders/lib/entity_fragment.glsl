// Shared fragment stage of the entity and block-entity programs.
//
// DEBUG_VIEW 0 (default): ordinary shading (texture, vertex colour, hurt overlay, lightmap, diffuse light from the
// vertex normal), so models look as they would in a real pack.
//
// DEBUG_VIEW 1: false-colour diagnostics in horizontal stripes (6 pixels each, repeating) showing every input a pack
// reads from entity vertices on every model:
//   stripe 0: textured, shaded with the vertex normal, tinted by the id from entity.properties/block.properties
//             (Animatio robot: red, husk/zombie: green, robot statue: yellow, anything else: blue)
//   stripe 1: the tangent direction as a colour (darker where the handedness is negative; grey = no tangent)
//   stripe 2: red/green = distance of the texture coordinate from mc_midTexCoord, blue = id code
//             (robot 1.0, husk/zombie 0.66, statue 0.33, other 0)
// The autotest turns it on for its last screenshots (shaderpacks/AnimatioTest.txt), or set it in the pack's options.

#define DEBUG_VIEW 0 // [0 1]

uniform sampler2D gtexture;
uniform sampler2D lightmap;
uniform vec4 entityColor;
uniform int entityId;
uniform int blockEntityId;
uniform mat4 gbufferModelViewInverse;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 normal;
varying vec4 tangent;
varying vec2 midcoord;

/** Two-light diffuse like vanilla's entity lighting, in world space. */
float diffuse() {
    vec3 n = normalize(mat3(gbufferModelViewInverse) * normal);
    float l0 = max(dot(n, normalize(vec3(0.2, 1.0, -0.7))), 0.0);
    float l1 = max(dot(n, normalize(vec3(-0.2, 1.0, 0.7))), 0.0);
    return min(1.0, 0.4 + 0.6 * (l0 + l1));
}

vec3 idTint() {
    if (entityId == 100) return vec3(1.0, 0.55, 0.55);
    if (entityId == 101) return vec3(0.55, 1.0, 0.55);
    if (blockEntityId == 200) return vec3(1.0, 1.0, 0.45);
    return vec3(0.55, 0.65, 1.0);
}

float idCode() {
    if (entityId == 100) return 1.0;
    if (entityId == 101) return 0.66;
    if (blockEntityId == 200) return 0.33;
    return 0.0;
}

void main() {
    vec4 color = texture2D(gtexture, texcoord) * glcolor;
    if (color.a < 0.1) discard;
    color.rgb = mix(color.rgb, entityColor.rgb, entityColor.a);
    color *= texture2D(lightmap, lmcoord);
    color.rgb *= diffuse();
#if DEBUG_VIEW == 1
    int stripe = int(mod(floor(gl_FragCoord.y / 6.0), 3.0));
    if (stripe == 0) {
        color.rgb *= idTint();
    } else if (stripe == 1) {
        vec3 t = length(tangent.xyz) > 0.0 ? normalize(tangent.xyz) : vec3(0.0);
        color.rgb = (t * 0.5 + 0.5) * (tangent.w < 0.0 ? 0.6 : 1.0);
    } else {
        color.rgb = vec3(clamp(abs(texcoord - midcoord) * 24.0, 0.0, 1.0), idCode());
    }
#endif
/* DRAWBUFFERS:0 */
    gl_FragData[0] = color;
}
