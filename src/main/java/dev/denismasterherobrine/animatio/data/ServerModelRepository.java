package dev.denismasterherobrine.animatio.data;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.denismasterherobrine.animatio.anim.model.AnimationSet;
import dev.denismasterherobrine.animatio.gltf.GltfLoader;
import dev.denismasterherobrine.animatio.gltf.ModelData;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-side model data. The logical server has no client resources, so it reads skeletons, clip timings and
 * JSON definitions straight from mod files. This is enough for {@code isPlaying}, sequence timing and late-join
 * snapshots; the client stays authoritative for visuals (a resource pack that changes clip lengths only makes
 * server-side timing approximate).
 */
public final class ServerModelRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger("Animatio");
    private static final Map<ResourceLocation, Optional<AnimationSet>> CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger GENERATION = new AtomicInteger(1);
    private static volatile int configVersion = -1;

    private ServerModelRepository() {
    }

    /** The model's animation data, or {@code null} if no mod ships it. */
    public static @Nullable AnimationSet get(ResourceLocation id) {
        int cv = ModelConfigs.version();
        if (cv != configVersion) {
            configVersion = cv;
            clear();
        }
        return CACHE.computeIfAbsent(id, ServerModelRepository::load).orElse(null);
    }

    /** Changes whenever the cache is cleared; controllers rebind when it does. */
    public static int generation() {
        return GENERATION.get();
    }

    public static void clear() {
        CACHE.clear();
        GENERATION.incrementAndGet();
    }

    private static Optional<AnimationSet> load(ResourceLocation id) {
        try {
            ResourceLocation glb = ModelPaths.glb(id);
            byte[] data = read(glb);
            ResourceLocation file = glb;
            if (data == null) {
                file = ModelPaths.gltf(id);
                data = read(file);
            }
            if (data == null) {
                LOGGER.warn("Model {} was not found in any mod file ({}); server-side animation timing is unavailable", id, glb);
                return Optional.empty();
            }
            ResourceLocation base = file;
            ModelData model = GltfLoader.load(id.toString(), data, false, uri -> read(ModelPaths.relative(base, uri)));
            ModelDefinition def = null;
            byte[] json = read(ModelPaths.definition(id));
            if (json != null) {
                def = ModelDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(new String(json, StandardCharsets.UTF_8)))
                        .resultOrPartial(err -> LOGGER.error("Model {}: invalid definition: {}", id, err))
                        .orElse(null);
            }
            return Optional.of(AnimationSetFactory.build(id, model, def, ModelConfigs.get(id)));
        } catch (Exception e) {
            LOGGER.error("Failed to load model {} on the server", id, e);
            return Optional.empty();
        }
    }

    /** Read {@code assets/<ns>/<path>} from the mod files, preferring the mod whose id is the namespace. */
    private static byte @Nullable [] read(ResourceLocation location) {
        String relative = "assets/" + location.getNamespace() + "/" + location.getPath();
        for (Path root : roots(location.getNamespace())) {
            Path p = root.resolve(relative);
            if (Files.isRegularFile(p)) {
                try {
                    return Files.readAllBytes(p);
                } catch (IOException e) {
                    LOGGER.error("Could not read {}", p, e);
                    return null;
                }
            }
        }
        return null;
    }

    private static List<Path> roots(String namespace) {
        List<Path> roots = new ArrayList<>();
        ModList list = ModList.get();
        if (list == null) return roots;
        IModFileInfo preferred = list.getModFileById(namespace);
        if (preferred != null) roots.add(preferred.getFile().getSecureJar().getRootPath());
        for (IModFileInfo info : list.getModFiles()) {
            if (info == preferred) continue;
            roots.add(info.getFile().getSecureJar().getRootPath());
        }
        return roots;
    }
}
