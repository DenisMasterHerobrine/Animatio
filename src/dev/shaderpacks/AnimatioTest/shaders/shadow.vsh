#version 120

// Plain (undistorted) shadow map, enough for the short range of the test scene.

varying vec2 texcoord;
varying vec4 glcolor;

void main() {
    gl_Position = ftransform();
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    glcolor = gl_Color;
}
