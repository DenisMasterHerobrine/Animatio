package dev.denismasterherobrine.animatio.net;

import dev.denismasterherobrine.animatio.anim.runtime.ControllerCommand;
import dev.denismasterherobrine.animatio.api.playable.AnimationRef;
import dev.denismasterherobrine.animatio.api.playable.BlendSpace1D;
import dev.denismasterherobrine.animatio.api.playable.BlendSpace2D;
import dev.denismasterherobrine.animatio.api.playback.BoneMask;
import dev.denismasterherobrine.animatio.api.playback.Easing;
import dev.denismasterherobrine.animatio.api.playback.PlayOptions;
import dev.denismasterherobrine.animatio.api.playable.Playable;
import dev.denismasterherobrine.animatio.api.playable.Sequence;
import dev.denismasterherobrine.animatio.api.playback.TransitionConfig;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamCodecTest {
    private static final AnimationRef IDLE = AnimationRef.of("idle");
    private static final AnimationRef WALK = AnimationRef.of("walk");

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    private static ControllerCommand roundTrip(ControllerCommand command) {
        RegistryFriendlyByteBuf buf = buffer();
        AnimatioStreamCodecs.COMMAND.encode(buf, command);
        ControllerCommand decoded = AnimatioStreamCodecs.COMMAND.decode(buf);
        assertEquals(0, buf.readableBytes(), "all bytes consumed");
        return decoded;
    }

    @Test
    void playWithEverything() {
        BlendSpace1D loco = BlendSpace1D.builder("loco", "speed").sample(0, IDLE).sample(2, WALK.withSpeed(1.5f)).smoothing(0.1f).build();
        BlendSpace2D move = BlendSpace2D.builder("move", "x", "z").sample(0, 0, IDLE).sample(0, 1, WALK).sample(1, 0, WALK).build();
        Playable seq = AnimationRef.of("jump").times(2)
                .thenWaitSecs(1.5f)
                .thenPlay(loco, TransitionConfig.of(0.3f, Easing.LINEAR))
                .thenPlayFor(move, 4f)
                .thenPlay(AnimationRef.of("fall").looping())
                .named("combo");
        PlayOptions options = PlayOptions.defaults().priority(-3).fadeIn(TransitionConfig.of(0.25f)).fadeOut(TransitionConfig.NONE)
                .startAt(0.5f).speed(2f).weight(0.75f).mask(BoneMask.subtree("arm").excluding("hand")).additive(true);
        ControllerCommand play = new ControllerCommand.Play(42, seq, options);
        assertEquals(play, roundTrip(play));
        assertEquals(new ControllerCommand.Play(1, IDLE.thenHoldLastFrame(), PlayOptions.defaults()),
                roundTrip(new ControllerCommand.Play(1, IDLE.thenHoldLastFrame(), PlayOptions.defaults())));
        Playable repeat = Sequence.delay(1).thenPlay(IDLE).thenRepeat();
        assertEquals(new ControllerCommand.Play(2, repeat, PlayOptions.defaults()),
                roundTrip(new ControllerCommand.Play(2, repeat, PlayOptions.defaults())));
    }

    @Test
    void otherCommands() {
        List<ControllerCommand> commands = List.of(
                new ControllerCommand.Stop(ControllerCommand.Target.key("walk"), TransitionConfig.of(0.5f)),
                new ControllerCommand.Stop(ControllerCommand.Target.id(-7), null),
                new ControllerCommand.Pause(ControllerCommand.Target.ALL_TRACKS),
                new ControllerCommand.Resume(ControllerCommand.Target.key("idle")),
                new ControllerCommand.SetSpeed(ControllerCommand.Target.id(3), 0.5f),
                new ControllerCommand.Reset(),
                new ControllerCommand.SetParameter("speed", 3.5f),
                new ControllerCommand.SetGlobalSpeed(1.25f));
        for (ControllerCommand c : commands) assertEquals(c, roundTrip(c));
    }

    @Test
    void payload() {
        ControllerSyncPayload p = ControllerSyncPayload.forBlockEntity(new BlockPos(1, -2, 3), 1234.5,
                List.of(new ControllerCommand.Reset(), new ControllerCommand.SetParameter("a", 1f)));
        RegistryFriendlyByteBuf buf = buffer();
        ControllerSyncPayload.STREAM_CODEC.encode(buf, p);
        assertEquals(p, ControllerSyncPayload.STREAM_CODEC.decode(buf));
    }
}
