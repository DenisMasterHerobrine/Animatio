package dev.denismasterherobrine.animatio.client.override;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;

import java.util.List;
import java.util.Optional;

/**
 * Replaces the renderer of an existing entity type (vanilla or modded) with a glTF model, from a resource pack:
 * {@code assets/<any namespace>/animatio/entity_overrides/<entity namespace>/<entity path>.json}
 * <pre>{@code
 * {
 *   "model": "mypack:zombie",
 *   "death_flip": false,
 *   "driver": { "locomotion": "locomotion", "attack": "attack", "hurt": "hurt", "death": "death" },
 *   "attachments": [
 *     { "bone": "hand_r", "type": "held_item", "arm": "right" },
 *     { "bone": "head", "type": "armor", "slot": "head", "piece": "head" }
 *   ]
 * }
 * }</pre>
 * The driver plays clips (or blend spaces from the model definition) from the entity's state on the client:
 * {@code locomotion} loops on the base layer (use a blend space on {@code speed}), {@code attack} plays when the
 * entity swings, {@code hurt} when it takes damage, {@code death} (held) when it dies.
 */
public record EntityOverride(ResourceLocation model, boolean deathFlip, Driver driver, List<AttachmentDef> attachments) {

    /**
     * Clips (or blend spaces) driven from the entity's state on the client. {@code locomotion} loops on the base layer;
     * {@code attack}, {@code hurt} and {@code death} play once on events; the state clips ({@code crouch},
     * {@code swim}, {@code fly} (elytra), {@code ride}, {@code sleep}, {@code use} (using an item)) loop while their
     * state lasts, on a layer above locomotion.
     */
    public record Driver(Optional<String> locomotion, Optional<String> attack, Optional<String> hurt, Optional<String> death,
                         Optional<String> crouch, Optional<String> swim, Optional<String> fly, Optional<String> ride,
                         Optional<String> sleep, Optional<String> use) {
        public static final Driver NONE = new Driver(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        /** Conventional names, used for players and runtime-model entities without an override file. */
        public static final Driver CONVENTIONAL = new Driver(Optional.of("locomotion"), Optional.of("attack"), Optional.of("hurt"),
                Optional.of("death"), Optional.of("crouch"), Optional.of("swim"), Optional.of("fly"), Optional.of("ride"),
                Optional.of("sleep"), Optional.of("use"));
        public static final Codec<Driver> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("locomotion").forGetter(Driver::locomotion),
                Codec.STRING.optionalFieldOf("attack").forGetter(Driver::attack),
                Codec.STRING.optionalFieldOf("hurt").forGetter(Driver::hurt),
                Codec.STRING.optionalFieldOf("death").forGetter(Driver::death),
                Codec.STRING.optionalFieldOf("crouch").forGetter(Driver::crouch),
                Codec.STRING.optionalFieldOf("swim").forGetter(Driver::swim),
                Codec.STRING.optionalFieldOf("fly").forGetter(Driver::fly),
                Codec.STRING.optionalFieldOf("ride").forGetter(Driver::ride),
                Codec.STRING.optionalFieldOf("sleep").forGetter(Driver::sleep),
                Codec.STRING.optionalFieldOf("use").forGetter(Driver::use)
        ).apply(i, Driver::new));
    }

    /**
     * @param type {@code held_item} (arm), {@code armor} (slot, piece), {@code equipped} (slot, display), {@code cape} (texture)
     */
    public record AttachmentDef(String bone, String type, Optional<String> arm, Optional<String> slot, Optional<String> piece,
                                Optional<String> display, Optional<ResourceLocation> texture) {
        public static final Codec<AttachmentDef> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("bone").forGetter(AttachmentDef::bone),
                Codec.STRING.fieldOf("type").forGetter(AttachmentDef::type),
                Codec.STRING.optionalFieldOf("arm").forGetter(AttachmentDef::arm),
                Codec.STRING.optionalFieldOf("slot").forGetter(AttachmentDef::slot),
                Codec.STRING.optionalFieldOf("piece").forGetter(AttachmentDef::piece),
                Codec.STRING.optionalFieldOf("display").forGetter(AttachmentDef::display),
                ResourceLocation.CODEC.optionalFieldOf("texture").forGetter(AttachmentDef::texture)
        ).apply(i, AttachmentDef::new));

        public HumanoidArm armValue() {
            return arm.map(a -> a.equalsIgnoreCase("left") ? HumanoidArm.LEFT : HumanoidArm.RIGHT).orElse(HumanoidArm.RIGHT);
        }

        public EquipmentSlot slotValue() {
            return EquipmentSlot.byName(slot.orElse("head").toLowerCase(java.util.Locale.ROOT));
        }

        public ItemDisplayContext displayValue() {
            String d = display.orElse("head").toUpperCase(java.util.Locale.ROOT);
            try {
                return ItemDisplayContext.valueOf(d);
            } catch (IllegalArgumentException e) {
                return ItemDisplayContext.HEAD;
            }
        }
    }

    public static final Codec<EntityOverride> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.fieldOf("model").forGetter(EntityOverride::model),
            Codec.BOOL.optionalFieldOf("death_flip", true).forGetter(EntityOverride::deathFlip),
            Driver.CODEC.optionalFieldOf("driver", Driver.NONE).forGetter(EntityOverride::driver),
            AttachmentDef.CODEC.listOf().optionalFieldOf("attachments", List.of()).forGetter(EntityOverride::attachments)
    ).apply(i, EntityOverride::new));
}
