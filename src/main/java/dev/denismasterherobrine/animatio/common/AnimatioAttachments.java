package dev.denismasterherobrine.animatio.common;

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

    private AnimatioAttachments() {
    }

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }
}
