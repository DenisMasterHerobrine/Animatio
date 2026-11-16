package dev.denismasterherobrine.animatio.data;

import net.minecraft.resources.ResourceLocation;

/**
 * Where model files live: {@code assets/<namespace>/animatio/models/<path>.glb} (or {@code .gltf}, or a Blockbench
 * project {@code .bbmodel}), with an
 * optional {@code <path>.anim.json} definition next to it.
 */
public final class ModelPaths {
    public static final String DIRECTORY = "animatio/models";

    private ModelPaths() {
    }

    public static ResourceLocation glb(ResourceLocation model) {
        return model.withPath(DIRECTORY + "/" + model.getPath() + ".glb");
    }

    public static ResourceLocation gltf(ResourceLocation model) {
        return model.withPath(DIRECTORY + "/" + model.getPath() + ".gltf");
    }

    public static ResourceLocation bbmodel(ResourceLocation model) {
        return model.withPath(DIRECTORY + "/" + model.getPath() + ".bbmodel");
    }

    /** Load a model file of any supported kind (glTF, GLB or Blockbench project) by its file name. */
    public static dev.denismasterherobrine.animatio.gltf.ModelData load(String id, String fileName, byte[] data, boolean geometry,
                                                                       java.util.function.Function<String, byte[]> resolver)
            throws dev.denismasterherobrine.animatio.gltf.GltfException {
        if (fileName.endsWith(".bbmodel")) return dev.denismasterherobrine.animatio.blockbench.BbmodelLoader.load(id, data, geometry, resolver);
        return dev.denismasterherobrine.animatio.gltf.GltfLoader.load(id, data, geometry, resolver);
    }

    public static ResourceLocation definition(ResourceLocation model) {
        return model.withPath(DIRECTORY + "/" + model.getPath() + ".anim.json");
    }

    /** Model id from a resource path under {@link #DIRECTORY}, or {@code null} if it is not a model file. */
    public static ResourceLocation modelId(ResourceLocation file) {
        String path = file.getPath();
        if (!path.startsWith(DIRECTORY + "/")) return null;
        String rest = path.substring(DIRECTORY.length() + 1);
        if (rest.endsWith(".glb")) return file.withPath(rest.substring(0, rest.length() - 4));
        if (rest.endsWith(".gltf")) return file.withPath(rest.substring(0, rest.length() - 5));
        if (rest.endsWith(".bbmodel")) return file.withPath(rest.substring(0, rest.length() - 8));
        return null;
    }

    /** Resolve a URI relative to a model file (for {@code .gltf} buffers and images). */
    public static ResourceLocation relative(ResourceLocation file, String uri) {
        String path = file.getPath();
        int slash = path.lastIndexOf('/');
        String dir = slash >= 0 ? path.substring(0, slash + 1) : "";
        return file.withPath(normalize(dir + uri));
    }

    private static String normalize(String path) {
        java.util.Deque<String> parts = new java.util.ArrayDeque<>();
        for (String p : path.split("/")) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) {
                if (!parts.isEmpty()) parts.removeLast();
            } else {
                parts.addLast(p);
            }
        }
        return String.join("/", parts);
    }
}
