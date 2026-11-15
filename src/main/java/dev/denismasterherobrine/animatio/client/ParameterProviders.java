package dev.denismasterherobrine.animatio.client;

import dev.denismasterherobrine.animatio.anim.runtime.ControllerImpl;
import dev.denismasterherobrine.animatio.common.ControllerHolder;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Blend-space parameters computed on the client from an entity's own movement, every tick, so locomotion needs
 * no network traffic. A parameter set from the server takes precedence over the computed value.
 * <ul>
 *     <li>{@code speed}: horizontal speed, blocks per second</li>
 *     <li>{@code move_x}, {@code move_z}: velocity relative to the body (right, forward), blocks per second</li>
 *     <li>{@code vertical_speed}: blocks per second, positive up</li>
 *     <li>{@code on_ground}: 1 or 0</li>
 *     <li>{@code yaw_delta}: body turn rate, degrees per second</li>
 *     <li>{@code health}: health fraction 0..1 (living entities)</li>
 * </ul>
 */
public final class ParameterProviders {
    private ParameterProviders() {
    }

    public static void updateEntity(ControllerHolder holder) {
        if (!(holder.owner() instanceof Entity e)) return;
        ControllerImpl c = holder.controller();
        double dx = e.getX() - e.xo, dy = e.getY() - e.yo, dz = e.getZ() - e.zo;
        float yaw, yawO;
        if (e instanceof LivingEntity living) {
            yaw = living.yBodyRot;
            yawO = living.yBodyRotO;
            c.provideParameter("health", living.getMaxHealth() > 0 ? living.getHealth() / living.getMaxHealth() : 0f);
        } else {
            yaw = e.getYRot();
            yawO = e.yRotO;
        }
        double rad = Math.toRadians(yaw);
        double fx = -Math.sin(rad), fz = Math.cos(rad);
        double rx = -Math.cos(rad), rz = -Math.sin(rad);
        c.provideParameter("speed", (float) (Math.sqrt(dx * dx + dz * dz) * 20.0));
        c.provideParameter("move_z", (float) ((dx * fx + dz * fz) * 20.0));
        c.provideParameter("move_x", (float) ((dx * rx + dz * rz) * 20.0));
        c.provideParameter("vertical_speed", (float) (dy * 20.0));
        c.provideParameter("on_ground", e.onGround() ? 1f : 0f);
        c.provideParameter("yaw_delta", Mth.wrapDegrees(yaw - yawO) * 20f);
    }
}
