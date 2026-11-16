package dev.denismasterherobrine.animatio.common;

import dev.denismasterherobrine.animatio.anim.model.AnimationSet;
import dev.denismasterherobrine.animatio.anim.runtime.ControllerImpl;
import dev.denismasterherobrine.animatio.data.ModelConfigs;
import dev.denismasterherobrine.animatio.data.ServerModelRepository;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The animation state attached to one entity or block entity: its controller plus the wiring to time, model data,
 * networking and (on the client) rendering.
 */
public final class ControllerHolder {
    private final IAttachmentHolder owner;
    private final ControllerImpl controller;
    private final boolean client;
    private @Nullable ResourceLocation model;
    private int boundGeneration = Integer.MIN_VALUE;
    private int boundConfigVersion = Integer.MIN_VALUE;
    private long refreshedFrame = -1;
    /** Client render state (owned by the client package; opaque to common code). */
    public @Nullable Object renderState;
    /** Client-only hook run every tick before the controller advances (parameter providers). */
    public java.util.function.@Nullable Consumer<ControllerHolder> clientTickHook;

    private ControllerHolder(IAttachmentHolder owner, boolean client) {
        this.owner = owner;
        this.client = client;
        this.controller = new ControllerImpl(client ? ControllerImpl.Side.CLIENT : ControllerImpl.Side.SERVER);
        controller.setTimeSource(this::now);
        if (!client) controller.setSink(cmd -> SyncManager.enqueue(this, cmd));
    }

    /** Attachment factory. */
    public static ControllerHolder create(IAttachmentHolder owner) {
        boolean client = isClientSide(owner);
        ControllerHolder holder = new ControllerHolder(owner, client);
        if (client) {
            ClientBridge bridge = ClientBridge.Holder.get();
            if (bridge != null) bridge.onCreated(holder);
        }
        ControllerTicker.register(holder);
        holder.refreshBinding();
        return holder;
    }

    private static boolean isClientSide(IAttachmentHolder owner) {
        if (owner instanceof Entity e) return e.level().isClientSide();
        if (owner instanceof BlockEntity be && be.getLevel() != null) return be.getLevel().isClientSide();
        // no level yet: decide by thread (the render thread is the only client logic thread)
        return FMLEnvironment.dist.isClient() && "Render thread".equals(Thread.currentThread().getName());
    }

    public ControllerImpl controller() {
        return controller;
    }

    public Object owner() {
        return owner;
    }

    public boolean isClient() {
        return client;
    }

    public @Nullable Level level() {
        if (owner instanceof Entity e) return e.level();
        if (owner instanceof BlockEntity be) return be.getLevel();
        return null;
    }

    /** False once the owner was removed from its level. */
    public boolean isValid() {
        if (owner instanceof Entity e) return !e.isRemoved();
        if (owner instanceof BlockEntity be) return !be.isRemoved() && be.getLevel() != null;
        return true;
    }

    /** Animation clock in seconds. */
    public double now() {
        Level level = level();
        if (level == null) return controller.clock();
        if (client) {
            ClientBridge bridge = ClientBridge.Holder.get();
            if (bridge != null) return bridge.clientTime(level);
        }
        return level.getGameTime() / 20.0;
    }

    public @Nullable ResourceLocation model() {
        return model;
    }

    /** Bind to the current model data, picking up model changes and reloads. Cheap when nothing changed. */
    public void refreshBinding() {
        if (client) {
            // render code calls this several times per object per frame; once per frame is enough
            ClientBridge bridge = ClientBridge.Holder.get();
            long frame = bridge != null ? bridge.frame() : -1;
            if (frame >= 0 && frame == refreshedFrame) return;
            refreshedFrame = frame;
        }
        ResourceLocation id = ModelBindings.modelFor(owner, client);
        int generation = generation();
        int configVersion = ModelConfigs.version();
        if (Objects.equals(id, model) && generation == boundGeneration && configVersion == boundConfigVersion) return;
        model = id;
        boundGeneration = generation;
        boundConfigVersion = configVersion;
        AnimationSet set = null;
        if (id != null) {
            if (client) {
                ClientBridge bridge = ClientBridge.Holder.get();
                set = bridge != null ? bridge.animationSet(id) : null;
            } else {
                set = ServerModelRepository.get(id);
            }
        }
        controller.bind(set);
    }

    private int generation() {
        if (!client) return ServerModelRepository.generation();
        ClientBridge bridge = ClientBridge.Holder.get();
        return bridge != null ? bridge.generation() : 0;
    }

    /** Per-tick update: rebind if needed and advance time. */
    public void tick() {
        refreshBinding();
        if (clientTickHook != null) clientTickHook.accept(this);
        controller.update(now());
    }
}
