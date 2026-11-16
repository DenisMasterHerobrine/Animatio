package dev.denismasterherobrine.animatio.anim.model;

/**
 * A glTF camera instanced by a node. The camera looks down the node's -Z axis with +Y up.
 *
 * @param name        the node's name (unique within the model)
 * @param node        node index; its animated transform moves the camera
 * @param perspective {@code false} for orthographic cameras
 * @param yfov        vertical field of view in radians (perspective)
 * @param aspect      aspect ratio, or 0 when the file leaves it to the viewport
 * @param znear       near plane distance
 * @param zfar        far plane distance, or +infinity
 * @param xmag        horizontal half extent (orthographic)
 * @param ymag        vertical half extent (orthographic)
 */
public record ModelCamera(String name, int node, boolean perspective, float yfov, float aspect, float znear, float zfar,
                          float xmag, float ymag) {
}
