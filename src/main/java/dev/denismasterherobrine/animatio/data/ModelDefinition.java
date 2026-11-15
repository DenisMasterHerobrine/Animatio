package dev.denismasterherobrine.animatio.data;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.denismasterherobrine.animatio.api.playable.AnimationRef;
import dev.denismasterherobrine.animatio.api.playable.BlendSpace;
import dev.denismasterherobrine.animatio.api.playable.BlendSpace1D;
import dev.denismasterherobrine.animatio.api.playable.BlendSpace2D;
import dev.denismasterherobrine.animatio.api.playback.Easing;
import dev.denismasterherobrine.animatio.api.event.EventMarker;
import dev.denismasterherobrine.animatio.api.playback.TransitionConfig;
import dev.denismasterherobrine.animatio.event.EventTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.StringRepresentable;
import org.joml.Vector3f;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The optional JSON file next to a model, {@code assets/<ns>/animatio/models/<name>.anim.json}:
 * <pre>{@code
 * {
 *   "clips": {
 *     "walk": { "loop": true, "speed": 1.0,
 *               "events": [ { "time": 0.25, "type": "animatio:sound", "sound": "minecraft:entity.zombie.step" } ] }
 *   },
 *   "blend_spaces": {
 *     "locomotion": { "parameter": "speed", "smoothing": 0.1,
 *                     "samples": [ { "x": 0, "clip": "idle" }, { "x": 2, "clip": "walk" }, { "x": 6, "clip": "run" } ] },
 *     "move": { "parameters": ["move_x", "move_z"],
 *               "samples": [ { "x": 0, "y": 0, "clip": "idle" }, { "x": 0, "y": 4, "clip": "walk" } ] }
 *   },
 *   "transitions": { "default": 0.2, "pairs": [ { "from": "fall", "to": "get_up", "duration": 0.3 } ] },
 *   "sockets": { "right_hand": { "bone": "hand_r", "offset": [0, 0.1, 0], "rotation": [0, 0, 90], "scale": 1 } },
 *   "materials": { "eyes": { "render_type": "emissive" }, "glass": { "render_type": "translucent" } },
 *   "render": { "scale": 1, "offset": [0, 0, 0], "rotation": [0, 180, 0], "shadow_radius": 0.5,
 *               "reduced_rate_distance": 32, "minimal_rate_distance": 64 }
 * }
 * }</pre>
 * Everything is optional. Settings made in code ({@code AnimatioApi.configureModel}) win over this file.
 */
public record ModelDefinition(Map<String, ClipDef> clips, Map<String, BlendSpaceDef> blendSpaces,
                              TransitionsDef transitions, Map<String, SocketDef> sockets,
                              Map<String, MaterialDef> materials, RenderDef render) {

    // ------------------------------------------------------------------ transitions

    private static final Codec<Easing> EASING = Codec.STRING.comapFlatMap(s -> {
        try {
            return DataResult.success(Easing.valueOf(s.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return DataResult.error(() -> "Unknown easing '" + s + "' (linear, ease_in, ease_out, ease_in_out)");
        }
    }, e -> e.name().toLowerCase(Locale.ROOT));

    private static final Codec<TransitionConfig> TRANSITION_OBJECT = RecordCodecBuilder.create(i -> i.group(
            Codec.floatRange(0f, 60f).fieldOf("duration").forGetter(TransitionConfig::duration),
            EASING.optionalFieldOf("easing", Easing.EASE_IN_OUT).forGetter(TransitionConfig::easing)
    ).apply(i, TransitionConfig::new));

    /** Either a number of seconds or {@code {"duration": 0.3, "easing": "linear"}}. */
    public static final Codec<TransitionConfig> TRANSITION = Codec.either(Codec.floatRange(0f, 60f), TRANSITION_OBJECT)
            .xmap(e -> e.map(TransitionConfig::of, c -> c), Either::right);

    public record PairDef(String from, String to, TransitionConfig transition) {
        public static final Codec<PairDef> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("from", "*").forGetter(PairDef::from),
                Codec.STRING.optionalFieldOf("to", "*").forGetter(PairDef::to),
                Codec.floatRange(0f, 60f).fieldOf("duration").forGetter(p -> p.transition.duration()),
                EASING.optionalFieldOf("easing", Easing.EASE_IN_OUT).forGetter(p -> p.transition.easing())
        ).apply(i, (from, to, d, e) -> new PairDef(from, to, new TransitionConfig(d, e))));
    }

    public record TransitionsDef(Optional<TransitionConfig> defaultTransition, List<PairDef> pairs) {
        public static final TransitionsDef EMPTY = new TransitionsDef(Optional.empty(), List.of());
        public static final Codec<TransitionsDef> CODEC = RecordCodecBuilder.create(i -> i.group(
                TRANSITION.optionalFieldOf("default").forGetter(TransitionsDef::defaultTransition),
                PairDef.CODEC.listOf().optionalFieldOf("pairs", List.of()).forGetter(TransitionsDef::pairs)
        ).apply(i, TransitionsDef::new));
    }

    // ------------------------------------------------------------------ clips

    public record ClipDef(Optional<Boolean> loop, Optional<Float> speed, List<EventMarker> events) {
        public static final Codec<ClipDef> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.BOOL.optionalFieldOf("loop").forGetter(ClipDef::loop),
                Codec.floatRange(0.001f, 1000f).optionalFieldOf("speed").forGetter(ClipDef::speed),
                // lazy: event codecs reach into game registries, which must not be touched while this class loads
                Codec.lazyInitialized(() -> EventTypes.MARKER_CODEC).listOf().optionalFieldOf("events", List.of()).forGetter(ClipDef::events)
        ).apply(i, ClipDef::new));
    }

    // ------------------------------------------------------------------ blend spaces

    public record SampleDef(float x, Optional<Float> y, String clip, float speed) {
        public static final Codec<SampleDef> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.fieldOf("x").forGetter(SampleDef::x),
                Codec.FLOAT.optionalFieldOf("y").forGetter(SampleDef::y),
                Codec.STRING.fieldOf("clip").forGetter(SampleDef::clip),
                Codec.floatRange(0.001f, 1000f).optionalFieldOf("speed", 1f).forGetter(SampleDef::speed)
        ).apply(i, SampleDef::new));
    }

    public record BlendSpaceDef(Optional<String> parameter, Optional<List<String>> parameters, float smoothing,
                                List<SampleDef> samples) {
        public static final Codec<BlendSpaceDef> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("parameter").forGetter(BlendSpaceDef::parameter),
                Codec.STRING.listOf(2, 2).optionalFieldOf("parameters").forGetter(BlendSpaceDef::parameters),
                Codec.floatRange(0f, 10f).optionalFieldOf("smoothing", 0f).forGetter(BlendSpaceDef::smoothing),
                SampleDef.CODEC.listOf(1, 256).fieldOf("samples").forGetter(BlendSpaceDef::samples)
        ).apply(i, BlendSpaceDef::new));

        public BlendSpace toBlendSpace(String name) {
            if (parameters.isPresent()) {
                BlendSpace2D.Builder b = BlendSpace2D.builder(name, parameters.get().get(0), parameters.get().get(1)).smoothing(smoothing);
                for (SampleDef s : samples) {
                    b.sample(s.x, s.y.orElseThrow(() -> new IllegalArgumentException("2D blend space '" + name + "' sample needs a 'y'")),
                            AnimationRef.of(s.clip).withSpeed(s.speed));
                }
                return b.build();
            }
            BlendSpace1D.Builder b = BlendSpace1D.builder(name, parameter.orElseThrow(
                    () -> new IllegalArgumentException("Blend space '" + name + "' needs 'parameter' (1D) or 'parameters' (2D)"))).smoothing(smoothing);
            for (SampleDef s : samples) b.sample(s.x, AnimationRef.of(s.clip).withSpeed(s.speed));
            return b.build();
        }
    }

    // ------------------------------------------------------------------ sockets

    public record SocketDef(String bone, Vector3f offset, Vector3f rotation, float scale) {
        public static final Codec<SocketDef> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("bone").forGetter(SocketDef::bone),
                ExtraCodecs.VECTOR3F.optionalFieldOf("offset", new Vector3f()).forGetter(SocketDef::offset),
                ExtraCodecs.VECTOR3F.optionalFieldOf("rotation", new Vector3f()).forGetter(SocketDef::rotation),
                Codec.FLOAT.optionalFieldOf("scale", 1f).forGetter(SocketDef::scale)
        ).apply(i, SocketDef::new));
    }

    // ------------------------------------------------------------------ materials and rendering

    /** How a material is drawn. {@code AUTO} derives it from the glTF alpha mode and emissive settings. */
    public enum RenderTypeKind implements StringRepresentable {
        AUTO, SOLID, CUTOUT, TRANSLUCENT, EMISSIVE, INVISIBLE;

        public static final Codec<RenderTypeKind> CODEC = StringRepresentable.fromEnum(RenderTypeKind::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record MaterialDef(RenderTypeKind renderType, Optional<ResourceLocation> texture, Optional<ResourceLocation> shader,
                              Optional<List<Float>> tint, Optional<Boolean> doubleSided) {
        public static final Codec<MaterialDef> CODEC = RecordCodecBuilder.create(i -> i.group(
                RenderTypeKind.CODEC.optionalFieldOf("render_type", RenderTypeKind.AUTO).forGetter(MaterialDef::renderType),
                ResourceLocation.CODEC.optionalFieldOf("texture").forGetter(MaterialDef::texture),
                ResourceLocation.CODEC.optionalFieldOf("shader").forGetter(MaterialDef::shader),
                Codec.FLOAT.listOf(4, 4).optionalFieldOf("tint").forGetter(MaterialDef::tint),
                Codec.BOOL.optionalFieldOf("double_sided").forGetter(MaterialDef::doubleSided)
        ).apply(i, MaterialDef::new));
    }

    public record RenderDef(float scale, Vector3f offset, Vector3f rotation, float shadowRadius,
                            float reducedRateDistance, float minimalRateDistance) {
        public static final RenderDef DEFAULT = new RenderDef(1f, new Vector3f(), new Vector3f(), 0.5f, 32f, 64f);
        public static final Codec<RenderDef> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.floatRange(0.0001f, 1000f).optionalFieldOf("scale", 1f).forGetter(RenderDef::scale),
                ExtraCodecs.VECTOR3F.optionalFieldOf("offset", new Vector3f()).forGetter(RenderDef::offset),
                ExtraCodecs.VECTOR3F.optionalFieldOf("rotation", new Vector3f()).forGetter(RenderDef::rotation),
                Codec.floatRange(0f, 32f).optionalFieldOf("shadow_radius", 0.5f).forGetter(RenderDef::shadowRadius),
                Codec.floatRange(0f, 4096f).optionalFieldOf("reduced_rate_distance", 32f).forGetter(RenderDef::reducedRateDistance),
                Codec.floatRange(0f, 4096f).optionalFieldOf("minimal_rate_distance", 64f).forGetter(RenderDef::minimalRateDistance)
        ).apply(i, RenderDef::new));
    }

    public static final Codec<ModelDefinition> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, ClipDef.CODEC).optionalFieldOf("clips", Map.of()).forGetter(ModelDefinition::clips),
            Codec.unboundedMap(Codec.STRING, BlendSpaceDef.CODEC).optionalFieldOf("blend_spaces", Map.of()).forGetter(ModelDefinition::blendSpaces),
            TransitionsDef.CODEC.optionalFieldOf("transitions", TransitionsDef.EMPTY).forGetter(ModelDefinition::transitions),
            Codec.unboundedMap(Codec.STRING, SocketDef.CODEC).optionalFieldOf("sockets", Map.of()).forGetter(ModelDefinition::sockets),
            Codec.unboundedMap(Codec.STRING, MaterialDef.CODEC).optionalFieldOf("materials", Map.of()).forGetter(ModelDefinition::materials),
            RenderDef.CODEC.optionalFieldOf("render", RenderDef.DEFAULT).forGetter(ModelDefinition::render)
    ).apply(i, ModelDefinition::new));

    // Declared last: the nested records' static codecs read the codec fields above during initialisation.
    public static final ModelDefinition EMPTY = new ModelDefinition(Map.of(), Map.of(), TransitionsDef.EMPTY, Map.of(), Map.of(), RenderDef.DEFAULT);
}
