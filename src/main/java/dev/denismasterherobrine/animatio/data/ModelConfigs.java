package dev.denismasterherobrine.animatio.data;

import dev.denismasterherobrine.animatio.api.ModelConfig;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Code-side model configuration, keyed by model id.
 */
public final class ModelConfigs {
    private static final Map<ResourceLocation, ModelConfig> CONFIGS = new ConcurrentHashMap<>();
    /** Bumped on every change so loaded models know to rebuild. */
    private static final AtomicInteger VERSION = new AtomicInteger();

    private ModelConfigs() {
    }

    public static void configure(ResourceLocation model, Consumer<ModelConfig> config) {
        ModelConfig c = CONFIGS.computeIfAbsent(model, k -> new ModelConfig());
        synchronized (c) {
            config.accept(c);
        }
        VERSION.incrementAndGet();
    }

    public static @Nullable ModelConfig get(ResourceLocation model) {
        return CONFIGS.get(model);
    }

    public static int version() {
        return VERSION.get();
    }
}
