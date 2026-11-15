package dev.denismasterherobrine.animatio.dev;

import dev.denismasterherobrine.animatio.api.AnimatioApi;
import dev.denismasterherobrine.animatio.api.AnimationController;
import dev.denismasterherobrine.animatio.api.playable.AnimationRef;
import dev.denismasterherobrine.animatio.api.playback.PlayOptions;
import dev.denismasterherobrine.animatio.api.playback.TransitionConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class RobotStatueBlockEntity extends BlockEntity {
    private static final AnimationRef IDLE = AnimationRef.of("idle").looping();
    private boolean started;

    public RobotStatueBlockEntity(BlockPos pos, BlockState state) {
        super(AnimatioDev.STATUE_BE.get(), pos, state);
    }

    static void serverTick(Level level, BlockPos pos, BlockState state, RobotStatueBlockEntity be) {
        AnimationController controller = AnimatioApi.controller(be);
        if (!be.started) {
            be.started = true;
            controller.play(IDLE, PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        }
        if (level.getGameTime() % 100 == 0) controller.play(RobotEntity.WAVE, PlayOptions.defaults().priority(4));
    }
}
