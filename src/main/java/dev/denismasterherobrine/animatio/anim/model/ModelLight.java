package dev.denismasterherobrine.animatio.anim.model;

/**
 * A {@code KHR_lights_punctual} light instanced by a node. Spot and directional lights shine down the node's -Z axis.
 *
 * @param name       the node's name (unique within the model)
 * @param node       node index; its animated transform moves the light
 * @param color      linear RGB
 * @param intensity  candela for point and spot lights, lux for directional lights
 * @param range      distance where the light reaches zero, or +infinity
 * @param innerCone  spot inner cone angle in radians
 * @param outerCone  spot outer cone angle in radians
 */
public record ModelLight(String name, int node, Type type, float[] color, float intensity, float range,
                         float innerCone, float outerCone) {
    public enum Type {POINT, SPOT, DIRECTIONAL}
}
