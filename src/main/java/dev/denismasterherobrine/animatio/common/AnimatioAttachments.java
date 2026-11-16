package dev.denismasterherobrine.animatio.common;

import com.mojang.serialization.Codec;
import dev.denismasterherobrine.animatio.Animatio;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public final class AnimatioAttachments {
    private static final DeferredRegister<AttachmentType<?>> TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Animatio.MODID);

    /** Transient (not saved): animation state is rebuilt by game logic after loading. */
    public static final Supplier<AttachmentType<ControllerHolder>> CONTROLLER = TYPES.register("controller",
            () -> AttachmentType.builder(ControllerHolder::create).build());

    /**
     * Model chosen at runtime for one entity (see {@code AnimatioApi.setModel}), as a resource location string;
     * empty when none. Saved with the entity and kept by players across death.
     */
    public static final Supplier<AttachmentType<String>> ENTITY_MODEL = TYPES.register("entity_model",
            () -> AttachmentType.builder(() -> "").serialize(Codec.STRING).copyOnDeath().build());

    private AnimatioAttachments() {
    }

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }
}
