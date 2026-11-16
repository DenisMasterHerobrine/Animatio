package dev.denismasterherobrine.animatio.gltf;

import org.jetbrains.annotations.Nullable;

/**
 * One drawable primitive, flattened to a triangle list. Every vertex is skinned to up to four palette entries
 * (rigid meshes use a single entry with weight 1), so all meshes share one skinning path. Influences are sorted by
 * weight, so a vertex with {@code weights[v*4+1] == 0} is rigid on {@code joints[v*4]}.
 * <p>
 * Besides the glTF-shaped arrays, the constructor derives the layouts the skinning kernels stream through:
 * {@linkplain #spans() spans} of consecutive vertices that share one rigid joint, packed vertex colours, and a sparse
 * list of the vertices each morph target moves.
 */
public final class MeshData {
    private final String name;
    private final int material;
    private final int vertexCount;
    private final float[] positions;
    private final float[] normals;
    private final float[] uvs;
    private final @Nullable float[] colors;
    private final int[] joints;
    private final float[] weights;
    private final int[] indices;

    private final int morphTargets;
    private final int morphWeightOffset;
    private final @Nullable float[] morphPositions;
    private final @Nullable float[] morphNormals;

    private final int[] spans;
    private final @Nullable int[] packedColors;
    private final int[] morphStart;
    private final int[] morphVertex;
    private final float[] morphDelta;

    /** A mesh without morph targets. */
    public MeshData(String name, int material, int vertexCount, float[] positions, float[] normals, float[] uvs,
                    @Nullable float[] colors, int[] joints, float[] weights, int[] indices) {
        this(name, material, vertexCount, positions, normals, uvs, colors, joints, weights, indices, 0, 0, null, null);
    }

    /**
     * @param positions         3 floats per vertex, in the space the palette matrices expect
     * @param normals           3 floats per vertex
     * @param uvs               2 floats per vertex (glTF convention: origin at the top-left of the image)
     * @param colors            4 floats per vertex, or {@code null}
     * @param joints            4 palette indices per vertex
     * @param weights           4 weights per vertex, summing to 1
     * @param indices           3 per triangle
     * @param material          index into {@link ModelData#materials()}, or -1 for the default material
     * @param morphTargets      number of morph targets
     * @param morphWeightOffset first slot of this mesh's weights in a pose's morph weights
     * @param morphPositions    position deltas, target-major ({@code [target][vertex][xyz]}), or {@code null}
     * @param morphNormals      normal deltas in the same layout, or {@code null}
     */
    public MeshData(String name, int material, int vertexCount, float[] positions, float[] normals, float[] uvs,
                    @Nullable float[] colors, int[] joints, float[] weights, int[] indices,
                    int morphTargets, int morphWeightOffset, @Nullable float[] morphPositions, @Nullable float[] morphNormals) {
        this.name = name;
        this.material = material;
        this.vertexCount = vertexCount;
        this.positions = positions;
        this.normals = normals;
        this.uvs = uvs;
        this.colors = colors;
        this.joints = joints;
        this.weights = weights;
        this.indices = indices;
        this.morphTargets = morphTargets;
        this.morphWeightOffset = morphWeightOffset;
        this.morphPositions = morphTargets > 0 ? morphPositions : null;
        this.morphNormals = morphTargets > 0 ? morphNormals : null;
        if (morphTargets > 0 && (morphPositions == null || morphPositions.length != morphTargets * vertexCount * 3)) {
            throw new IllegalArgumentException("Morph position deltas must hold " + morphTargets + " x " + vertexCount + " vectors");
        }
        if (this.morphNormals != null && this.morphNormals.length != morphTargets * vertexCount * 3) {
            throw new IllegalArgumentException("Morph normal deltas have the wrong length");
        }
        this.spans = buildSpans(vertexCount, joints, weights);
        this.packedColors = packColors(vertexCount, colors);

        // sparse morph deltas: per target, the vertices it moves (position and normal delta, 6 floats each)
        this.morphStart = new int[morphTargets + 1];
        int entries = 0;
        for (int t = 0; t < morphTargets; t++) {
            morphStart[t] = entries;
            for (int v = 0; v < vertexCount; v++) if (moves(t, v)) entries++;
        }
        morphStart[morphTargets] = entries;
        this.morphVertex = new int[entries];
        this.morphDelta = new float[entries * 6];
        int e = 0;
        for (int t = 0; t < morphTargets; t++) {
            for (int v = 0; v < vertexCount; v++) {
                if (!moves(t, v)) continue;
                morphVertex[e] = v;
                int o = (t * vertexCount + v) * 3;
                System.arraycopy(this.morphPositions, o, morphDelta, e * 6, 3);
                if (this.morphNormals != null) System.arraycopy(this.morphNormals, o, morphDelta, e * 6 + 3, 3);
                e++;
            }
        }
    }

    private boolean moves(int target, int v) {
        int o = (target * vertexCount + v) * 3;
        float[] p = morphPositions, n = morphNormals;
        if (p[o] != 0f || p[o + 1] != 0f || p[o + 2] != 0f) return true;
        return n != null && (n[o] != 0f || n[o + 1] != 0f || n[o + 2] != 0f);
    }

    /** Runs of vertices: (start, end, joint) triples; joint -1 marks a run of blended vertices. */
    private static int[] buildSpans(int n, int[] joints, float[] weights) {
        int[] out = new int[Math.max(3, n * 3)];
        int count = 0;
        int v = 0;
        while (v < n) {
            int joint = rigidJoint(v, joints, weights);
            int end = v + 1;
            while (end < n && rigidJoint(end, joints, weights) == joint) end++;
            out[count++] = v;
            out[count++] = end;
            out[count++] = joint;
            v = end;
        }
        return java.util.Arrays.copyOf(out, count);
    }

    /** The joint a vertex follows rigidly (one influence), or -1 if it blends several. */
    private static int rigidJoint(int v, int[] joints, float[] weights) {
        int o = v * 4;
        return weights[o] > 0f && weights[o + 1] <= 0f && weights[o + 2] <= 0f && weights[o + 3] <= 0f ? joints[o] : -1;
    }

    private static @Nullable int[] packColors(int n, @Nullable float[] colors) {
        if (colors == null) return null;
        int[] out = new int[n];
        for (int v = 0; v < n; v++) {
            int r = unorm(colors[v * 4]), g = unorm(colors[v * 4 + 1]), b = unorm(colors[v * 4 + 2]), a = unorm(colors[v * 4 + 3]);
            out[v] = a << 24 | r << 16 | g << 8 | b; // ARGB
        }
        return out;
    }

    private static int unorm(float f) {
        return (int) (Math.max(0f, Math.min(1f, f)) * 255f);
    }

    public String name() {
        return name;
    }

    public int material() {
        return material;
    }

    public int vertexCount() {
        return vertexCount;
    }

    public float[] positions() {
        return positions;
    }

    public float[] normals() {
        return normals;
    }

    public float[] uvs() {
        return uvs;
    }

    public @Nullable float[] colors() {
        return colors;
    }

    public int[] joints() {
        return joints;
    }

    public float[] weights() {
        return weights;
    }

    public int[] indices() {
        return indices;
    }

    public int triangleCount() {
        return indices.length / 3;
    }

    public int morphTargetCount() {
        return morphTargets;
    }

    public boolean hasMorphs() {
        return morphTargets > 0;
    }

    /** First slot of this mesh's weights in {@code Pose.m}. */
    public int morphWeightOffset() {
        return morphWeightOffset;
    }

    /** Dense position deltas ({@code [target][vertex][xyz]}), or {@code null}. Do not modify. */
    public @Nullable float[] morphPositions() {
        return morphPositions;
    }

    /** Dense normal deltas, or {@code null} when no target changes normals. Do not modify. */
    public @Nullable float[] morphNormals() {
        return morphNormals;
    }

    /** Vertex runs for the skinning kernel: (start, end, joint) triples, joint -1 for blended vertices. */
    public int[] spans() {
        return spans;
    }

    /** Vertex colours as ARGB ints, or {@code null}. */
    public @Nullable int[] packedColors() {
        return packedColors;
    }

    /** Sparse morph data: target {@code t} moves entries {@code morphStart[t]..morphStart[t+1]}. */
    public int[] morphStart() {
        return morphStart;
    }

    /** Vertex of each sparse morph entry. */
    public int[] morphVertex() {
        return morphVertex;
    }

    /** Position (xyz) and normal (xyz) delta of each sparse morph entry. */
    public float[] morphDelta() {
        return morphDelta;
    }
}
