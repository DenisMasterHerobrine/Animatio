package dev.denismasterherobrine.animatio.anim.runtime;

import dev.denismasterherobrine.animatio.anim.math.Mat4;
import dev.denismasterherobrine.animatio.anim.math.PoseMath;
import dev.denismasterherobrine.animatio.anim.model.AnimationSet;
import dev.denismasterherobrine.animatio.anim.model.ClipInfo;
import dev.denismasterherobrine.animatio.anim.model.Pose;
import dev.denismasterherobrine.animatio.anim.model.Skeleton;
import dev.denismasterherobrine.animatio.anim.model.Socket;
import dev.denismasterherobrine.animatio.api.AnimationController;
import dev.denismasterherobrine.animatio.api.event.AnimationEvent;
import dev.denismasterherobrine.animatio.api.AnimationListener;
import dev.denismasterherobrine.animatio.api.playable.AnimationRef;
import dev.denismasterherobrine.animatio.api.playable.BlendSpace;
import dev.denismasterherobrine.animatio.api.playback.PlayOptions;
import dev.denismasterherobrine.animatio.api.playable.Playable;
import dev.denismasterherobrine.animatio.api.playback.TransitionConfig;
import dev.denismasterherobrine.animatio.api.playback.TransitionTable;
import it.unimi.dsi.fastutil.objects.Object2FloatMap;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/**
 * The animation runtime behind {@link AnimationController}.
 * <p>
 * Threading: each instance belongs to the thread of its side (server thread or render thread). The one exception is
 * {@link #evaluate()}, which the client runs on pose-evaluation workers after {@link #prepareForEvaluation()} while
 * the render thread is not touching the controller; a {@linkplain #setConcurrencyGuard guard} makes render-thread
 * calls wait for in-flight evaluation first.
 * <p>
 * Layering: tracks are sorted by (priority, slot, play order). Tracks sharing a slot form a group whose poses
 * are averaged per bone by weight (so cross-fades never drift toward the rest pose); each group is then
 * layered over the result below it by its combined weight. A new track gets a new slot on top, unless it
 * replaces a track (same key, or a track stopped in the same tick), in which case it joins that track's slot.
 */
public final class ControllerImpl implements AnimationController {
    public enum Side {
        /** Logical server: no events; every change is emitted to the command sink. */
        SERVER,
        /** Logical client: fires events; changes made through the API are local only. */
        CLIENT
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("Animatio");
    /** Larger time jumps (catching up after the owner was not updated) advance silently. */
    private static final float MAX_EVENT_DT = 1f;
    private static final float WEIGHT_EPSILON = 1e-5f;
    private static final Comparator<Track> ORDER = Comparator.<Track>comparingInt(t -> t.priority)
            .thenComparingLong(t -> t.slot).thenComparingLong(t -> t.seq);

    private final Side side;
    private @Nullable AnimationSet set;
    final ArrayList<Track> tracks = new ArrayList<>(4);
    private Track[] iteration = new Track[8];
    private final Object2FloatOpenHashMap<String> params = new Object2FloatOpenHashMap<>();
    private final java.util.Set<String> remoteParams = new java.util.HashSet<>();
    private final TransitionTable transitions = new TransitionTable();
    private final List<AnimationListener> listeners = new ArrayList<>(0);
    private @Nullable Consumer<ControllerCommand> sink;
    private @Nullable DoubleSupplier timeSource;
    private @Nullable BaseEventContext eventContext;
    private double clock = Double.NaN;
    private long epoch;
    private int nextId = 1;
    private long nextSlot = 1;
    private long nextSeq = 1;
    private float globalSpeed = 1f;
    private boolean warnedEventFailure;

    // evaluation buffers, sized to the bound skeleton
    private int bufferNodes = -1;
    private Pose pose, sampleA, sampleB, blendScratch;
    private float[] weightsA, weightsB, accT, accR, accS, sumT, sumR, sumS, globals;
    private boolean evaluated;
    private final Matrix4f scratchMatrix = new Matrix4f();

    private static volatile @Nullable Runnable concurrencyGuard;

    public ControllerImpl(Side side) {
        this.side = side;
    }

    /**
     * Installed by the client: called before any state change or pose read, so a render-thread caller waits until
     * background pose evaluation has finished. Must return immediately on other threads.
     */
    public static void setConcurrencyGuard(@Nullable Runnable guard) {
        concurrencyGuard = guard;
    }

    private static void guard() {
        Runnable g = concurrencyGuard;
        if (g != null) g.run();
    }

    // ================================================================== wiring

    public Side side() {
        return side;
    }

    /** Bind (or rebind after a reload) to a model. Tracks re-resolve their clips lazily. */
    public void bind(@Nullable AnimationSet set) {
        if (this.set == set) return;
        guard();
        this.set = set;
        this.evaluated = false;
    }

    public @Nullable AnimationSet animationSet() {
        return set;
    }

    int setVersion() {
        return set == null ? 0 : set.version();
    }

    /** Server: receives every state change, to be sent to tracking clients. */
    public void setSink(@Nullable Consumer<ControllerCommand> sink) {
        this.sink = sink;
    }

    /** Clock used to bring the controller up to date before API calls (seconds). */
    public void setTimeSource(@Nullable DoubleSupplier timeSource) {
        this.timeSource = timeSource;
    }

    public void setEventContext(@Nullable BaseEventContext context) {
        this.eventContext = context;
    }

    public double clock() {
        return clock;
    }

    // ================================================================== time

    /** Advance to absolute time {@code now} (seconds). Calling it again with the same time does nothing. */
    public void update(double now) {
        guard();
        if (Double.isNaN(clock)) {
            clock = now;
            epoch++;
            return;
        }
        double dt = now - clock;
        if (dt <= 0) {
            if (dt < -1.0) clock = now; // the clock jumped backwards (e.g. /time set): resynchronise
            return;
        }
        clock = now;
        epoch++;
        advance((float) dt);
    }

    private void syncClock() {
        if (timeSource != null) update(timeSource.getAsDouble());
    }

    private void advance(float dt) {
        int n = tracks.size();
        if (n == 0) return;
        boolean fire = side == Side.CLIENT && dt <= MAX_EVENT_DT;
        if (iteration.length < n) iteration = new Track[n * 2];
        Track[] snapshot = tracks.toArray(iteration);
        for (int i = 0; i < n; i++) {
            Track t = snapshot[i];
            t.advance(dt, fire);
            handleFinished(t);
        }
        Arrays.fill(snapshot, 0, n, null);
        removeDead();
    }

    private void handleFinished(Track t) {
        if (t.finished && !t.finishHandled && !t.dead) {
            t.finishHandled = true;
            for (int i = 0; i < listeners.size(); i++) listeners.get(i).onFinish(this, t.id, t.key);
            t.beginStop(resolveFadeOut(t), epoch);
        }
    }

    private void removeDead() {
        for (int i = tracks.size() - 1; i >= 0; i--) {
            Track t = tracks.get(i);
            if (t.dead) {
                tracks.remove(i);
                for (int l = 0; l < listeners.size(); l++) listeners.get(l).onRemoved(this, t.id, t.key);
            }
        }
    }

    // ================================================================== commands

    private void emit(ControllerCommand command) {
        if (side == Side.SERVER && sink != null) sink.accept(command);
    }

    /**
     * Apply a command received from the server.
     *
     * @param lag seconds since the server issued it (catch-up for plays and fades)
     */
    public void applyRemote(ControllerCommand command, float lag) {
        guard();
        if (command instanceof ControllerCommand.SetParameter p) remoteParams.add(p.name());
        else if (command instanceof ControllerCommand.Reset) remoteParams.clear();
        apply(command, Math.max(0f, lag));
    }

    /** True if the server has set this parameter; client-side providers then leave it alone. */
    public boolean isRemoteParameter(String name) {
        return remoteParams.contains(name);
    }

    private void apply(ControllerCommand command, float lag) {
        switch (command) {
            case ControllerCommand.Play p -> applyPlay(p.id(), p.playable(), p.options(), lag);
            case ControllerCommand.Stop s -> applyStop(s.target(), s.fade(), lag);
            case ControllerCommand.Pause p -> {
                for (Track t : tracks) if (t.isActive() && t.matches(p.target())) t.paused = true;
            }
            case ControllerCommand.Resume r -> {
                for (Track t : tracks) if (t.isActive() && t.matches(r.target())) t.paused = false;
            }
            case ControllerCommand.SetSpeed s -> {
                for (Track t : tracks) if (t.isActive() && t.matches(s.target())) t.speed = s.speed();
            }
            case ControllerCommand.Reset r -> applyReset();
            case ControllerCommand.SetParameter p -> params.put(p.name(), p.value());
            case ControllerCommand.SetGlobalSpeed g -> globalSpeed = g.speed();
        }
    }

    private void applyPlay(int id, Playable playable, PlayOptions options, float lag) {
        String key = playable.key();
        Track join = findJoin(key, options.priority(), options.additive());
        TransitionConfig fadeIn = options.fadeIn() != null ? options.fadeIn() : resolveFadeIn(join, key);
        for (Track t : tracks) {
            if (t.isActive() && t.key.equals(key)) t.beginStop(fadeIn, epoch);
        }
        long slot = join != null ? join.slot : nextSlot++;
        if (join != null) {
            join.claimed = true;
            for (Track t : tracks) {
                if (t.slot == slot && t.priority == options.priority()) t.retimeFadeOut(fadeIn);
            }
        }
        Track track = new Track(this, id, playable, options, fadeIn, slot, nextSeq++);
        boolean seeking = options.startAt() > 0f || lag > 0f;
        track.start(side == Side.CLIENT && !seeking);
        float seek = options.startAt() + lag * track.playbackRate();
        if (seek > 0f) track.seek(seek);
        if (lag > 0f && fadeIn.duration() > 0f) track.fadeInT = Math.min(fadeIn.duration(), lag);

        int pos = 0;
        while (pos < tracks.size() && ORDER.compare(tracks.get(pos), track) <= 0) pos++;
        tracks.add(pos, track);
        for (int i = 0; i < listeners.size(); i++) listeners.get(i).onStart(this, id, key);
        handleFinished(track);
        removeDead();
    }

    /** The track a new play of {@code key} replaces: an active one with the same key, else one stopped this tick. */
    private @Nullable Track findJoin(String key, int priority, boolean additive) {
        if (additive) return null;
        for (Track t : tracks) {
            if (t.isActive() && t.priority == priority && !t.options.additive() && t.key.equals(key)) return t;
        }
        Track best = null;
        for (Track t : tracks) {
            if (t.stopping && !t.dead && !t.claimed && t.stopEpoch == epoch && t.priority == priority && !t.options.additive()
                    && (best == null || t.slot > best.slot)) {
                best = t;
            }
        }
        return best;
    }

    private TransitionConfig resolveFadeIn(@Nullable Track from, String to) {
        TransitionConfig c = null;
        if (from != null) {
            c = lookupPair(from.shownKey(), to);
            if (c == null && !from.key.equals(from.shownKey())) c = lookupPair(from.key, to);
        } else {
            c = lookupPair(null, to);
        }
        return c != null ? c : defaultTransition();
    }

    private TransitionConfig resolveFadeOut(Track t) {
        if (t.options.fadeOut() != null) return t.options.fadeOut();
        TransitionConfig c = lookupPair(t.shownKey(), TransitionTable.ANY);
        if (c == null && !t.key.equals(t.shownKey())) c = lookupPair(t.key, TransitionTable.ANY);
        return c != null ? c : defaultTransition();
    }

    private @Nullable TransitionConfig lookupPair(@Nullable String from, String to) {
        TransitionConfig c = transitions.findPair(from, to);
        if (c == null && set != null) c = set.transitions().findPair(from, to);
        return c;
    }

    private TransitionConfig defaultTransition() {
        if (transitions.defaultConfig() != null) return transitions.defaultConfig();
        if (set != null && set.transitions().defaultConfig() != null) return set.transitions().defaultConfig();
        return TransitionConfig.DEFAULT;
    }

    private void applyStop(ControllerCommand.Target target, @Nullable TransitionConfig fade, float lag) {
        for (Track t : tracks) {
            if (t.isActive() && t.matches(target)) {
                t.beginStop(fade != null ? fade : resolveFadeOut(t), epoch);
                if (lag > 0f && !t.dead) t.fadeOutT = Math.min(t.fadeOutDur, lag);
            }
        }
        removeDead();
    }

    private void applyReset() {
        for (Track t : tracks) t.dead = true;
        removeDead();
        globalSpeed = 1f;
    }

    /** Commands that rebuild this controller's state on a client that just started tracking the owner. */
    public List<ControllerCommand> snapshot() {
        List<ControllerCommand> out = new ArrayList<>();
        out.add(new ControllerCommand.Reset());
        if (globalSpeed != 1f) out.add(new ControllerCommand.SetGlobalSpeed(globalSpeed));
        for (Object2FloatMap.Entry<String> e : params.object2FloatEntrySet()) {
            out.add(new ControllerCommand.SetParameter(e.getKey(), e.getFloatValue()));
        }
        for (Track t : tracks) {
            if (!t.isActive() || t.id <= 0) continue;
            PlayOptions opts = t.options.fadeIn(TransitionConfig.NONE).startAt(t.elapsed);
            out.add(new ControllerCommand.Play(t.id, t.source, opts));
            ControllerCommand.Target target = ControllerCommand.Target.id(t.id);
            if (t.speed != 1f) out.add(new ControllerCommand.SetSpeed(target, t.speed));
            if (t.paused) out.add(new ControllerCommand.Pause(target));
        }
        return out;
    }

    // ================================================================== AnimationController

    @Override
    public int play(Playable playable, PlayOptions options) {
        Objects.requireNonNull(playable, "playable");
        Objects.requireNonNull(options, "options");
        guard();
        syncClock();
        int id = side == Side.CLIENT ? -(nextId++) : nextId++;
        if (options.fadeIn() == null) {
            Track join = findJoin(playable.key(), options.priority(), options.additive());
            options = options.fadeIn(resolveFadeIn(join, playable.key()));
        }
        applyPlay(id, playable, options, 0f);
        emit(new ControllerCommand.Play(id, playable, options));
        return id;
    }

    @Override
    public boolean isPlaying(Playable playable) {
        String key = playable.key();
        for (Track t : tracks) if (t.isActive() && t.matchesKey(key)) return true;
        return false;
    }

    @Override
    public boolean isPlaying(int trackId) {
        for (Track t : tracks) if (t.isActive() && t.id == trackId) return true;
        return false;
    }

    @Override
    public boolean isPaused(Playable playable) {
        String key = playable.key();
        for (Track t : tracks) if (t.isActive() && t.paused && t.matchesKey(key)) return true;
        return false;
    }

    @Override
    public boolean isAnyPlaying() {
        for (Track t : tracks) if (t.isActive()) return true;
        return false;
    }

    @Override
    public float clipTime(Playable playable) {
        String key = playable.key();
        for (int i = tracks.size() - 1; i >= 0; i--) {
            Track t = tracks.get(i);
            if (t.isActive() && t.matchesKey(key)) return t.displayTime();
        }
        return -1f;
    }

    @Override
    public void stop(Playable playable) {
        command(new ControllerCommand.Stop(ControllerCommand.Target.key(playable.key()), null));
    }

    @Override
    public void stop(Playable playable, TransitionConfig fadeOut) {
        command(new ControllerCommand.Stop(ControllerCommand.Target.key(playable.key()), Objects.requireNonNull(fadeOut)));
    }

    @Override
    public void stop(int trackId) {
        command(new ControllerCommand.Stop(ControllerCommand.Target.id(trackId), null));
    }

    @Override
    public void stop(int trackId, TransitionConfig fadeOut) {
        command(new ControllerCommand.Stop(ControllerCommand.Target.id(trackId), Objects.requireNonNull(fadeOut)));
    }

    @Override
    public void stopAll() {
        command(new ControllerCommand.Stop(ControllerCommand.Target.ALL_TRACKS, null));
    }

    @Override
    public void stopAll(TransitionConfig fadeOut) {
        command(new ControllerCommand.Stop(ControllerCommand.Target.ALL_TRACKS, Objects.requireNonNull(fadeOut)));
    }

    @Override
    public void reset() {
        command(new ControllerCommand.Reset());
    }

    @Override
    public void pause(Playable playable) {
        command(new ControllerCommand.Pause(ControllerCommand.Target.key(playable.key())));
    }

    @Override
    public void pause(int trackId) {
        command(new ControllerCommand.Pause(ControllerCommand.Target.id(trackId)));
    }

    @Override
    public void pauseAll() {
        command(new ControllerCommand.Pause(ControllerCommand.Target.ALL_TRACKS));
    }

    @Override
    public void resume(Playable playable) {
        command(new ControllerCommand.Resume(ControllerCommand.Target.key(playable.key())));
    }

    @Override
    public void resume(int trackId) {
        command(new ControllerCommand.Resume(ControllerCommand.Target.id(trackId)));
    }

    @Override
    public void resumeAll() {
        command(new ControllerCommand.Resume(ControllerCommand.Target.ALL_TRACKS));
    }

    @Override
    public void setSpeed(Playable playable, float speed) {
        checkSpeed(speed);
        command(new ControllerCommand.SetSpeed(ControllerCommand.Target.key(playable.key()), speed));
    }

    @Override
    public void setSpeed(int trackId, float speed) {
        checkSpeed(speed);
        command(new ControllerCommand.SetSpeed(ControllerCommand.Target.id(trackId), speed));
    }

    @Override
    public void setGlobalSpeed(float speed) {
        checkSpeed(speed);
        if (speed == globalSpeed) return;
        command(new ControllerCommand.SetGlobalSpeed(speed));
    }

    private static void checkSpeed(float speed) {
        if (!(speed >= 0f) || Float.isInfinite(speed)) throw new IllegalArgumentException("Invalid speed " + speed);
    }

    @Override
    public float globalSpeed() {
        return globalSpeed;
    }

    @Override
    public void setParameter(String name, float value) {
        Objects.requireNonNull(name, "name");
        if (params.containsKey(name) && params.getFloat(name) == value) return;
        command(new ControllerCommand.SetParameter(name, value));
    }

    /** Set a parameter locally without syncing (client-side parameter providers). */
    public void setLocalParameter(String name, float value) {
        params.put(name, value);
    }

    /** Like {@link #setLocalParameter}, unless the server controls this parameter. */
    public void provideParameter(String name, float value) {
        if (!remoteParams.contains(name)) params.put(name, value);
    }

    @Override
    public float getParameter(String name) {
        return params.getFloat(name);
    }

    private void command(ControllerCommand command) {
        guard();
        syncClock();
        apply(command, 0f);
        emit(command);
    }

    @Override
    public TransitionTable transitions() {
        return transitions;
    }

    @Override
    public void addListener(AnimationListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    @Override
    public void removeListener(AnimationListener listener) {
        listeners.remove(listener);
    }

    @Override
    public @Nullable String modelName() {
        return set == null ? null : set.name();
    }

    // ================================================================== resolution & events

    /** Resolve a play target to a {@link ClipInfo}, a {@link BlendSpace}, or {@code null}. */
    @Nullable Object resolve(Playable target) {
        if (target instanceof BlendSpace bs) return bs;
        if (target instanceof AnimationRef ref) {
            if (set == null) return null;
            ClipInfo ci = set.clip(ref.name());
            if (ci != null) return ci;
            return set.blendSpace(ref.name());
        }
        return null;
    }

    boolean hasListeners() {
        return !listeners.isEmpty();
    }

    void notifyLoop(Track t, String clip) {
        for (int i = 0; i < listeners.size(); i++) listeners.get(i).onLoop(this, t.id, clip);
    }

    void fireEvent(Track t, String clip, AnimationEvent event) {
        for (int i = 0; i < listeners.size(); i++) listeners.get(i).onEvent(this, t.id, clip, event);
        BaseEventContext ctx = eventContext;
        if (ctx == null) return;
        try {
            ctx.prepare(this, clip, t.key);
            event.fire(ctx);
        } catch (Throwable e) {
            if (!warnedEventFailure) {
                warnedEventFailure = true;
                LOGGER.error("Animation event {} on clip '{}' failed (further failures on this controller are not logged)", event, clip, e);
            }
        }
    }

    // ================================================================== evaluation

    private void ensureBuffers(int n) {
        if (bufferNodes == n) return;
        bufferNodes = n;
        pose = new Pose(n);
        sampleA = new Pose(n);
        sampleB = new Pose(n);
        blendScratch = new Pose(n);
        weightsA = new float[n * 3];
        weightsB = new float[n * 3];
        accT = new float[n * 3];
        accR = new float[n * 4];
        accS = new float[n * 3];
        sumT = new float[n];
        sumR = new float[n];
        sumS = new float[n];
        globals = new float[n * 16];
    }

    /**
     * Do every state change that pose sampling could otherwise trigger (resolving clips after a model bind or reload,
     * replaying time that passed before a model was bound), so that {@link #evaluate()} only reads track state and
     * writes this controller's own buffers. Call on the owner thread before evaluating on another thread.
     */
    public void prepareForEvaluation() {
        guard();
        int n = tracks.size();
        if (n == 0) return;
        if (iteration.length < n) iteration = new Track[n * 2];
        Track[] snapshot = tracks.toArray(iteration);
        for (int i = 0; i < n; i++) {
            snapshot[i].ensureResolved();
            handleFinished(snapshot[i]);
        }
        Arrays.fill(snapshot, 0, n, null);
        removeDead();
        ensureBuffersForSet();
    }

    private void ensureBuffersForSet() {
        AnimationSet s = set;
        if (s != null) ensureBuffers(s.skeleton().size());
    }

    /**
     * Compute the current pose and model-space node matrices.
     * <p>
     * May run on a worker thread after {@link #prepareForEvaluation()}, while no other thread uses this controller.
     *
     * @return false if no model is bound
     */
    public boolean evaluate() {
        AnimationSet s = set;
        if (s == null) return false;
        Skeleton sk = s.skeleton();
        int n = sk.size();
        ensureBuffers(n);
        pose.setRest(sk);
        int count = tracks.size();
        int i = 0;
        while (i < count) {
            Track first = tracks.get(i);
            if (first.options.additive()) {
                applyAdditive(sk, first);
                i++;
                continue;
            }
            int j = i + 1;
            while (j < count) {
                Track t = tracks.get(j);
                if (t.options.additive() || t.priority != first.priority || t.slot != first.slot) break;
                j++;
            }
            blendGroup(sk, i, j);
            i = j;
        }
        for (int node = 0; node < n; node++) PoseMath.normalizeQuat(pose.r, node * 4);
        Mat4.computeGlobals(sk, pose, globals);
        evaluated = true;
        return true;
    }

    private void blendGroup(Skeleton sk, int from, int to) {
        int n = sk.size();
        boolean any = false;
        for (int k = from; k < to; k++) {
            Track t = tracks.get(k);
            float tw = t.weight();
            if (tw <= WEIGHT_EPSILON) continue;
            if (!t.sample(sk, sampleA, weightsA, sampleB, weightsB, blendScratch)) continue;
            if (!any) {
                Arrays.fill(accT, 0f);
                Arrays.fill(accR, 0f);
                Arrays.fill(accS, 0f);
                Arrays.fill(sumT, 0f);
                Arrays.fill(sumR, 0f);
                Arrays.fill(sumS, 0f);
                any = true;
            }
            float[] mask = t.options.mask() != null ? t.options.mask().resolve(sk) : null;
            for (int node = 0; node < n; node++) {
                float base = mask == null ? tw : tw * mask[node];
                if (base <= 0f) continue;
                float wt = base * weightsA[node * 3];
                if (wt > 0f) {
                    int o = node * 3;
                    accT[o] += sampleA.t[o] * wt;
                    accT[o + 1] += sampleA.t[o + 1] * wt;
                    accT[o + 2] += sampleA.t[o + 2] * wt;
                    sumT[node] += wt;
                }
                float wr = base * weightsA[node * 3 + 1];
                if (wr > 0f) {
                    int o = node * 4;
                    float sign = PoseMath.dot4(pose.r, o, sampleA.r, o) < 0f ? -wr : wr;
                    accR[o] += sampleA.r[o] * sign;
                    accR[o + 1] += sampleA.r[o + 1] * sign;
                    accR[o + 2] += sampleA.r[o + 2] * sign;
                    accR[o + 3] += sampleA.r[o + 3] * sign;
                    sumR[node] += wr;
                }
                float ws = base * weightsA[node * 3 + 2];
                if (ws > 0f) {
                    int o = node * 3;
                    accS[o] += sampleA.s[o] * ws;
                    accS[o + 1] += sampleA.s[o + 1] * ws;
                    accS[o + 2] += sampleA.s[o + 2] * ws;
                    sumS[node] += ws;
                }
            }
        }
        if (!any) return;
        for (int node = 0; node < n; node++) {
            if (sumT[node] > 0f) {
                int o = node * 3;
                float inv = 1f / sumT[node];
                accT[o] *= inv;
                accT[o + 1] *= inv;
                accT[o + 2] *= inv;
                PoseMath.lerp3(pose.t, o, accT, o, Math.min(1f, sumT[node]), pose.t, o);
            }
            if (sumR[node] > 0f) {
                int o = node * 4;
                PoseMath.normalizeQuat(accR, o);
                PoseMath.nlerp(pose.r, o, accR, o, Math.min(1f, sumR[node]), pose.r, o);
            }
            if (sumS[node] > 0f) {
                int o = node * 3;
                float inv = 1f / sumS[node];
                accS[o] *= inv;
                accS[o + 1] *= inv;
                accS[o + 2] *= inv;
                PoseMath.lerp3(pose.s, o, accS, o, Math.min(1f, sumS[node]), pose.s, o);
            }
        }
    }

    /** Additive layer: adds the clip's offset from the rest pose, scaled by weight. */
    private void applyAdditive(Skeleton sk, Track t) {
        float tw = t.weight();
        if (tw <= WEIGHT_EPSILON) return;
        if (!t.sample(sk, sampleA, weightsA, sampleB, weightsB, blendScratch)) return;
        float[] mask = t.options.mask() != null ? t.options.mask().resolve(sk) : null;
        float[] rt = sk.restT(), rr = sk.restR(), rs = sk.restS();
        float[] q = accR; // reuse as scratch: [0..3] delta, [4..7] identity
        int n = sk.size();
        for (int node = 0; node < n; node++) {
            float base = mask == null ? tw : tw * mask[node];
            if (base <= 0f) continue;
            float wt = base * weightsA[node * 3];
            if (wt > 0f) {
                int o = node * 3;
                pose.t[o] += (sampleA.t[o] - rt[o]) * wt;
                pose.t[o + 1] += (sampleA.t[o + 1] - rt[o + 1]) * wt;
                pose.t[o + 2] += (sampleA.t[o + 2] - rt[o + 2]) * wt;
            }
            float wr = base * weightsA[node * 3 + 1];
            if (wr > 0f) {
                int o = node * 4;
                // delta = sample * conjugate(rest)
                q[4] = -rr[o];
                q[5] = -rr[o + 1];
                q[6] = -rr[o + 2];
                q[7] = rr[o + 3];
                PoseMath.mulQuat(sampleA.r, o, q, 4, q, 0);
                q[4] = 0f;
                q[5] = 0f;
                q[6] = 0f;
                q[7] = 1f;
                PoseMath.nlerp(q, 4, q, 0, Math.min(1f, wr), q, 0);
                PoseMath.mulQuat(q, 0, pose.r, o, pose.r, o);
            }
            float ws = base * weightsA[node * 3 + 2];
            if (ws > 0f) {
                int o = node * 3;
                for (int c = 0; c < 3; c++) {
                    float r = rs[o + c];
                    float ratio = r != 0f ? sampleA.s[o + c] / r : 1f;
                    pose.s[o + c] *= 1f + (ratio - 1f) * ws;
                }
            }
        }
    }

    /** Local pose from the last {@link #evaluate()}. */
    public @Nullable Pose pose() {
        return evaluated ? pose : null;
    }

    /** Model-space node matrices (16 floats per node) from the last {@link #evaluate()}. */
    public @Nullable float[] globals() {
        return evaluated ? globals : null;
    }

    @Override
    public boolean boneTransform(String boneOrSocket, Matrix4f out) {
        guard();
        AnimationSet s = set;
        if (!evaluated || s == null) return false;
        int bone = s.skeleton().find(boneOrSocket);
        if (bone >= 0) {
            out.set(globals, bone * 16);
            return true;
        }
        Socket socket = s.socket(boneOrSocket);
        if (socket == null) return false;
        out.set(globals, socket.bone() * 16).mul(scratchMatrix.set(socket.offset(), 0));
        return true;
    }

    /** Number of live tracks, including ones fading out. */
    public int trackCount() {
        return tracks.size();
    }
}
