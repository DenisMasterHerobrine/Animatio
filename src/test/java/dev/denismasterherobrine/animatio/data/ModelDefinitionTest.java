package dev.denismasterherobrine.animatio.data;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.denismasterherobrine.animatio.api.playable.BlendSpace1D;
import dev.denismasterherobrine.animatio.api.playable.BlendSpace2D;
import dev.denismasterherobrine.animatio.api.playback.Easing;
import dev.denismasterherobrine.animatio.api.playback.TransitionConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Definition parsing without game registries (events need a bootstrapped game; they are covered by the dev GameTests).
 */
class ModelDefinitionTest {
    private static ModelDefinition parse(String json) {
        return ModelDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    @Test
    void emptyDefinition() {
        ModelDefinition def = parse("{}");
        assertEquals(ModelDefinition.EMPTY.render(), def.render());
        assertTrue(def.clips().isEmpty());
    }

    @Test
    void transitionsAcceptNumberOrObject() {
        ModelDefinition def = parse("""
                { "transitions": { "default": 0.3,
                                   "pairs": [ { "from": "a", "to": "b", "duration": 0.5, "easing": "linear" } ] } }""");
        assertEquals(TransitionConfig.of(0.3f), def.transitions().defaultTransition().orElseThrow());
        assertEquals(new TransitionConfig(0.5f, Easing.LINEAR), def.transitions().pairs().getFirst().transition());
    }

    @Test
    void blendSpacesAndClips() {
        ModelDefinition def = parse("""
                { "clips": { "walk": { "loop": true, "speed": 1.5 } },
                  "blend_spaces": {
                    "loco": { "parameter": "speed", "samples": [ { "x": 0, "clip": "idle" }, { "x": 2, "clip": "walk" } ] },
                    "move": { "parameters": ["x", "z"], "samples": [ { "x": 0, "y": 0, "clip": "idle" }, { "x": 1, "y": 0, "clip": "r" },
                                                                     { "x": 0, "y": 1, "clip": "f" } ] } },
                  "sockets": { "hand": { "bone": "hand_r", "rotation": [0, 90, 0] } },
                  "materials": { "eyes": { "render_type": "emissive" } },
                  "render": { "scale": 2 } }""");
        assertTrue(def.clips().get("walk").loop().orElseThrow());
        assertEquals(1.5f, def.clips().get("walk").speed().orElseThrow());
        assertInstanceOf(BlendSpace1D.class, def.blendSpaces().get("loco").toBlendSpace("loco"));
        assertInstanceOf(BlendSpace2D.class, def.blendSpaces().get("move").toBlendSpace("move"));
        assertEquals(ModelDefinition.RenderTypeKind.EMISSIVE, def.materials().get("eyes").renderType());
        assertEquals(2f, def.render().scale());
        assertEquals("hand_r", def.sockets().get("hand").bone());
    }
}
