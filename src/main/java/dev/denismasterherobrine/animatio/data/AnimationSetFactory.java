package dev.denismasterherobrine.animatio.data;

import com.mojang.serialization.JsonOps;
import dev.denismasterherobrine.animatio.anim.model.AnimationSet;
import dev.denismasterherobrine.animatio.anim.model.Clip;
import dev.denismasterherobrine.animatio.anim.model.ClipInfo;
import dev.denismasterherobrine.animatio.anim.model.Skeleton;
import dev.denismasterherobrine.animatio.anim.model.Socket;
import dev.denismasterherobrine.animatio.api.playable.BlendSpace;
import dev.denismasterherobrine.animatio.api.event.EventMarker;
import dev.denismasterherobrine.animatio.api.ModelConfig;
import dev.denismasterherobrine.animatio.event.EventTypes;
import dev.denismasterherobrine.animatio.gltf.ModelData;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Combines a glTF model, its JSON definition and code configuration into an {@link AnimationSet}.
 */
public final class AnimationSetFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger("Animatio");

    private AnimationSetFactory() {
    }

    public static AnimationSet build(ResourceLocation id, ModelData model, @Nullable ModelDefinition def, @Nullable ModelConfig code) {
        ModelDefinition d = def != null ? def : ModelDefinition.EMPTY;
        Skeleton sk = model.skeleton();
        AnimationSet.Builder b = AnimationSet.builder(id.toString(), sk);

        for (Clip clip : model.clips()) {
            String name = clip.name();
            List<EventMarker> markers = new ArrayList<>();
            for (Clip.RawEvent raw : clip.rawEvents()) {
                EventTypes.MARKER_CODEC.parse(JsonOps.INSTANCE, raw.data())
                        .resultOrPartial(err -> LOGGER.warn("Model {}: bad event in glTF extras of '{}': {}", id, name, err))
                        .ifPresent(markers::add);
            }
            boolean loop = false;
            float speed = 1f;
            ModelDefinition.ClipDef cd = d.clips().get(name);
            if (cd != null) {
                loop = cd.loop().orElse(false);
                speed = cd.speed().orElse(1f);
                markers.addAll(cd.events());
            }
            if (code != null) {
                loop = code.loops().getOrDefault(name, loop);
                speed = code.speeds().getOrDefault(name, speed);
                markers.addAll(code.events().getOrDefault(name, List.of()));
            }
            b.clip(new ClipInfo(clip, loop, speed, markers));
        }
        for (String name : d.clips().keySet()) {
            if (model.clip(name) == null) LOGGER.warn("Model {}: definition configures missing clip '{}'", id, name);
        }

        for (Map.Entry<String, ModelDefinition.BlendSpaceDef> e : d.blendSpaces().entrySet()) {
            try {
                BlendSpace space = e.getValue().toBlendSpace(e.getKey());
                checkClips(id, space, b);
                b.blendSpace(space);
            } catch (IllegalArgumentException ex) {
                LOGGER.warn("Model {}: invalid blend space '{}': {}", id, e.getKey(), ex.getMessage());
            }
        }
        if (code != null) {
            for (BlendSpace space : code.blendSpaces().values()) {
                checkClips(id, space, b);
                b.blendSpace(space);
            }
        }

        d.transitions().defaultTransition().ifPresent(b.transitions()::setDefault);
        for (ModelDefinition.PairDef pair : d.transitions().pairs()) b.transitions().set(pair.from(), pair.to(), pair.transition());
        if (code != null) b.transitions().putAll(code.transitions());

        for (Map.Entry<String, ModelDefinition.SocketDef> e : d.sockets().entrySet()) {
            ModelDefinition.SocketDef s = e.getValue();
            int bone = sk.find(s.bone());
            if (bone < 0) {
                LOGGER.warn("Model {}: socket '{}' refers to missing bone '{}'", id, e.getKey(), s.bone());
                continue;
            }
            Matrix4f m = new Matrix4f().translate(s.offset())
                    .rotateZYX((float) Math.toRadians(s.rotation().z), (float) Math.toRadians(s.rotation().y), (float) Math.toRadians(s.rotation().x))
                    .scale(s.scale());
            b.socket(new Socket(e.getKey(), bone, m.get(new float[16])));
        }
        if (code != null) {
            for (Map.Entry<String, ModelConfig.SocketConfig> e : code.sockets().entrySet()) {
                int bone = sk.find(e.getValue().bone());
                if (bone < 0) {
                    LOGGER.warn("Model {}: socket '{}' refers to missing bone '{}'", id, e.getKey(), e.getValue().bone());
                    continue;
                }
                b.socket(new Socket(e.getKey(), bone, e.getValue().offset().get(new float[16])));
            }
        }
        return b.build();
    }

    private static void checkClips(ResourceLocation id, BlendSpace space, AnimationSet.Builder b) {
        for (int i = 0; i < space.size(); i++) {
            String clip = space.clip(i).name();
            if (b.getClip(clip) == null) LOGGER.warn("Model {}: blend space '{}' uses missing clip '{}'", id, space.name(), clip);
        }
    }
}
