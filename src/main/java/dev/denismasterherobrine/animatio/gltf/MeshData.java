package dev.denismasterherobrine.animatio.gltf;

import org.jetbrains.annotations.Nullable;

/**
 * One drawable primitive, flattened to a triangle list. Every vertex is skinned to up to four palette entries
 * (rigid meshes use a single entry with weight 1), so all meshes share one skinning path.
 *
 * @param positions 3 floats per vertex, in the space the palette matrices expect
 * @param normals   3 floats per vertex
 * @param uvs       2 floats per vertex (glTF convention: origin at the top-left of the image)
 * @param colors    4 floats per vertex, or {@code null}
 * @param joints    4 palette indices per vertex
 * @param weights   4 weights per vertex, summing to 1
 * @param indices   3 per triangle
 * @param material  index into {@link ModelData#materials()}, or -1 for the default material
 */
public record MeshData(String name, int material, int vertexCount, float[] positions, float[] normals, float[] uvs,
                       @Nullable float[] colors, int[] joints, float[] weights, int[] indices) {
    public int triangleCount() {
        return indices.length / 3;
    }
}
