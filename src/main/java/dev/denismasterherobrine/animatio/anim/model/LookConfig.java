package dev.denismasterherobrine.animatio.anim.model;

/**
 * Procedural look-at for a head (or any) bone: after animations are applied, the bone turns by the controller's
 * {@code look_yaw} / {@code look_pitch} parameters (degrees, clamped to the limits and scaled by {@code weight}).
 * The client fills these parameters from a living entity's head rotation every frame.
 *
 * @param bone node index
 */
public record LookConfig(int bone, float maxYaw, float maxPitch, float weight) {
    public static final String YAW_PARAMETER = "look_yaw";
    public static final String PITCH_PARAMETER = "look_pitch";
}
