package dev.denismasterherobrine.animatio.client.model;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.JsonOps;
import dev.denismasterherobrine.animatio.anim.model.AnimationSet;
import dev.denismasterherobrine.animatio.data.AnimationSetFactory;
import dev.denismasterherobrine.animatio.data.ModelConfigs;
import dev.denismasterherobrine.animatio.data.ModelDefinition;
import dev.denismasterherobrine.animatio.data.ModelPaths;
import dev.denismasterherobrine.animatio.gltf.GltfLoader;
import dev.denismasterherobrine.animatio.gltf.MaterialData;
import dev.denismasterherobrine.animatio.gltf.MeshData;
import dev.denismasterherobrine.animatio.gltf.ModelData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.FastColor;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loads every model under {@code assets/<ns>/animatio/models/} on resource reload. Parsing, image decoding and
 * bounds computation happen off-thread; texture upload happens on the render thread.
 */
public final class ClientModelManager extends SimplePreparableReloadListener<ClientModelManager.Prepared> {
    private static final Logger LOGGER = LoggerFactory.getLogger("Animatio");
    public static final ClientModelManager INSTANCE = new ClientModelManager();
    public static final ResourceLocation WHITE_TEXTURE = ResourceLocation.fromNamespaceAndPath("animatio", "white");

    private static final AtomicInteger VERSIONS = new AtomicInteger(1);

    record Loaded(ResourceLocation id, ModelData data, @Nullable ModelDefinition definition, List<@Nullable NativeImage> images, float[] bounds) {
    }

    public record Prepared(Map<ResourceLocation, Loaded> models) {
    }

    private volatile Map<ResourceLocation, GltfModel> models = Map.of();
    private volatile Map<ResourceLocation, AnimationSet> animationSets = Map.of();
    private final AtomicInteger generation = new AtomicInteger(1);
    private int configVersion = -1;
    private boolean whiteRegistered;

    private ClientModelManager() {
    }

    public @Nullable GltfModel get(@Nullable ResourceLocation id) {
        return id == null ? null : models.get(id);
    }

    public Map<ResourceLocation, GltfModel> all() {
        return models;
    }

    public @Nullable AnimationSet animationSet(ResourceLocation id) {
        ensureConfigCurrent();
        return animationSets.get(id);
    }

    public int generation() {
        return generation.get();
    }

    /** Rebuild animation sets if code configuration changed after the last reload. */
    private void ensureConfigCurrent() {
        int cv = ModelConfigs.version();
        if (cv == configVersion) return;
        configVersion = cv;
        Map<ResourceLocation, AnimationSet> sets = new HashMap<>();
        for (GltfModel model : models.values()) {
            sets.put(model.id(), AnimationSetFactory.build(model.id(), model.data(), model.definition(), ModelConfigs.get(model.id())));
        }
        animationSets = Map.copyOf(sets);
        generation.incrementAndGet();
    }

    // ------------------------------------------------------------------ reload

    @Override
    protected Prepared prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, Loaded> out = new HashMap<>();
        Map<ResourceLocation, Integer> priorities = new HashMap<>();
        Map<ResourceLocation, Resource> files = manager.listResources(ModelPaths.DIRECTORY,
                rl -> rl.getPath().endsWith(".glb") || rl.getPath().endsWith(".gltf") || rl.getPath().endsWith(".bbmodel"));
        for (Map.Entry<ResourceLocation, Resource> e : files.entrySet()) {
            ResourceLocation file = e.getKey();
            ResourceLocation id = ModelPaths.modelId(file);
            if (id == null) continue;
            // one model id, several files: .glb wins over .gltf, which wins over .bbmodel
            int priority = file.getPath().endsWith(".glb") ? 3 : file.getPath().endsWith(".gltf") ? 2 : 1;
            Integer existing = priorities.get(id);
            if (existing != null) {
                LOGGER.warn("Model {} exists in more than one format (.glb, .gltf, .bbmodel); using the .glb, then the .gltf", id);
                if (existing >= priority) continue;
            }
            priorities.put(id, priority);
            try {
                byte[] bytes;
                try (InputStream in = e.getValue().open()) {
                    bytes = in.readAllBytes();
                }
                ModelData data = ModelPaths.load(id.toString(), file.getPath(), bytes, true, uri -> readOrNull(manager, ModelPaths.relative(file, uri)));
                ModelDefinition def = loadDefinition(manager, id);
                List<NativeImage> images = new ArrayList<>(data.images().size());
                for (int i = 0; i < data.images().size(); i++) {
                    try {
                        images.add(ImageDecoder.decode(data.images().get(i)));
                    } catch (IOException ex) {
                        LOGGER.error("Model {}: could not decode image {}: {}", id, i, ex.getMessage());
                        images.add(null);
                    }
                }
                out.put(id, new Loaded(id, data, def, images, ModelBounds.compute(data)));
            } catch (Exception ex) {
                LOGGER.error("Failed to load model {} from {}", id, file, ex);
            }
        }
        return new Prepared(out);
    }

    private static byte @Nullable [] readOrNull(ResourceManager manager, ResourceLocation location) {
        Optional<Resource> res = manager.getResource(location);
        if (res.isEmpty()) return null;
        try (InputStream in = res.get().open()) {
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static @Nullable ModelDefinition loadDefinition(ResourceManager manager, ResourceLocation id) {
        byte[] json = readOrNull(manager, ModelPaths.definition(id));
        if (json == null) return null;
        try {
            return ModelDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(new String(json, StandardCharsets.UTF_8)))
                    .resultOrPartial(err -> LOGGER.error("Model {}: invalid definition: {}", id, err))
                    .orElse(null);
        } catch (RuntimeException e) {
            LOGGER.error("Model {}: malformed definition JSON: {}", id, e.getMessage());
            return null;
        }
    }

    @Override
    protected void apply(Prepared prepared, ResourceManager manager, ProfilerFiller profiler) {
        TextureManager textures = Minecraft.getInstance().getTextureManager();
        ensureWhiteTexture(textures);
        for (GltfModel old : models.values()) {
            for (ResourceLocation tex : old.ownedTextures()) textures.release(tex);
            dev.denismasterherobrine.animatio.client.render.gpu.GpuModelData.release(old);
        }

        Map<ResourceLocation, GltfModel> loaded = new HashMap<>();
        Map<ResourceLocation, AnimationSet> sets = new HashMap<>();
        for (Loaded l : prepared.models().values()) {
            try {
                GltfModel model = build(l, textures);
                loaded.put(l.id(), model);
                sets.put(l.id(), model.animations());
            } catch (Exception e) {
                LOGGER.error("Failed to prepare model {}", l.id(), e);
                for (NativeImage img : l.images()) if (img != null) img.close();
            }
        }
        models = Map.copyOf(loaded);
        animationSets = Map.copyOf(sets);
        configVersion = ModelConfigs.version();
        generation.incrementAndGet();
        LOGGER.info("Loaded {} Animatio model(s)", loaded.size());
    }

    private void ensureWhiteTexture(TextureManager textures) {
        if (whiteRegistered) return;
        NativeImage white = new NativeImage(1, 1, false);
        white.setPixelRGBA(0, 0, 0xFFFFFFFF);
        textures.register(WHITE_TEXTURE, new DynamicTexture(white));
        whiteRegistered = true;
    }

    private GltfModel build(Loaded l, TextureManager textures) {
        ResourceLocation id = l.id();
        ModelData data = l.data();
        ModelDefinition def = l.definition() != null ? l.definition() : ModelDefinition.EMPTY;

        List<ResourceLocation> owned = new ArrayList<>();
        ResourceLocation[] imageTextures = new ResourceLocation[l.images().size()];
        boolean[] linear = new boolean[l.images().size()];
        for (MaterialData m : data.materials()) {
            if (m.baseColorImage() >= 0 && m.baseColorImage() < linear.length && !m.nearestFilter()) linear[m.baseColorImage()] = true;
        }
        for (int i = 0; i < imageTextures.length; i++) {
            NativeImage img = l.images().get(i);
            if (img == null) continue;
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath("animatio", "models/" + id.getNamespace() + "/" + id.getPath() + "/image_" + i);
            DynamicTexture tex = new DynamicTexture(img);
            if (linear[i]) tex.setFilter(true, false);
            textures.register(rl, tex);
            imageTextures[i] = rl;
            owned.add(rl);
        }

        List<GltfModel.Part> parts = new ArrayList<>();
        for (MeshData mesh : data.meshes()) {
            MaterialData md = data.material(mesh.material());
            ModelDefinition.MaterialDef mdef = def.materials().get(md.name());
            ModelDefinition.RenderTypeKind kind = mdef != null ? mdef.renderType() : ModelDefinition.RenderTypeKind.AUTO;
            if (kind == ModelDefinition.RenderTypeKind.AUTO) {
                kind = md.alphaMode() == MaterialData.AlphaMode.BLEND ? ModelDefinition.RenderTypeKind.TRANSLUCENT : ModelDefinition.RenderTypeKind.CUTOUT;
            }
            ResourceLocation texture = WHITE_TEXTURE;
            if (mdef != null && mdef.texture().isPresent()) texture = mdef.texture().get();
            else if (md.baseColorImage() >= 0 && md.baseColorImage() < imageTextures.length && imageTextures[md.baseColorImage()] != null) {
                texture = imageTextures[md.baseColorImage()];
            }
            ResourceLocation emissive = md.emissiveImage() >= 0 && md.emissiveImage() < imageTextures.length ? imageTextures[md.emissiveImage()] : null;
            float[] f = md.baseColorFactor();
            float r = f[0], g = f[1], b = f[2], a = f[3];
            if (mdef != null && mdef.tint().isPresent()) {
                List<Float> t = mdef.tint().get();
                r *= t.get(0);
                g *= t.get(1);
                b *= t.get(2);
                a *= t.get(3);
            }
            int color = FastColor.ARGB32.color(Math.round(clamp(a) * 255), Math.round(clamp(r) * 255), Math.round(clamp(g) * 255), Math.round(clamp(b) * 255));
            boolean doubleSided = mdef != null && mdef.doubleSided().isPresent() ? mdef.doubleSided().get() : md.doubleSided();
            boolean fullBright = md.unlit() || kind == ModelDefinition.RenderTypeKind.EMISSIVE;
            ResourceLocation shader = mdef != null ? mdef.shader().orElse(null) : null;
            parts.add(new GltfModel.Part(mesh, md.name(), mesh.material(), kind, texture, emissive, color, doubleSided, fullBright, shader,
                    QuadPacker.pack(mesh.indices())));
        }
        for (String name : def.materials().keySet()) {
            boolean found = data.materials().stream().anyMatch(m -> m.name().equals(name));
            if (!found) LOGGER.warn("Model {}: definition configures missing material '{}'", id, name);
        }

        AnimationSet set = AnimationSetFactory.build(id, data, l.definition(), ModelConfigs.get(id));
        return new GltfModel(id, data, set, def, parts, owned, l.bounds(), VERSIONS.getAndIncrement());
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
