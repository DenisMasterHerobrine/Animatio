package dev.denismasterherobrine.animatio.client.camera;

import dev.denismasterherobrine.animatio.anim.model.AnimationSet;
import dev.denismasterherobrine.animatio.anim.model.ModelCamera;
import dev.denismasterherobrine.animatio.api.AnimatioApi;
import dev.denismasterherobrine.animatio.client.entity.GltfEntityRenderer;
import dev.denismasterherobrine.animatio.client.model.ClientModelManager;
import dev.denismasterherobrine.animatio.client.model.GltfModel;
import dev.denismasterherobrine.animatio.client.player.GltfPlayerRenderer;
import dev.denismasterherobrine.animatio.client.render.AnimatedInstance;
import dev.denismasterherobrine.animatio.client.render.ClientClock;
import dev.denismasterherobrine.animatio.common.ControllerHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Looks through a glTF camera: while active, the player's view follows a camera node of an animated model
 * (a cutscene camera keyed in Blender), including roll and, for perspective cameras, the field of view.
 * Render thread only.
 */
public final class CinematicCamera {
    private static @Nullable ControllerHolder target;
    private static @Nullable String cameraName;
    private static float fov = Float.NaN;
    private static final Matrix4f WORLD = new Matrix4f();
    private static final Matrix4f BONE = new Matrix4f();
    private static final Quaternionf ROTATION = new Quaternionf();
    private static final Vector3f ANGLES = new Vector3f();
    private static final Vector3f POSITION = new Vector3f();

    private CinematicCamera() {
    }

    /** View the world through {@code camera} (a glTF camera's node name, or any bone or socket) of {@code owner}'s model. */
    public static void start(IAttachmentHolder owner, String camera) {
        target = AnimatioApi.holder(owner);
        cameraName = camera;
    }

    public static void stop() {
        target = null;
        cameraName = null;
        fov = Float.NaN;
    }

    public static boolean isActive() {
        return target != null;
    }

    /**
     * Called at the end of {@code Camera.setup}: the camera pose this frame (x, y, z, yaw, pitch, roll), or
     * {@code null} when inactive or the camera cannot be resolved.
     */
    public static double @Nullable [] pose(float partialTick) {
        ControllerHolder holder = target;
        String name = cameraName;
        if (holder == null || name == null) return null;
        Level level = holder.level();
        if (!holder.isValid() || level == null || level != Minecraft.getInstance().level) {
            stop();
            return null;
        }
        holder.refreshBinding();
        GltfModel model = ClientModelManager.INSTANCE.get(holder.model());
        if (model == null) return null;
        AnimatedInstance instance = AnimatedInstance.of(holder);
        instance.prepare(holder, model, ClientClock.time(level, partialTick), 0);
        if (!modelToWorld(holder, model, instance, partialTick)) return null;
        if (!holder.controller().boneTransform(name, BONE)) return null;
        WORLD.mul(BONE);
        WORLD.getTranslation(POSITION);
        WORLD.getNormalizedRotation(ROTATION);
        // Camera.rotation = rotationYXZ(pi - yaw, -pitch, -roll); it looks down -Z with +Y up, like a glTF camera
        ROTATION.getEulerAnglesYXZ(ANGLES);
        double yaw = 180.0 - Math.toDegrees(ANGLES.y), pitch = -Math.toDegrees(ANGLES.x), roll = -Math.toDegrees(ANGLES.z);
        AnimationSet set = holder.controller().animationSet();
        ModelCamera cam = set != null ? set.camera(name) : null;
        fov = cam != null && cam.perspective() ? (float) Math.toDegrees(cam.yfov()) : Float.NaN;
        return new double[]{POSITION.x, POSITION.y, POSITION.z, yaw, pitch, roll};
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean modelToWorld(ControllerHolder holder, GltfModel model, AnimatedInstance instance, float partialTick) {
        if (holder.owner() instanceof Entity entity) {
            EntityRenderer<?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
            GltfEntityRenderer gltf = renderer instanceof GltfEntityRenderer g ? g
                    : renderer instanceof GltfPlayerRenderer p ? p.body() : null;
            if (gltf != null) {
                gltf.modelToWorld(entity, partialTick, model, WORLD);
            } else {
                Vec3 pos = entity.getPosition(partialTick);
                WORLD.translation((float) pos.x, (float) pos.y, (float) pos.z).mul(model.rootTransform());
            }
            return true;
        }
        if (!instance.hasWorldTransform()) return false;
        WORLD.set(instance.modelToWorld());
        return true;
    }

    private static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (isActive() && event.usedConfiguredFov() && !Float.isNaN(fov)) event.setFOV(fov);
    }

    private static void onRenderHand(RenderHandEvent event) {
        if (isActive()) event.setCanceled(true);
    }

    /** Wire the viewport events (client constructor). */
    public static void install(IEventBus bus) {
        bus.addListener(CinematicCamera::onComputeFov);
        bus.addListener(CinematicCamera::onRenderHand);
    }
}
