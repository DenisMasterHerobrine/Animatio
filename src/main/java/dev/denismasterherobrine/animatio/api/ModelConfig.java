package dev.denismasterherobrine.animatio.api;

import dev.denismasterherobrine.animatio.api.event.AnimationEvent;
import dev.denismasterherobrine.animatio.api.event.EventMarker;
import dev.denismasterherobrine.animatio.api.playable.AnimationRef;
import dev.denismasterherobrine.animatio.api.playable.BlendSpace;
import dev.denismasterherobrine.animatio.api.playable.Playable;
import dev.denismasterherobrine.animatio.api.playback.TransitionConfig;
import dev.denismasterherobrine.animatio.api.playback.TransitionTable;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Model settings made in code. They are merged over the model's JSON definition (code wins) every time the
 * model is (re)loaded, on both sides. Register with {@link AnimatioApi#configureModel} during mod construction
 * from common code, so the server and the client agree.
 */
public final class ModelConfig {
    private @Nullable LookSettings look;
    public record SocketConfig(String bone, Matrix4fc offset) {
    }

    final Map<String, Boolean> loops = new HashMap<>();
    final Map<String, Float> speeds = new HashMap<>();
    final Map<String, List<EventMarker>> events = new HashMap<>();
    final Map<String, BlendSpace> blendSpaces = new LinkedHashMap<>();
    final TransitionTable transitions = new TransitionTable();
    final Map<String, SocketConfig> sockets = new LinkedHashMap<>();

    /** Whether a plain {@code play(clip)} loops. */
    public ModelConfig loop(String clip, boolean loop) {
        loops.put(clip, loop);
        return this;
    }

    public ModelConfig speed(String clip, float speed) {
        if (!(speed > 0f)) throw new IllegalArgumentException("Speed must be > 0");
        speeds.put(clip, speed);
        return this;
    }

    /** Add a keyframe event to a clip of this model. */
    public ModelConfig event(String clip, float time, AnimationEvent event) {
        events.computeIfAbsent(clip, k -> new ArrayList<>()).add(new EventMarker(time, event));
        return this;
    }

    /** Make a blend space playable by name, e.g. {@code play(AnimationRef.of("locomotion"))}. */
    public ModelConfig blendSpace(BlendSpace space) {
        blendSpaces.put(space.name(), space);
        return this;
    }

    public ModelConfig transition(Playable from, Playable to, TransitionConfig transition) {
        transitions.set(from, to, transition);
        return this;
    }

    public ModelConfig defaultTransition(TransitionConfig transition) {
        transitions.setDefault(transition);
        return this;
    }

    /** Name an attachment point: a bone plus a local offset. */
    public ModelConfig socket(String name, String bone, @Nullable Matrix4fc offset) {
        sockets.put(name, new SocketConfig(bone, offset == null ? new Matrix4f() : new Matrix4f(offset)));
        return this;
    }

    /**
     * Turn {@code bone} toward where the entity looks (head yaw relative to the body, and pitch), clamped to the given
     * limits in degrees, after animations are applied. Client side, from the entity's head rotation.
     */
    public ModelConfig look(String bone, float maxYawDegrees, float maxPitchDegrees) {
        this.look = new LookSettings(bone, maxYawDegrees, maxPitchDegrees, 1f);
        return this;
    }

    public record LookSettings(String bone, float maxYaw, float maxPitch, float weight) {
    }

    public @Nullable LookSettings look() {
        return look;
    }

    public Map<String, Boolean> loops() {
        return loops;
    }

    public Map<String, Float> speeds() {
        return speeds;
    }

    public Map<String, List<EventMarker>> events() {
        return events;
    }

    public Map<String, BlendSpace> blendSpaces() {
        return blendSpaces;
    }

    public TransitionTable transitions() {
        return transitions;
    }

    public Map<String, SocketConfig> sockets() {
        return sockets;
    }
}
