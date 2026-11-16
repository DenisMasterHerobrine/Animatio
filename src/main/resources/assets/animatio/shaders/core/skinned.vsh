#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>
#moj_import <animatio:skinning.glsl>

in vec3 Position;
in vec2 UV0;
in vec3 Normal;
in ivec4 Joints;
in vec4 Weights;
in vec4 Color;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out vec3 modelPos;
flat out vec4 animatioParams;
flat out float animatioEmissive;

void main() {
    AnimatioInstance inst = animatio_instance();
    vec3 position = Position;
    vec3 morphedNormal = Normal;
    animatio_morph(inst, position, morphedNormal);
    mat4 skin = animatio_skin_matrix(inst, Joints, Weights);
    vec4 skinned = skin * vec4(position, 1.0);
    vec4 pos = inst.model * skinned;
    vec3 normal = normalize(mat3(inst.model) * (mat3(skin) * morphedNormal));

    gl_Position = ProjMat * ModelViewMat * pos;
    vertexDistance = fog_distance(pos.xyz, FogShape);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normal, Color * inst.tint);
    lightMapColor = texelFetch(Sampler2, inst.light / 16, 0);
    overlayColor = texelFetch(Sampler1, inst.overlay, 0);
    texCoord0 = UV0;
    modelPos = skinned.xyz;
    animatioParams = inst.params;
    animatioEmissive = inst.emissive;
}
