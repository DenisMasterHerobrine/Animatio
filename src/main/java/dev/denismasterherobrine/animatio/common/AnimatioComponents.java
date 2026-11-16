package dev.denismasterherobrine.animatio.common;

import com.mojang.serialization.Codec;
import dev.denismasterherobrine.animatio.Animatio;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/** Item data components. */
public final class AnimatioComponents {
    private static final DeferredRegister.DataComponents TYPES = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Animatio.MODID);

    /**
     * Identity of one item stack's animation state (random, assigned on first use): it travels with the stack, so
     * every client finds the same per-stack controller, even after the stack moves between inventories.
     */
    public static final Supplier<DataComponentType<Long>> ANIMATION_ID = TYPES.registerComponentType("animation_id",
            b -> b.persistent(Codec.LONG).networkSynchronized(ByteBufCodecs.VAR_LONG));

    private AnimatioComponents() {
    }

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }
}
