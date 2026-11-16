package dev.denismasterherobrine.animatio.common;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.denismasterherobrine.animatio.anim.model.AnimationSet;
import dev.denismasterherobrine.animatio.anim.model.ClipInfo;
import dev.denismasterherobrine.animatio.anim.runtime.ControllerImpl;
import dev.denismasterherobrine.animatio.api.AnimatioApi;
import dev.denismasterherobrine.animatio.api.AnimationController;
import dev.denismasterherobrine.animatio.api.playable.AnimationRef;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.Collection;
import java.util.function.BiConsumer;

/**
 * {@code /animatio} — drive controllers from the server for testing models (permission level 2).
 * <pre>
 * /animatio play &lt;targets&gt; &lt;animation&gt; [loop]
 * /animatio stop &lt;targets&gt; [animation]
 * /animatio pause|resume &lt;targets&gt; [animation]
 * /animatio param &lt;targets&gt; &lt;name&gt; &lt;value&gt;
 * /animatio speed &lt;targets&gt; &lt;value&gt;
 * /animatio reset &lt;targets&gt;
 * /animatio info &lt;targets&gt;
 * </pre>
 */
public final class AnimatioCommand {
    private AnimatioCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("animatio").requires(s -> s.hasPermission(2))
                .then(Commands.literal("play").then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("animation", StringArgumentType.string())
                                .executes(ctx -> play(ctx, false))
                                .then(Commands.argument("loop", BoolArgumentType.bool())
                                        .executes(ctx -> play(ctx, BoolArgumentType.getBool(ctx, "loop")))))))
                .then(Commands.literal("stop").then(Commands.argument("targets", EntityArgument.entities())
                        .executes(ctx -> each(ctx, (e, c) -> c.stopAll()))
                        .then(Commands.argument("animation", StringArgumentType.string())
                                .executes(ctx -> each(ctx, (e, c) -> c.stop(anim(ctx)))))))
                .then(Commands.literal("pause").then(Commands.argument("targets", EntityArgument.entities())
                        .executes(ctx -> each(ctx, (e, c) -> c.pauseAll()))
                        .then(Commands.argument("animation", StringArgumentType.string())
                                .executes(ctx -> each(ctx, (e, c) -> c.pause(anim(ctx)))))))
                .then(Commands.literal("resume").then(Commands.argument("targets", EntityArgument.entities())
                        .executes(ctx -> each(ctx, (e, c) -> c.resumeAll()))
                        .then(Commands.argument("animation", StringArgumentType.string())
                                .executes(ctx -> each(ctx, (e, c) -> c.resume(anim(ctx)))))))
                .then(Commands.literal("param").then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("name", StringArgumentType.string())
                                .then(Commands.argument("value", FloatArgumentType.floatArg())
                                        .executes(ctx -> each(ctx, (e, c) -> c.setParameter(StringArgumentType.getString(ctx, "name"),
                                                FloatArgumentType.getFloat(ctx, "value"))))))))
                .then(Commands.literal("speed").then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("value", FloatArgumentType.floatArg(0f, 100f))
                                .executes(ctx -> each(ctx, (e, c) -> c.setGlobalSpeed(FloatArgumentType.getFloat(ctx, "value")))))))
                .then(Commands.literal("reset").then(Commands.argument("targets", EntityArgument.entities())
                        .executes(ctx -> each(ctx, (e, c) -> c.reset()))))
                .then(Commands.literal("info").then(Commands.argument("targets", EntityArgument.entities())
                        .executes(AnimatioCommand::info)))
                .then(Commands.literal("morph").then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("target", StringArgumentType.string())
                                .then(Commands.literal("clear")
                                        .executes(ctx -> each(ctx, (e, c) -> c.clearMorphWeight(StringArgumentType.getString(ctx, "target")))))
                                .then(Commands.argument("weight", FloatArgumentType.floatArg(-10f, 10f))
                                        .executes(ctx -> each(ctx, (e, c) -> c.setMorphWeight(StringArgumentType.getString(ctx, "target"),
                                                FloatArgumentType.getFloat(ctx, "weight"))))))))
                .then(Commands.literal("model").then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.literal("reset").executes(ctx -> model(ctx, null)))
                        .then(Commands.argument("model", ResourceLocationArgument.id())
                                .executes(ctx -> model(ctx, ResourceLocationArgument.getId(ctx, "model"))))))
                .then(Commands.literal("camera").then(Commands.argument("viewers", EntityArgument.players())
                        .then(Commands.literal("stop").executes(ctx -> {
                            for (ServerPlayer p : EntityArgument.getPlayers(ctx, "viewers")) AnimatioApi.stopCameraView(p);
                            return 1;
                        }))
                        .then(Commands.argument("owner", EntityArgument.entity())
                                .then(Commands.argument("camera", StringArgumentType.string()).executes(ctx -> {
                                    Entity owner = EntityArgument.getEntity(ctx, "owner");
                                    String camera = StringArgumentType.getString(ctx, "camera");
                                    for (ServerPlayer p : EntityArgument.getPlayers(ctx, "viewers")) AnimatioApi.startCameraView(p, owner, camera);
                                    return 1;
                                }))))));
    }

    private static int model(CommandContext<CommandSourceStack> ctx, net.minecraft.resources.@org.jetbrains.annotations.Nullable ResourceLocation model)
            throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(ctx, "targets");
        for (Entity e : targets) AnimatioApi.setModel(e, model);
        int n = targets.size();
        ctx.getSource().sendSuccess(() -> Component.literal((model == null ? "Reset the model of " : "Set model " + model + " on ")
                + n + " entit" + (n == 1 ? "y" : "ies")), true);
        return n;
    }

    private static AnimationRef anim(CommandContext<CommandSourceStack> ctx) {
        return AnimationRef.of(StringArgumentType.getString(ctx, "animation"));
    }

    private static int play(CommandContext<CommandSourceStack> ctx, boolean loop) throws CommandSyntaxException {
        AnimationRef ref = anim(ctx);
        AnimationRef played = loop ? ref.looping() : ref;
        return each(ctx, (e, c) -> c.play(played));
    }

    private static int each(CommandContext<CommandSourceStack> ctx, BiConsumer<Entity, AnimationController> action) throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(ctx, "targets");
        for (Entity e : targets) action.accept(e, AnimatioApi.controller(e));
        int n = targets.size();
        ctx.getSource().sendSuccess(() -> Component.literal("Applied to " + n + " entit" + (n == 1 ? "y" : "ies")), false);
        return n;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(ctx, "targets");
        for (Entity e : targets) {
            ControllerHolder holder = AnimatioApi.holder(e);
            ControllerImpl c = holder.controller();
            AnimationSet set = c.animationSet();
            StringBuilder sb = new StringBuilder();
            sb.append(e.getName().getString()).append(": model ").append(holder.model() == null ? "none" : holder.model());
            if (set == null) {
                sb.append(" (not loaded on the server)");
            } else {
                sb.append(", clips:");
                for (ClipInfo ci : set.clips()) sb.append(' ').append(ci.name()).append(String.format(" (%.2fs%s)", ci.duration(), ci.loop() ? ", loop" : ""));
                if (!set.blendSpaces().isEmpty()) sb.append(", blend spaces: ").append(String.join(", ", set.blendSpaces().keySet()));
            }
            sb.append(", tracks: ").append(c.trackCount());
            ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        }
        return targets.size();
    }
}
