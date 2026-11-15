package dev.denismasterherobrine.animatio.client.render;

import dev.denismasterherobrine.animatio.anim.model.AnimationSet;
import dev.denismasterherobrine.animatio.anim.runtime.ControllerImpl;
import dev.denismasterherobrine.animatio.api.material.MaterialOverride;
import dev.denismasterherobrine.animatio.client.model.GltfModel;
import dev.denismasterherobrine.animatio.common.ControllerHolder;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Client render state of one animated object: the skinning palette of its last evaluated pose, its last
 * model-to-world transform (for events, attachments and early culling) and its material overrides.
 * <p>
 * Pose evaluation is split in two: {@link #beginEvaluation} (render thread: decides whether this frame needs a new
 * pose and prepares buffers) and {@link #evaluate} (pure computation on this instance's own state, safe on a
 * worker thread). {@link PoseScheduler} runs the second part for many instances in parallel.
 */
public final class AnimatedInstance {
    private static final double REDUCED_INTERVAL = 1.0 / 15.0;
    private static final double MINIMAL_INTERVAL = 1.0 / 5.0;
    private static final Vector3f SCALE_SCRATCH = new Vector3f(); // render thread only

    private @Nullable GltfModel model;
    private @Nullable ControllerHolder holder;
    private float[] palette = new float[0];
    private long evaluatedFrame = -1;
    private double evaluatedAt = Double.NaN;
    private long recordedFrame = -1;
    private final Matrix4f modelToWorld = new Matrix4f();
    private boolean hasWorldTransform;
    private double centerX, centerY, centerZ;
    private float radius;
    private final Map<String, MaterialOverride> overrides = new HashMap<>();
    private MaterialOverride allOverride = MaterialOverride.none();
    /** LOD multiplier from client settings; 0 disables reduced update rates. */
    public static float lodDistanceScale = 1f;

    public static AnimatedInstance of(ControllerHolder holder) {
        if (holder.renderState instanceof AnimatedInstance instance) return instance;
        AnimatedInstance instance = new AnimatedInstance();
        holder.renderState = instance;
        return instance;
    }

    public static @Nullable AnimatedInstance getIfPresent(ControllerHolder holder) {
        return holder.renderState instanceof AnimatedInstance instance ? instance : null;
    }

    // ------------------------------------------------------------------ preparation

    /**
     * Bring the controller to {@code now} and make sure the palette reflects this frame (evaluated at most once per
     * frame; less often for distant objects). Usually the {@link PoseScheduler} already evaluated it in parallel.
     */
    public void prepare(ControllerHolder holder, GltfModel model, double now, double distance) {
        PoseScheduler.awaitPending();
        this.holder = holder;
        holder.refreshBinding();
        prepare(holder.controller(), model, now, distance);
        long frame = ClientClock.frame();
        if (recordedFrame != frame) {
            recordedFrame = frame;
            PoseScheduler.markRendered(this);
        }
    }

    /** Same as {@link #prepare(ControllerHolder, GltfModel, double, double)} for a controller without an owner. */
    public void prepare(ControllerImpl controller, GltfModel model, double now, double distance) {
        controller.update(now);
        if (beginEvaluation(model, now, distance)) evaluate(controller, model);
    }

    /**
     * Render thread: decide whether this frame needs a new pose and prepare buffers.
     *
     * @return true if {@link #evaluate} must run
     */
    boolean beginEvaluation(GltfModel model, double now, double distance) {
        boolean changed = this.model != model;
        if (changed) {
            this.model = model;
            this.palette = new float[model.paletteSize() * 16];
            this.evaluatedAt = Double.NaN;
            this.evaluatedFrame = -1;
        }
        long frame = ClientClock.frame();
        if (frame == evaluatedFrame) return false;
        evaluatedFrame = frame;
        double interval = updateInterval(model, distance);
        if (!changed && !Double.isNaN(evaluatedAt) && now >= evaluatedAt && now - evaluatedAt < interval) return false;
        evaluatedAt = now;
        return true;
    }

    /** Compute the pose and palette. Touches only this instance and its controller; safe on a worker thread. */
    void evaluate(ControllerImpl controller, GltfModel model) {
        long t0 = RenderStats.ENABLED ? System.nanoTime() : 0L;
        AnimationSet set = controller.animationSet();
        if (set != null && set.skeleton() == model.data().skeleton() && controller.evaluate()) {
            model.data().computePalette(controller.globals(), palette);
        } else {
            System.arraycopy(model.restPalette(), 0, palette, 0, palette.length);
        }
        if (RenderStats.ENABLED) RenderStats.recordEvaluation(System.nanoTime() - t0);
    }

    /**
     * Render thread, at the start of a frame: if this instance (rendered last frame) is still in view, bring its
     * controller up to date and decide whether it needs a new pose.
     *
     * @return true if {@link #evaluateScheduled()} must run
     */
    boolean schedule(float partialTick, Frustum frustum, Vec3 camera) {
        ControllerHolder h = holder;
        GltfModel m = model;
        if (h == null || m == null || !hasWorldTransform || !h.isValid()) return false;
        Level level = h.level();
        if (level == null) return false;
        if (!frustum.isVisible(new AABB(centerX - radius, centerY - radius, centerZ - radius,
                centerX + radius, centerY + radius, centerZ + radius))) {
            return false;
        }
        double distance = Math.sqrt(camera.distanceToSqr(centerX, centerY, centerZ));
        h.refreshBinding();
        ControllerImpl controller = h.controller();
        controller.update(ClientClock.time(level, partialTick));
        controller.prepareForEvaluation();
        return beginEvaluation(m, controller.clock(), distance);
    }

    /** Worker thread: the evaluation decided by {@link #schedule}. */
    void evaluateScheduled() {
        ControllerHolder h = holder;
        GltfModel m = model;
        if (h != null && m != null) evaluate(h.controller(), m);
    }

    private static double updateInterval(GltfModel model, double distance) {
        if (lodDistanceScale <= 0f) return 0;
        var render = model.definition().render();
        if (render.minimalRateDistance() > 0 && distance > render.minimalRateDistance() * lodDistanceScale) return MINIMAL_INTERVAL;
        if (render.reducedRateDistance() > 0 && distance > render.reducedRateDistance() * lodDistanceScale) return REDUCED_INTERVAL;
        return 0;
    }

    public float[] palette() {
        return palette;
    }

    public @Nullable GltfModel model() {
        return model;
    }

    // ------------------------------------------------------------------ world transform

    /** Record where the model was drawn (entity/block position and the transform applied before the skeleton). */
    public void setWorldTransform(double x, double y, double z, Matrix4f root) {
        modelToWorld.translation((float) x, (float) y, (float) z).mul(root);
        hasWorldTransform = true;
        GltfModel m = model;
        if (m != null) {
            // bounding sphere for early culling: the model's yaw-independent cylinder, scaled like the root
            float[] c = m.cullCylinder();
            Vector3f s = root.getScale(SCALE_SCRATCH);
            float scale = Math.max(s.x, Math.max(s.y, s.z));
            float halfHeight = (c[1] - c[0]) * 0.5f * scale;
            centerX = x;
            centerY = y + (c[0] + c[1]) * 0.5f * scale;
            centerZ = z;
            radius = (float) Math.sqrt(halfHeight * halfHeight + c[2] * scale * c[2] * scale) + 0.5f;
        }
    }

    public boolean hasWorldTransform() {
        return hasWorldTransform;
    }

    public Matrix4f modelToWorld() {
        return modelToWorld;
    }

    /** World position of a bone or socket from the last rendered frame. */
    public boolean bonePosition(ControllerHolder holder, String bone, Vector3f out, Matrix4f scratch) {
        if (!hasWorldTransform || !holder.controller().boneTransform(bone, scratch)) return false;
        modelToWorld.mul(scratch, scratch);
        scratch.getTranslation(out);
        return true;
    }

    // ------------------------------------------------------------------ material overrides

    public void setOverride(String material, @Nullable MaterialOverride override) {
        if (override == null || override.isNone()) overrides.remove(material);
        else overrides.put(material, override);
    }

    public void setOverrideAll(@Nullable MaterialOverride override) {
        allOverride = override == null ? MaterialOverride.none() : override;
    }

    public void clearOverrides() {
        overrides.clear();
        allOverride = MaterialOverride.none();
    }

    /** Override for a material: the per-material one if set, else the all-materials one. */
    public MaterialOverride override(String material) {
        if (overrides.isEmpty()) return allOverride;
        MaterialOverride o = overrides.get(material);
        return o != null ? o : allOverride;
    }
}
