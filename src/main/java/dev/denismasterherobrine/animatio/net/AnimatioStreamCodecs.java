package dev.denismasterherobrine.animatio.net;

import dev.denismasterherobrine.animatio.anim.runtime.ControllerCommand;
import dev.denismasterherobrine.animatio.api.playable.AnimationRef;
import dev.denismasterherobrine.animatio.api.playable.BlendSpace;
import dev.denismasterherobrine.animatio.api.playable.BlendSpace1D;
import dev.denismasterherobrine.animatio.api.playable.BlendSpace2D;
import dev.denismasterherobrine.animatio.api.playback.BoneMask;
import dev.denismasterherobrine.animatio.api.playback.Easing;
import dev.denismasterherobrine.animatio.api.event.EventMarker;
import dev.denismasterherobrine.animatio.api.playback.PlayOptions;
import dev.denismasterherobrine.animatio.api.playable.Playable;
import dev.denismasterherobrine.animatio.api.playable.Sequence;
import dev.denismasterherobrine.animatio.api.playback.TransitionConfig;
import dev.denismasterherobrine.animatio.event.EventTypes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Network encoding of playables, options and controller commands.
 */
public final class AnimatioStreamCodecs {
    private static final int MAX_LIST = 4096;

    private AnimatioStreamCodecs() {
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ControllerCommand> COMMAND =
            StreamCodec.of(AnimatioStreamCodecs::writeCommand, AnimatioStreamCodecs::readCommand);

    public static final StreamCodec<RegistryFriendlyByteBuf, Playable> PLAYABLE =
            StreamCodec.of(AnimatioStreamCodecs::writePlayable, AnimatioStreamCodecs::readPlayable);

    // ------------------------------------------------------------------ primitives

    private static int readCount(RegistryFriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_LIST) throw new IllegalArgumentException("List too long: " + n);
        return n;
    }

    private static void writeTransition(RegistryFriendlyByteBuf buf, TransitionConfig c) {
        buf.writeFloat(c.duration());
        buf.writeByte(c.easing().ordinal());
    }

    private static TransitionConfig readTransition(RegistryFriendlyByteBuf buf) {
        float duration = buf.readFloat();
        int easing = buf.readByte();
        Easing[] values = Easing.values();
        return new TransitionConfig(duration, values[Math.floorMod(easing, values.length)]);
    }

    private static void writeOptionalTransition(RegistryFriendlyByteBuf buf, @Nullable TransitionConfig c) {
        buf.writeBoolean(c != null);
        if (c != null) writeTransition(buf, c);
    }

    private static @Nullable TransitionConfig readOptionalTransition(RegistryFriendlyByteBuf buf) {
        return buf.readBoolean() ? readTransition(buf) : null;
    }

    private static void writeStrings(RegistryFriendlyByteBuf buf, java.util.Collection<String> strings) {
        buf.writeVarInt(strings.size());
        for (String s : strings) buf.writeUtf(s);
    }

    private static List<String> readStrings(RegistryFriendlyByteBuf buf) {
        int n = readCount(buf);
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(buf.readUtf());
        return out;
    }

    // ------------------------------------------------------------------ playables

    public static void writeRef(RegistryFriendlyByteBuf buf, AnimationRef ref) {
        buf.writeUtf(ref.name());
        buf.writeVarInt(ref.loops() + 1);
        buf.writeFloat(ref.speed());
        buf.writeVarInt(ref.events().size());
        for (EventMarker m : ref.events()) {
            buf.writeFloat(m.time());
            EventTypes.STREAM_CODEC.encode(buf, m.event());
        }
    }

    public static AnimationRef readRef(RegistryFriendlyByteBuf buf) {
        String name = buf.readUtf();
        int loops = buf.readVarInt() - 1;
        float speed = buf.readFloat();
        int n = readCount(buf);
        List<EventMarker> events = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            float time = buf.readFloat();
            events.add(new EventMarker(time, EventTypes.STREAM_CODEC.decode(buf)));
        }
        return AnimationRef.create(name, loops, speed, events);
    }

    private static void writeBlendSpace(RegistryFriendlyByteBuf buf, BlendSpace space) {
        boolean twoD = space instanceof BlendSpace2D;
        buf.writeBoolean(twoD);
        buf.writeUtf(space.name());
        buf.writeFloat(space.smoothing());
        buf.writeUtf(space.parameterX());
        if (twoD) buf.writeUtf(space.parameterY());
        buf.writeVarInt(space.size());
        for (int i = 0; i < space.size(); i++) {
            if (space instanceof BlendSpace2D b) {
                buf.writeFloat(b.positionX(i));
                buf.writeFloat(b.positionY(i));
            } else {
                buf.writeFloat(((BlendSpace1D) space).position(i));
            }
            writeRef(buf, space.clip(i));
        }
    }

    private static BlendSpace readBlendSpace(RegistryFriendlyByteBuf buf) {
        boolean twoD = buf.readBoolean();
        String name = buf.readUtf();
        float smoothing = buf.readFloat();
        String px = buf.readUtf();
        if (twoD) {
            BlendSpace2D.Builder b = BlendSpace2D.builder(name, px, buf.readUtf()).smoothing(smoothing);
            int n = readCount(buf);
            for (int i = 0; i < n; i++) {
                float x = buf.readFloat(), y = buf.readFloat();
                b.sample(x, y, readRef(buf));
            }
            return b.build();
        }
        BlendSpace1D.Builder b = BlendSpace1D.builder(name, px).smoothing(smoothing);
        int n = readCount(buf);
        for (int i = 0; i < n; i++) {
            float x = buf.readFloat();
            b.sample(x, readRef(buf));
        }
        return b.build();
    }

    private static final int REF = 0, SEQUENCE = 1, BLEND = 2;
    private static final int STEP_PLAY = 0, STEP_WAIT = 1, STEP_HOLD = 2, STEP_FIRE = 3, STEP_REPEAT = 4;

    public static void writePlayable(RegistryFriendlyByteBuf buf, Playable p) {
        switch (p) {
            case AnimationRef ref -> {
                buf.writeByte(REF);
                writeRef(buf, ref);
            }
            case BlendSpace space -> {
                buf.writeByte(BLEND);
                writeBlendSpace(buf, space);
            }
            case Sequence seq -> {
                buf.writeByte(SEQUENCE);
                buf.writeBoolean(seq.name() != null);
                if (seq.name() != null) buf.writeUtf(seq.name());
                buf.writeVarInt(seq.steps().size());
                for (Sequence.Step step : seq.steps()) writeStep(buf, step);
            }
        }
    }

    private static void writeStep(RegistryFriendlyByteBuf buf, Sequence.Step step) {
        switch (step) {
            case Sequence.Play play -> {
                buf.writeByte(STEP_PLAY);
                writePlayable(buf, play.target());
                writeOptionalTransition(buf, play.blendIn());
                buf.writeFloat(play.duration());
            }
            case Sequence.Wait wait -> {
                buf.writeByte(STEP_WAIT);
                buf.writeFloat(wait.seconds());
            }
            case Sequence.Hold hold -> buf.writeByte(STEP_HOLD);
            case Sequence.Fire fire -> {
                buf.writeByte(STEP_FIRE);
                EventTypes.STREAM_CODEC.encode(buf, fire.event());
            }
            case Sequence.Repeat repeat -> buf.writeByte(STEP_REPEAT);
        }
    }

    public static Playable readPlayable(RegistryFriendlyByteBuf buf) {
        int kind = buf.readByte();
        return switch (kind) {
            case REF -> readRef(buf);
            case BLEND -> readBlendSpace(buf);
            case SEQUENCE -> {
                String name = buf.readBoolean() ? buf.readUtf() : null;
                int n = readCount(buf);
                List<Sequence.Step> steps = new ArrayList<>(n);
                for (int i = 0; i < n; i++) steps.add(readStep(buf));
                yield Sequence.create(steps, name);
            }
            default -> throw new IllegalArgumentException("Unknown playable kind " + kind);
        };
    }

    private static Sequence.Step readStep(RegistryFriendlyByteBuf buf) {
        int kind = buf.readByte();
        return switch (kind) {
            case STEP_PLAY -> {
                Playable target = readPlayable(buf);
                TransitionConfig blend = readOptionalTransition(buf);
                yield new Sequence.Play(target, blend, buf.readFloat());
            }
            case STEP_WAIT -> new Sequence.Wait(buf.readFloat());
            case STEP_HOLD -> new Sequence.Hold();
            case STEP_FIRE -> new Sequence.Fire(EventTypes.STREAM_CODEC.decode(buf));
            case STEP_REPEAT -> new Sequence.Repeat();
            default -> throw new IllegalArgumentException("Unknown sequence step " + kind);
        };
    }

    // ------------------------------------------------------------------ options

    private static void writeMask(RegistryFriendlyByteBuf buf, BoneMask mask) {
        buf.writeBoolean(mask.includesAll());
        writeStrings(buf, mask.bones());
        writeStrings(buf, mask.subtrees());
        writeStrings(buf, mask.excludedBones());
        writeStrings(buf, mask.excludedSubtrees());
    }

    private static BoneMask readMask(RegistryFriendlyByteBuf buf) {
        boolean all = buf.readBoolean();
        return BoneMask.create(all, readStrings(buf), readStrings(buf), readStrings(buf), readStrings(buf));
    }

    public static void writeOptions(RegistryFriendlyByteBuf buf, PlayOptions o) {
        buf.writeVarInt(o.priority());
        writeOptionalTransition(buf, o.fadeIn());
        writeOptionalTransition(buf, o.fadeOut());
        buf.writeFloat(o.startAt());
        buf.writeFloat(o.speed());
        buf.writeFloat(o.weight());
        buf.writeBoolean(o.mask() != null);
        if (o.mask() != null) writeMask(buf, o.mask());
        buf.writeBoolean(o.additive());
    }

    public static PlayOptions readOptions(RegistryFriendlyByteBuf buf) {
        int priority = buf.readVarInt();
        TransitionConfig fadeIn = readOptionalTransition(buf);
        TransitionConfig fadeOut = readOptionalTransition(buf);
        float startAt = buf.readFloat();
        float speed = buf.readFloat();
        float weight = buf.readFloat();
        BoneMask mask = buf.readBoolean() ? readMask(buf) : null;
        boolean additive = buf.readBoolean();
        return new PlayOptions(priority, fadeIn, fadeOut, startAt, speed, weight, mask, additive);
    }

    // ------------------------------------------------------------------ commands

    private static final int CMD_PLAY = 0, CMD_STOP = 1, CMD_PAUSE = 2, CMD_RESUME = 3, CMD_SPEED = 4,
            CMD_RESET = 5, CMD_PARAM = 6, CMD_GLOBAL_SPEED = 7;

    private static void writeTarget(RegistryFriendlyByteBuf buf, ControllerCommand.Target t) {
        buf.writeByte(t.kind());
        if (t.kind() == ControllerCommand.Target.ID) buf.writeVarInt(t.id());
        else if (t.kind() == ControllerCommand.Target.KEY) buf.writeUtf(t.key());
    }

    private static ControllerCommand.Target readTarget(RegistryFriendlyByteBuf buf) {
        int kind = buf.readByte();
        return switch (kind) {
            case ControllerCommand.Target.ALL -> ControllerCommand.Target.ALL_TRACKS;
            case ControllerCommand.Target.ID -> ControllerCommand.Target.id(buf.readVarInt());
            case ControllerCommand.Target.KEY -> ControllerCommand.Target.key(buf.readUtf());
            default -> throw new IllegalArgumentException("Unknown target kind " + kind);
        };
    }

    public static void writeCommand(RegistryFriendlyByteBuf buf, ControllerCommand command) {
        switch (command) {
            case ControllerCommand.Play p -> {
                buf.writeByte(CMD_PLAY);
                buf.writeVarInt(p.id());
                writePlayable(buf, p.playable());
                writeOptions(buf, p.options());
            }
            case ControllerCommand.Stop s -> {
                buf.writeByte(CMD_STOP);
                writeTarget(buf, s.target());
                writeOptionalTransition(buf, s.fade());
            }
            case ControllerCommand.Pause p -> {
                buf.writeByte(CMD_PAUSE);
                writeTarget(buf, p.target());
            }
            case ControllerCommand.Resume r -> {
                buf.writeByte(CMD_RESUME);
                writeTarget(buf, r.target());
            }
            case ControllerCommand.SetSpeed s -> {
                buf.writeByte(CMD_SPEED);
                writeTarget(buf, s.target());
                buf.writeFloat(s.speed());
            }
            case ControllerCommand.Reset r -> buf.writeByte(CMD_RESET);
            case ControllerCommand.SetParameter p -> {
                buf.writeByte(CMD_PARAM);
                buf.writeUtf(p.name());
                buf.writeFloat(p.value());
            }
            case ControllerCommand.SetGlobalSpeed g -> {
                buf.writeByte(CMD_GLOBAL_SPEED);
                buf.writeFloat(g.speed());
            }
        }
    }

    public static ControllerCommand readCommand(RegistryFriendlyByteBuf buf) {
        int kind = buf.readByte();
        return switch (kind) {
            case CMD_PLAY -> {
                int id = buf.readVarInt();
                Playable playable = readPlayable(buf);
                yield new ControllerCommand.Play(id, playable, readOptions(buf));
            }
            case CMD_STOP -> {
                ControllerCommand.Target target = readTarget(buf);
                yield new ControllerCommand.Stop(target, readOptionalTransition(buf));
            }
            case CMD_PAUSE -> new ControllerCommand.Pause(readTarget(buf));
            case CMD_RESUME -> new ControllerCommand.Resume(readTarget(buf));
            case CMD_SPEED -> {
                ControllerCommand.Target target = readTarget(buf);
                yield new ControllerCommand.SetSpeed(target, buf.readFloat());
            }
            case CMD_RESET -> new ControllerCommand.Reset();
            case CMD_PARAM -> {
                String name = buf.readUtf();
                yield new ControllerCommand.SetParameter(name, buf.readFloat());
            }
            case CMD_GLOBAL_SPEED -> new ControllerCommand.SetGlobalSpeed(buf.readFloat());
            default -> throw new IllegalArgumentException("Unknown controller command " + kind);
        };
    }
}
