package dev.denismasterherobrine.animatio.client.render;

import dev.denismasterherobrine.animatio.anim.model.ModelLight;
import dev.denismasterherobrine.animatio.anim.runtime.ControllerImpl;
import dev.denismasterherobrine.animatio.client.model.GltfModel;
import dev.denismasterherobrine.animatio.data.ModelDefinition;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.function.Consumer;

/**
 * {@code KHR_lights_punctual} lights on Minecraft's terms. The game has no dynamic lights, so a model's lights
 * brighten the model itself (its block light is raised to the brightest light's level), and their animated
 * world-space positions are available to other code, for example a dynamic-lights integration.
 */
public final class ModelLights {
    private ModelLights() {
    }

    /** A light as placed in the world in the last rendered frame. */
    public record WorldLight(ModelLight light, Vector3f position, Vector3f direction, int blockLight) {
    }

    /**
     * Block light level (0..15) a light gives its own model: the definition's {@code lights.<name>.block_light} if
     * set, else derived from the glTF intensity (10 candela of white light or more is full brightness; directional
     * lights give none).
     */
    public static int level(ModelLight light, ModelDefinition definition) {
        ModelDefinition.LightDef def = definition.lights().get(light.name());
        if (def != null && def.blockLight().isPresent()) return def.blockLight().get();
        if (light.type() == ModelLight.Type.DIRECTIONAL) return 0;
        float[] c = light.color();
        float strength = light.intensity() * Math.max(c[0], Math.max(c[1], c[2]));
        if (!(strength > 0f)) return 0;
        return Math.min(15, (int) Math.ceil(15f * Math.min(1f, strength / 10f)));
    }

    /** Packed light with the block component raised to the model's own light level. */
    public static int light(GltfModel model, AnimatedInstance instance, int packedLight) {
        int level = model.selfLight();
        if (level <= 0) return packedLight;
        int block = (packedLight & 0xFFFF) >> 4;
        if (block >= level) return packedLight;
        return (packedLight & 0xFFFF0000) | (level << 4);
    }

    /** World-space lights of an instance, from its last rendered frame. Render thread. */
    public static void forEach(GltfModel model, AnimatedInstance instance, ControllerImpl controller, Consumer<WorldLight> out) {
        List<ModelLight> lights = model.data().lights();
        if (lights.isEmpty() || !instance.hasWorldTransform()) return;
        float[] globals = controller.globals();
        Matrix4f m = new Matrix4f();
        for (ModelLight light : lights) {
            if (globals != null) m.set(globals, light.node() * 16);
            else m.identity();
            instance.modelToWorld().mul(m, m);
            Vector3f pos = m.getTranslation(new Vector3f());
            Vector3f dir = m.transformDirection(new Vector3f(0, 0, -1)).normalize();
            out.accept(new WorldLight(light, pos, dir, level(light, model.definition())));
        }
    }
}
