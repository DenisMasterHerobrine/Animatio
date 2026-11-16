package dev.denismasterherobrine.animatio.gltf;

import dev.denismasterherobrine.animatio.anim.math.Mat4;
import dev.denismasterherobrine.animatio.anim.model.Clip;
import dev.denismasterherobrine.animatio.anim.model.ModelCamera;
import dev.denismasterherobrine.animatio.anim.model.ModelLight;
import dev.denismasterherobrine.animatio.anim.model.Skeleton;

import java.util.List;

/**
 * An immutable model loaded from glTF.
 * <p>
 * The skinning palette lists, for every matrix the meshes are skinned with, the node it follows and its inverse
 * bind matrix: {@code palette[i] = global(paletteNodes[i]) * inverseBind[i]}.
 *
 * @param loopHints   whether each clip loops by default, when the source file says (Blockbench loop modes); the
 *                    model definition and code configuration override it
 * @param jointBounds per palette entry: min xyz, max xyz of the vertices it influences (mesh space, including the
 *                    reach of morph targets at weights 0..1); entries no vertex uses have min &gt; max
 */
public record ModelData(String name, Skeleton skeleton, List<Clip> clips, List<MeshData> meshes,
                        List<MaterialData> materials, List<ImageData> images, int[] paletteNodes,
                        float[] paletteInverseBind, float[] jointBounds, List<ModelCamera> cameras, List<ModelLight> lights,
                        java.util.Map<String, Boolean> loopHints) {

    public ModelData(String name, Skeleton skeleton, List<Clip> clips, List<MeshData> meshes, List<MaterialData> materials,
                     List<ImageData> images, int[] paletteNodes, float[] paletteInverseBind, float[] jointBounds,
                     List<ModelCamera> cameras, List<ModelLight> lights) {
        this(name, skeleton, clips, meshes, materials, images, paletteNodes, paletteInverseBind, jointBounds, cameras, lights, java.util.Map.of());
    }

    public ModelData(String name, Skeleton skeleton, List<Clip> clips, List<MeshData> meshes, List<MaterialData> materials,
                     List<ImageData> images, int[] paletteNodes, float[] paletteInverseBind, float[] jointBounds) {
        this(name, skeleton, clips, meshes, materials, images, paletteNodes, paletteInverseBind, jointBounds, List.of(), List.of(), java.util.Map.of());
    }

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
        int[] nodes = paletteNodes;
        float[] ibm = paletteInverseBind;
        for (int i = 0, n = nodes.length; i < n; i++) {
            Mat4.mulAffine(globals, nodes[i] * 16, ibm, i * 16, out, i * 16);
        }
    }

    /** True if any mesh has morph targets. */
    public boolean hasMorphs() {
        for (MeshData m : meshes) if (m.hasMorphs()) return true;
        return false;
    }
}
