package dev.denismasterherobrine.animatio.dev;

import dev.denismasterherobrine.animatio.api.AnimatioApi;
import dev.denismasterherobrine.animatio.api.AnimationController;
import dev.denismasterherobrine.animatio.api.playable.AnimationRef;
import dev.denismasterherobrine.animatio.api.playback.PlayOptions;
import dev.denismasterherobrine.animatio.api.playback.TransitionConfig;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Debug NPC. All animation logic runs on the server and is synced to clients:
 * <ul>
 *     <li>locomotion blend space (idle/walk/run by speed) on the base layer;</li>
 *     <li>blinks every 30 ticks in parallel with everything else;</li>
 *     <li>right-click: waves (parallel with blinking and walking);</li>
 *     <li>falling more than two blocks: fall pose, then fall → get_up cross-fade on landing;</li>
 *     <li>hurt and death clips.</li>
 * </ul>
 */
public class RobotEntity extends PathfinderMob implements dev.denismasterherobrine.animatio.api.GltfAnimatable {
    /** Benchmarks: {@code -Danimatio.dev.heavyRobots=true} switches every robot to the 59-bone variant (both sides read it). */
    private static final net.minecraft.resources.ResourceLocation MODEL = Boolean.getBoolean("animatio.dev.heavyRobots")
            ? net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(AnimatioDev.MODID, "robot_heavy")
            : AnimatioDev.ROBOT_MODEL;

    @Override
    public net.minecraft.resources.ResourceLocation animatioModel() {
        return MODEL;
    }

    public static final AnimationRef LOCOMOTION = AnimationRef.of("locomotion");
    public static final AnimationRef BLINK = AnimationRef.of("blink");
    public static final AnimationRef WAVE = AnimationRef.of("wave");
    public static final AnimationRef FALL = AnimationRef.of("fall");
    public static final AnimationRef GET_UP = AnimationRef.of("get_up");
    public static final AnimationRef HURT = AnimationRef.of("hurt");
    public static final AnimationRef DEATH = AnimationRef.of("death");

    private boolean started;
    private int fallTrack = -1;

    public RobotEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 1.0));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8f));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    public AnimationController animationController() {
        return AnimatioApi.controller(this);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide() || isDeadOrDying()) return;
        AnimationController controller = animationController();
        if (!started) {
            started = true;
            controller.play(LOCOMOTION, PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        }
        if (tickCount % 30 == 0 && !controller.isPlaying(BLINK)) {
            controller.play(BLINK);
        }
        if (!onGround() && fallDistance > 2f && fallTrack < 0) {
            fallTrack = controller.play(FALL.thenHoldLastFrame(), PlayOptions.defaults().priority(3));
        } else if (onGround() && fallTrack >= 0) {
            // stop(FALL) then play(GET_UP) in the same tick cross-fades, using the fall -> get_up pair in robot.anim.json
            controller.stop(fallTrack);
            controller.play(GET_UP, PlayOptions.defaults().priority(3));
            fallTrack = -1;
        }
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!level().isClientSide()) animationController().play(WAVE, PlayOptions.defaults().priority(5));
        return InteractionResult.sidedSuccess(level().isClientSide());
    }

    @Override
    protected void actuallyHurt(DamageSource source, float amount) {
        super.actuallyHurt(source, amount);
        if (!level().isClientSide() && !isDeadOrDying()) animationController().play(HURT, PlayOptions.defaults().priority(6));
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!level().isClientSide()) {
            AnimationController controller = animationController();
            controller.stopAll(TransitionConfig.of(0.15f));
            controller.play(DEATH.thenHoldLastFrame(), PlayOptions.defaults().priority(10).fadeIn(TransitionConfig.of(0.15f)));
        }
    }
}
