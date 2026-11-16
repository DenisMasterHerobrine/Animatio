package dev.denismasterherobrine.animatio.mixin.client;

import dev.denismasterherobrine.animatio.client.camera.CinematicCamera;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets a glTF camera drive the view ({@link CinematicCamera}).
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot, float roll);

    @Inject(method = "setup", at = @At("TAIL"))
    private void animatio$cinematicCamera(BlockGetter level, Entity entity, boolean detached, boolean mirror, float partialTick, CallbackInfo ci) {
        double[] pose = CinematicCamera.pose(partialTick);
        if (pose == null) return;
        this.detached = true; // the local player is part of the shot
        setRotation((float) pose[3], (float) pose[4], (float) pose[5]);
        setPosition(new Vec3(pose[0], pose[1], pose[2]));
    }
}
