package dev.denismasterherobrine.animatio.client;

import dev.denismasterherobrine.animatio.anim.runtime.BaseEventContext;
import dev.denismasterherobrine.animatio.client.render.AnimatedInstance;
import dev.denismasterherobrine.animatio.common.ControllerHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Event context for client-side controllers: bone positions come from the last rendered frame.
 */
final class ClientEventContext extends BaseEventContext {
    private final ControllerHolder holder;
    private final Matrix4f scratch = new Matrix4f();

    ClientEventContext(ControllerHolder holder) {
        this.holder = holder;
    }

    @Override
    public @Nullable Level level() {
        return holder.level();
    }

    @Override
    public @Nullable Object owner() {
        return holder.owner();
    }

    @Override
    public boolean bonePosition(String boneOrSocket, Vector3f out) {
        AnimatedInstance instance = AnimatedInstance.getIfPresent(holder);
        if (instance != null && instance.bonePosition(holder, boneOrSocket, out, scratch)) return true;
        origin(out);
        return false;
    }

    @Override
    public void origin(Vector3f out) {
        Object owner = holder.owner();
        if (owner instanceof Entity e) out.set((float) e.getX(), (float) e.getY(), (float) e.getZ());
        else if (owner instanceof BlockEntity be) out.set(be.getBlockPos().getX() + 0.5f, be.getBlockPos().getY(), be.getBlockPos().getZ() + 0.5f);
        else out.set(0f);
    }
}
