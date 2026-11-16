#version 150

// Animatio GPU skinning. Import from custom material shaders with:
//   #moj_import <animatio:skinning.glsl>
//
// Per draw: AnimatioInstanceBase (int uniform) is the first instance record of the draw.
// Per instance record (8 texels in AnimatioInstances):
//   0-3  model matrix (camera-relative, column-major)
//   4    light uv, overlay uv (packed like vanilla UV2 / UV1)
//   5    tint rgba
//   6    palette offset, emissive, first morph weight, unused
//   7    four free parameters (MaterialOverride.withParams)
// Bones: 3 texels per bone in AnimatioBones (rows of an affine 3x4 matrix).
// Morph targets: per draw, AnimatioMorphTargets targets (0 = none); deltas in AnimatioMorphs (2 texels per target and
// vertex: position, normal), weights in AnimatioMorphWeights (4 per texel). Call animatio_morph before skinning.

uniform samplerBuffer AnimatioBones;
uniform samplerBuffer AnimatioInstances;
uniform samplerBuffer AnimatioMorphWeights;
uniform samplerBuffer AnimatioMorphs;
uniform int AnimatioInstanceBase;
uniform int AnimatioMorphBase;
uniform int AnimatioMorphTargets;
uniform int AnimatioMorphVertices;
uniform int AnimatioMorphFirstVertex;
uniform int AnimatioMorphWeightOffset;

struct AnimatioInstance {
    mat4 model;
    ivec2 light;
    ivec2 overlay;
    vec4 tint;
    int palette;
    float emissive;
    int morphWeights;
    vec4 params;
};

AnimatioInstance animatio_instance() {
    int base = (AnimatioInstanceBase + gl_InstanceID) * 8;
    AnimatioInstance inst;
    inst.model = mat4(texelFetch(AnimatioInstances, base), texelFetch(AnimatioInstances, base + 1),
                      texelFetch(AnimatioInstances, base + 2), texelFetch(AnimatioInstances, base + 3));
    vec4 lo = texelFetch(AnimatioInstances, base + 4);
    inst.light = ivec2(lo.xy);
    inst.overlay = ivec2(lo.zw);
    inst.tint = texelFetch(AnimatioInstances, base + 5);
    vec4 misc = texelFetch(AnimatioInstances, base + 6);
    inst.palette = int(misc.x);
    inst.emissive = misc.y;
    inst.morphWeights = int(misc.z);
    inst.params = texelFetch(AnimatioInstances, base + 7);
    return inst;
}

mat4 animatio_bone(int index) {
    int b = index * 3;
    return transpose(mat4(texelFetch(AnimatioBones, b), texelFetch(AnimatioBones, b + 1),
                          texelFetch(AnimatioBones, b + 2), vec4(0.0, 0.0, 0.0, 1.0)));
}

// Adds the instance's weighted morph target deltas to a vertex's position and normal.
void animatio_morph(AnimatioInstance inst, inout vec3 position, inout vec3 normal) {
    if (AnimatioMorphTargets <= 0) return;
    int vertex = gl_VertexID - AnimatioMorphFirstVertex;
    int first = inst.morphWeights + AnimatioMorphWeightOffset;
    for (int t = 0; t < AnimatioMorphTargets; t++) {
        int wi = first + t;
        float w = texelFetch(AnimatioMorphWeights, wi / 4)[wi % 4];
        if (w == 0.0) continue;
        int texel = AnimatioMorphBase + (t * AnimatioMorphVertices + vertex) * 2;
        position += w * texelFetch(AnimatioMorphs, texel).xyz;
        normal += w * texelFetch(AnimatioMorphs, texel + 1).xyz;
    }
}

// Linear blend skinning matrix for a vertex.
mat4 animatio_skin_matrix(AnimatioInstance inst, ivec4 joints, vec4 weights) {
    mat4 skin = animatio_bone(inst.palette + joints.x) * weights.x;
    if (weights.y > 0.0) skin += animatio_bone(inst.palette + joints.y) * weights.y;
    if (weights.z > 0.0) skin += animatio_bone(inst.palette + joints.z) * weights.z;
    if (weights.w > 0.0) skin += animatio_bone(inst.palette + joints.w) * weights.w;
    return skin;
}
