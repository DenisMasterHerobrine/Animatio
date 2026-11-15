package dev.denismasterherobrine.animatio.gltf;

import dev.denismasterherobrine.animatio.anim.model.Clip;
import dev.denismasterherobrine.animatio.anim.model.Skeleton;

import java.util.List;

/**
 * An immutable model loaded from glTF.
 * <p>
 * The skinning palette lists, for every matrix the meshes are skinned with, the node it follows and its inverse
 * bind matrix: {@code palette[i] = global(paletteNodes[i]) * inverseBind[i]}.
 *
 * @param jointBounds per palette entry: min xyz, max xyz of the vertices it influences (mesh space);
 *                    entries no vertex uses have min &gt; max
 */
public record ModelData(String name, Skeleton skeleton, List<Clip> clips, List<MeshData> meshes,
                        List<MaterialData> materials, List<ImageData> images, int[] paletteNodes,
                        float[] paletteInverseBind, float[] jointBounds) {

    public int paletteSize() {
        return paletteNodes.length;
    }

    public Clip clip(String name) {
        for (Clip c : clips) if (c.name().equals(name)) return c;
        return null;
    }

    public MaterialData material(int index) {
        return index >= 0 && index < materials.size() ? materials.get(index) : MaterialData.DEFAULT;
    }

    /** Compute palette matrices (16 floats each) from model-space node matrices. */
    public void computePalette(float[] globals, float[] out) {
        for (int i = 0; i < paletteNodes.length; i++) {
            dev.denismasterherobrine.animatio.anim.math.Mat4.mulAffine(globals, paletteNodes[i] * 16, paletteInverseBind, i * 16, out, i * 16);
        }
    }
}
