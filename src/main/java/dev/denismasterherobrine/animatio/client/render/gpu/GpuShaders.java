package dev.denismasterherobrine.animatio.client.render.gpu;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skinning shader programs. {@code animatio:skinned} is the default; mods can add custom material shaders built on
 * {@code #moj_import <animatio:skinning.glsl>} and register them with {@link #register} before resources load.
 */
public final class GpuShaders {
    private static final Logger LOGGER = LoggerFactory.getLogger("Animatio");
    public static final ResourceLocation DEFAULT = ResourceLocation.fromNamespaceAndPath("animatio", "skinned");
    public static final ResourceLocation DISSOLVE = ResourceLocation.fromNamespaceAndPath("animatio", "dissolve");

    /**
     * Attribute names and order for the skinning programs. Only the names matter (they fix the attribute
     * locations); the vertex layout itself is set up by {@link GpuModelData}.
     */
    public static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("UV0", VertexFormatElement.UV0)
            .add("Normal", VertexFormatElement.NORMAL)
            .add("Joints", VertexFormatElement.UV1)
            .add("Weights", VertexFormatElement.COLOR)
            .add("Color", VertexFormatElement.COLOR)
            .build();
    public static final int ATTR_POSITION = 0, ATTR_UV0 = 1, ATTR_NORMAL = 2, ATTR_JOINTS = 3, ATTR_WEIGHTS = 4, ATTR_COLOR = 5;

    private static final Set<ResourceLocation> REQUESTED = ConcurrentHashMap.newKeySet();
    private static final Map<ResourceLocation, ShaderInstance> LOADED = new ConcurrentHashMap<>();
    private static final Set<ResourceLocation> WARNED = ConcurrentHashMap.newKeySet();

    static {
        REQUESTED.add(DEFAULT);
        REQUESTED.add(DISSOLVE);
    }

    private GpuShaders() {
    }

    /** Request a custom skinning shader ({@code assets/<ns>/shaders/core/<path>.json}). Call during client setup. */
    public static void register(ResourceLocation shader) {
        REQUESTED.add(shader);
    }

    public static void onRegisterShaders(RegisterShadersEvent event) {
        for (ResourceLocation id : REQUESTED) {
            try {
                event.registerShader(new ShaderInstance(event.getResourceProvider(), id, FORMAT), s -> LOADED.put(id, s));
            } catch (Exception e) {
                LOADED.remove(id);
                LOGGER.error("Could not load skinning shader {}; {}", id,
                        id.equals(DEFAULT) ? "GPU skinning is disabled" : "materials using it fall back to the default shader", e);
            }
        }
    }

    /** The default program, or {@code null} if it failed to load. */
    public static @Nullable ShaderInstance defaultShader() {
        return LOADED.get(DEFAULT);
    }

    /** A registered program, falling back to the default for unknown ids. */
    public static @Nullable ShaderInstance get(@Nullable ResourceLocation id) {
        if (id == null) return LOADED.get(DEFAULT);
        ShaderInstance s = LOADED.get(id);
        if (s != null) return s;
        if (WARNED.add(id)) LOGGER.warn("Skinning shader {} is not registered (GpuShaders.register) or failed to load; using the default", id);
        return LOADED.get(DEFAULT);
    }
}
