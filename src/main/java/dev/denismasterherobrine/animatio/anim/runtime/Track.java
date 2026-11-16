package dev.denismasterherobrine.animatio.anim.runtime;

import dev.denismasterherobrine.animatio.anim.math.PoseMath;
import dev.denismasterherobrine.animatio.anim.model.Channel;
import dev.denismasterherobrine.animatio.anim.model.ClipInfo;
import dev.denismasterherobrine.animatio.anim.model.Pose;
import dev.denismasterherobrine.animatio.anim.model.Skeleton;
import dev.denismasterherobrine.animatio.api.playable.AnimationRef;
import dev.denismasterherobrine.animatio.api.playable.BlendSpace;
import dev.denismasterherobrine.animatio.api.playback.Easing;
import dev.denismasterherobrine.animatio.api.event.EventMarker;
import dev.denismasterherobrine.animatio.api.playback.PlayOptions;
import dev.denismasterherobrine.animatio.api.playable.Playable;
import dev.denismasterherobrine.animatio.api.playable.Sequence;
import dev.denismasterherobrine.animatio.api.playback.TransitionConfig;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * Runtime state of one played {@link Playable}: the position in its sequence, fades, and step-to-step blending.
 */
final class Track {
    private static final float INF = Float.POSITIVE_INFINITY;
    private static final float WEIGHT_EPSILON = 1e-4f;

    final ControllerImpl ctl;
    final int id;
    final Playable source;
    final String key;
    final PlayOptions options;
    final Sequence.Step[] steps;
    final int priority;
    final long slot;
    final long seq;

    // --- playback ---
    int stepIndex;
    float stepTime;
    /** Playback time consumed since the sequence (re)started; guards {@code thenRepeat()} against zero-length loops. */
    float passTime;
    /** Total playback time, including seeks; used for late-join snapshots. */
    float elapsed;
    /** Time that could not be played because no model was bound yet; replayed silently once one is. */
    float pendingSeek;
    /** Unwrapped position: clip seconds for clips, cycles for blend spaces. */
    double clipPos;
    boolean finished;
    boolean finishHandled;
    boolean holding;
    boolean paused;
    float speed = 1f;

    // --- what is shown ---
    @Nullable Playable shownTarget;
    @Nullable Object shownRes; // ClipInfo, BlendSpace or null
    @Nullable AnimationRef shownRef;
    int shownLoops = -1;
    float shownSpeed = 1f;
    boolean shownFixed;
    @Nullable ClipInfo[] bsClips;
    @Nullable float[] bsWeights;
    float smoothX, smoothY;
    boolean smoothInit;
    int resolvedVersion = -1;

    // --- blend from the previous sequence step ---
    @Nullable Object prevRes;
    float prevTime;
    @Nullable ClipInfo[] prevBsClips;
    @Nullable float[] prevBsWeights;
    float stepBlendT;
    float stepBlendDur;
    Easing stepBlendEase = Easing.LINEAR;

    // --- fades ---
    float fadeInT;
    final float fadeInDur;
    final Easing fadeInEase;
    boolean stopping;
    /** Set once a new track has taken over this track's slot. */
    boolean claimed;
    long stopEpoch = Long.MIN_VALUE;
    float fadeOutT;
    float fadeOutDur;
    float fadeOutFrom;
    Easing fadeOutEase = Easing.LINEAR;
    boolean dead;

    Track(ControllerImpl ctl, int id, Playable source, PlayOptions options, TransitionConfig fadeIn, long slot, long seq) {
        this.ctl = ctl;
        this.id = id;
        this.source = source;
        this.key = source.key();
        this.options = options;
        this.steps = Sequence.of(source).steps().toArray(new Sequence.Step[0]);
        this.priority = options.priority();
        this.slot = slot;
        this.seq = seq;
        this.fadeInDur = fadeIn.duration();
        this.fadeInEase = fadeIn.easing();
    }

    // ------------------------------------------------------------------ state queries

    boolean isActive() {
        return !stopping && !dead;
    }

    String shownKey() {
        return shownTarget != null ? shownTarget.key() : key;
    }

    boolean matchesKey(String k) {
        return key.equals(k) || (shownTarget != null && shownTarget.key().equals(k));
    }

    boolean matches(ControllerCommand.Target target) {
        return switch (target.kind()) {
            case ControllerCommand.Target.ALL -> true;
            case ControllerCommand.Target.ID -> id == target.id();
            default -> matchesKey(target.key());
        };
    }

    float fadeInFactor() {
        return fadeInDur <= 0f ? 1f : fadeInEase.apply(fadeInT / fadeInDur);
    }

    float fadeFactor() {
        if (dead) return 0f;
        if (!stopping) return fadeInFactor();
        if (fadeOutDur <= 0f) return 0f;
        return fadeOutFrom * (1f - fadeOutEase.apply(fadeOutT / fadeOutDur));
    }

    float weight() {
        return options.weight() * fadeFactor();
    }

    /** Playback seconds per real second. */
    float playbackRate() {
        return paused ? 0f : speed * options.speed() * ctl.globalSpeed();
    }

    // ------------------------------------------------------------------ fades

    void beginStop(TransitionConfig fade, long epoch) {
        if (stopping || dead) return;
        fadeOutFrom = fadeFactor();
        stopping = true;
        stopEpoch = epoch;
        fadeOutT = 0f;
        fadeOutDur = fade.duration();
        fadeOutEase = fade.easing();
        if (fadeOutDur <= 0f || fadeOutFrom <= 0f) dead = true;
    }

    /** Restart an ongoing fade-out from the current weight so it matches an incoming fade-in. */
    void retimeFadeOut(TransitionConfig fade) {
        if (!stopping || dead) return;
        fadeOutFrom = fadeFactor();
        fadeOutT = 0f;
        fadeOutDur = fade.duration();
        fadeOutEase = fade.easing();
        if (fadeOutDur <= 0f || fadeOutFrom <= 0f) dead = true;
    }

    // ------------------------------------------------------------------ time

    void start(boolean fire) {
        stepIndex = 0;
        enterStep(fire);
    }

    /** Advance by {@code dt} real seconds. */
    void advance(float dt, boolean fire) {
        if (dead) return;
        if (stopping) {
            fadeOutT += dt;
            if (fadeOutT >= fadeOutDur) {
                dead = true;
                return;
            }
        } else if (fadeInT < fadeInDur) {
            fadeInT = Math.min(fadeInDur, fadeInT + dt);
        }
        if (!ensureResolved()) {
            float pdt = dt * playbackRate();
            pendingSeek += pdt;
            elapsed += pdt;
            return;
        }
        if (shownRes instanceof BlendSpace bs) updateBlendWeights(bs, dt);
        float pdt = dt * playbackRate();
        if (pdt <= 0f) return;
        elapsed += pdt;
        play(pdt, fire);
    }

    /** Skip ahead by {@code t} playback seconds without firing events. */
    void seek(float t) {
        if (t <= 0f) return;
        elapsed += t;
        if (!ensureResolved()) {
            pendingSeek += t;
            return;
        }
        play(t, false);
    }

    private void play(float t, boolean fire) {
        int guard = 0;
        while (t > 0f && !finished && !holding) {
            if (++guard > 10_000) break;
            Sequence.Step step = steps[stepIndex];
            if (step instanceof Sequence.Play p) {
                float limit = Math.max(0f, playLimit(p));
                float use = Math.min(t, limit);
                boolean ends = use >= limit;
                advanceShown(use, ends && !shownFixed, fire);
                consume(use);
                t -= use;
                if (ends) nextStep(fire);
            } else if (step instanceof Sequence.Wait w) {
                float limit = Math.max(0f, w.seconds() - stepTime);
                float use = Math.min(t, limit);
                consume(use);
                t -= use;
                if (use >= limit) nextStep(fire);
            } else {
                break; // Hold, Fire and Repeat are handled when the step is entered
            }
        }
    }

    private void consume(float use) {
        stepTime += use;
        passTime += use;
        if (stepBlendT < stepBlendDur) stepBlendT += use;
    }

    /** Playback seconds until the current play step ends. */
    private float playLimit(Sequence.Play p) {
        if (p.hasFixedDuration()) return p.duration() - stepTime;
        if (shownRes instanceof ClipInfo ci && shownLoops > 0) {
            double total = (double) shownLoops * ci.duration();
            return (float) ((total - clipPos) / shownSpeed);
        }
        return INF; // looping clips, blend spaces and unknown clips run until stopped
    }

    private void advanceShown(float use, boolean snapToEnd, boolean fire) {
        if (shownRes instanceof ClipInfo ci) {
            double from = clipPos;
            clipPos = snapToEnd && shownLoops > 0 ? (double) shownLoops * ci.duration() : clipPos + (double) use * shownSpeed;
            fireClipMarkers(ci, from, clipPos, fire);
        } else if (shownRes instanceof BlendSpace) {
            float cycle = cycleLength();
            if (cycle <= 0f) return;
            double from = clipPos;
            clipPos += (double) use * shownSpeed / cycle;
            fireBlendMarkers(from, clipPos, fire);
        } else {
            clipPos += (double) use * shownSpeed;
        }
    }

    private void nextStep(boolean fire) {
        stepIndex++;
        enterStep(fire);
    }

    private void enterStep(boolean fire) {
        while (true) {
            if (stepIndex >= steps.length) {
                finished = true;
                return;
            }
            Sequence.Step step = steps[stepIndex];
            stepTime = 0f;
            if (step instanceof Sequence.Play p) {
                TransitionConfig blend = p.blendIn() != null ? p.blendIn() : Sequence.DEFAULT_STEP_BLEND;
                if (shownTarget != null) {
                    capturePrev();
                    stepBlendT = 0f;
                    stepBlendDur = blend.duration();
                    stepBlendEase = blend.easing();
                } else if (stepIndex > 0) {
                    // nothing shown yet (sequence started with a wait): fade in from the layers below
                    prevRes = null;
                    stepBlendT = 0f;
                    stepBlendDur = blend.duration();
                    stepBlendEase = blend.easing();
                }
                shownFixed = p.hasFixedDuration();
                clipPos = 0;
                smoothInit = false;
                shownTarget = p.target();
                resolveShown();
                if (fire) fireStartMarkers();
                return;
            }
            if (step instanceof Sequence.Wait) return;
            if (step instanceof Sequence.Hold) {
                holding = true;
                return;
            }
            if (step instanceof Sequence.Fire f) {
                if (fire) ctl.fireEvent(this, "", f.event());
                stepIndex++;
                continue;
            }
            if (step instanceof Sequence.Repeat) {
                if (passTime <= 0f) { // a pass that takes no time would loop forever
                    holding = true;
                    return;
                }
                passTime = 0f;
                stepIndex = 0;
            }
        }
    }

    private void capturePrev() {
        prevRes = shownRes;
        prevTime = displayTime();
        prevBsClips = bsClips;
        prevBsWeights = bsWeights != null ? bsWeights.clone() : null;
    }

    // ------------------------------------------------------------------ resolution

    /** @return false if no model is bound (the track then accumulates pending time) */
    boolean ensureResolved() {
        int version = ctl.setVersion();
        if (version == 0) return false;
        if (resolvedVersion != version) {
            boolean firstBind = resolvedVersion <= 0;
            if (shownTarget != null) resolveShown();
            else resolvedVersion = version;
            if (!firstBind) {
                // the model was reloaded: the previous-step snapshot may refer to old clips
                prevRes = null;
                prevBsClips = null;
                prevBsWeights = null;
            }
            if (pendingSeek > 0f) {
                float t = pendingSeek;
                pendingSeek = 0f;
                play(t, false);
            }
        }
        return true;
    }

    private void resolveShown() {
        resolvedVersion = ctl.setVersion();
        Playable target = shownTarget;
        Object res = target == null ? null : ctl.resolve(target);
        shownRes = res;
        shownRef = target instanceof AnimationRef r ? r : null;
        float refSpeed = shownRef != null ? shownRef.speed() : 1f;
        bsClips = null;
        if (res instanceof ClipInfo ci) {
            shownSpeed = refSpeed * ci.speed();
            int loops = shownRef.loops();
            shownLoops = loops == AnimationRef.LOOPS_DEFAULT ? (ci.loop() ? -1 : 1) : loops;
            if (shownFixed) shownLoops = -1;
        } else if (res instanceof BlendSpace bs) {
            shownSpeed = refSpeed;
            shownLoops = -1;
            ClipInfo[] clips = new ClipInfo[bs.size()];
            for (int i = 0; i < clips.length; i++) {
                clips[i] = ctl.resolve(bs.clip(i)) instanceof ClipInfo ci ? ci : null;
            }
            bsClips = clips;
            bsWeights = new float[bs.size()];
            updateBlendWeights(bs, 0f);
        } else {
            shownSpeed = refSpeed;
            shownLoops = -1;
        }
    }

    private void updateBlendWeights(BlendSpace bs, float dt) {
        float[] weights = bsWeights;
        if (weights == null) return;
        float x = ctl.getParameter(bs.parameterX());
        String py = bs.parameterY();
        float y = py != null ? ctl.getParameter(py) : 0f;
        if (!smoothInit || bs.smoothing() <= 0f) {
            smoothX = x;
            smoothY = y;
            smoothInit = true;
        } else if (dt > 0f) {
            float a = 1f - (float) Math.exp(-dt / bs.smoothing());
            smoothX += (x - smoothX) * a;
            smoothY += (y - smoothY) * a;
        }
        bs.weights(smoothX, smoothY, weights);
    }

    /** Weighted cycle length (seconds) of the shown blend space. */
    private float cycleLength() {
        ClipInfo[] clips = bsClips;
        float[] weights = bsWeights;
        if (clips == null || weights == null || !(shownRes instanceof BlendSpace bs)) return 0f;
        float sum = 0f, total = 0f;
        for (int i = 0; i < clips.length; i++) {
            ClipInfo ci = clips[i];
            if (ci == null || weights[i] <= 0f) continue;
            float s = bs.clip(i).speed() * ci.speed();
            sum += weights[i] * ci.duration() / s;
            total += weights[i];
        }
        return total > 0f ? sum / total : 0f;
    }

    private int dominantBlendClip() {
        ClipInfo[] clips = bsClips;
        float[] weights = bsWeights;
        if (clips == null || weights == null) return -1;
        int best = -1;
        float bestW = 0f;
        for (int i = 0; i < clips.length; i++) {
            if (clips[i] != null && weights[i] > bestW) {
                bestW = weights[i];
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ events

    private void fireStartMarkers() {
        if (shownRes instanceof ClipInfo ci) {
            fireAtZero(ci.markers(), ci.name());
            if (shownRef != null) fireAtZero(shownRef.events(), ci.name());
        } else if (shownRes instanceof BlendSpace bs) {
            int dom = dominantBlendClip();
            if (dom >= 0) {
                ClipInfo ci = bsClips[dom];
                fireAtZero(ci.markers(), ci.name());
                fireAtZero(bs.clip(dom).events(), ci.name());
            }
        }
    }

    private void fireAtZero(List<EventMarker> markers, String clip) {
        for (int i = 0; i < markers.size(); i++) {
            EventMarker m = markers.get(i);
            if (m.time() > 0f) break;
            ctl.fireEvent(this, clip, m.event());
        }
    }

    private void fireClipMarkers(ClipInfo ci, double from, double to, boolean fire) {
        boolean loopCallbacks = ctl.hasListeners();
        if (!fire && !loopCallbacks) return;
        float d = ci.duration();
        if (d <= 1e-6f || to <= from) return;
        List<EventMarker> a = ci.markers();
        List<EventMarker> b = shownRef != null ? shownRef.events() : List.of();
        if (fire && a.isEmpty() && b.isEmpty() && !loopCallbacks) return;
        long kStart = (long) Math.floor(from / d);
        long kEnd = (long) Math.floor(to / d);
        if (kEnd - kStart > 64) kStart = kEnd - 64;
        for (long k = kStart; k <= kEnd; k++) {
            if (shownLoops > 0 && k >= shownLoops) break;
            double base = k * (double) d;
            if (loopCallbacks && k > 0 && base > from && base <= to) ctl.notifyLoop(this, ci.name());
            if (fire) {
                fireRange(a, base, 1.0, from, to, ci.name());
                fireRange(b, base, 1.0, from, to, ci.name());
            }
        }
    }

    private void fireBlendMarkers(double from, double to, boolean fire) {
        boolean loopCallbacks = ctl.hasListeners();
        if (!fire && !loopCallbacks) return;
        int dom = dominantBlendClip();
        if (dom < 0 || !(shownRes instanceof BlendSpace bs)) return;
        ClipInfo ci = bsClips[dom];
        float d = ci.duration();
        if (d <= 1e-6f || to <= from) return;
        long kStart = (long) Math.floor(from);
        long kEnd = (long) Math.floor(to);
        if (kEnd - kStart > 64) kStart = kEnd - 64;
        double scale = 1.0 / d;
        for (long k = kStart; k <= kEnd; k++) {
            if (loopCallbacks && k > 0 && k > from && k <= to) ctl.notifyLoop(this, bs.name());
            if (fire) {
                fireRange(ci.markers(), k, scale, from, to, ci.name());
                fireRange(bs.clip(dom).events(), k, scale, from, to, ci.name());
            }
        }
    }

    private void fireRange(List<EventMarker> markers, double base, double scale, double from, double to, String clip) {
        for (int i = 0; i < markers.size(); i++) {
            EventMarker m = markers.get(i);
            double x = base + m.time() * scale;
            if (x > to) break;
            if (x > from) ctl.fireEvent(this, clip, m.event());
        }
    }

    // ------------------------------------------------------------------ sampling

    /** Time shown for the current target: clip seconds, or blend-space phase in [0, 1). */
    float displayTime() {
        if (shownRes instanceof ClipInfo ci) return clipTimeOf(ci.duration(), clipPos, shownLoops);
        if (shownRes instanceof BlendSpace) return (float) (clipPos - Math.floor(clipPos));
        return 0f;
    }

    static float clipTimeOf(float duration, double pos, int loops) {
        if (duration <= 0f) return 0f;
        if (loops > 0 && pos >= (double) loops * duration) return duration;
        double m = pos % duration;
        return (float) (m < 0 ? m + duration : m);
    }

    /** True if this track shows a plain clip with no step blend in progress (see {@link #sampleDirect}). */
    boolean canSampleDirect() {
        if (stepBlendT < stepBlendDur || resolvedVersion != ctl.setVersion()) return false;
        if (shownRes instanceof ClipInfo) return true;
        return shownRes instanceof BlendSpace && singleBlendClip(bsClips, bsWeights) >= 0;
    }

    /**
     * Write the shown clip's animated properties straight into {@code pose}, leaving the others alone. Only valid
     * after {@link #canSampleDirect()}; equivalent to {@link #sample} followed by a full-weight blend.
     */
    void sampleDirect(Pose pose) {
        if (shownRes instanceof ClipInfo ci) {
            ci.clip().sample(displayTime(), pose);
        } else {
            ClipInfo ci = bsClips[singleBlendClip(bsClips, bsWeights)];
            ci.clip().sample(displayTime() * ci.duration(), pose);
        }
    }

    /** The only blend-space clip with weight, or -1 when several (or none) contribute. */
    private static int singleBlendClip(@Nullable ClipInfo[] clips, @Nullable float[] weights) {
        if (clips == null || weights == null) return -1;
        int found = -1;
        for (int i = 0; i < clips.length; i++) {
            if (clips[i] == null || weights[i] <= WEIGHT_EPSILON) continue;
            if (found >= 0) return -1;
            found = i;
        }
        return found;
    }

    /**
     * Sample this track's local pose into {@code out}, with per node/path weights ({@link Channel#PATHS} per node)
     * in {@code w}.
     *
     * @return false if the track shows nothing
     */
    boolean sample(Skeleton sk, Pose out, float[] w, Pose prevPose, float[] prevW, Pose blendScratch) {
        if (!ensureResolved()) return false;
        boolean has = sampleRes(sk, shownRes, displayTime(), bsClips, bsWeights, out, w, blendScratch);
        if (stepBlendT < stepBlendDur) {
            float a = stepBlendEase.apply(stepBlendT / stepBlendDur);
            boolean hasPrev = prevRes != null && sampleRes(sk, prevRes, prevTime, prevBsClips, prevBsWeights, prevPose, prevW, blendScratch);
            if (!has && !hasPrev) return false;
            if (!has) {
                out.setRest(sk);
                Arrays.fill(w, 0f);
            }
            float[] pw = hasPrev ? prevW : null;
            int n = sk.size();
            for (int node = 0; node < n; node++) {
                int k = node * Channel.PATHS;
                mixPath(k, out.t, prevPose.t, node * 3, 3, w, pw, a, false);
                mixPath(k + 1, out.r, prevPose.r, node * 4, 4, w, pw, a, true);
                mixPath(k + 2, out.s, prevPose.s, node * 3, 3, w, pw, a, false);
            }
            for (int node : sk.morphNodes()) {
                mixPath(node * Channel.PATHS + 3, out.m, prevPose.m, sk.morphOffset(node), sk.morphCount(node), w, pw, a, false);
            }
            return true;
        }
        return has;
    }

    private static void mixPath(int k, float[] cur, float[] prev, int o, int comps, float[] w, @Nullable float[] prevW, float a, boolean quat) {
        float wc = w[k];
        float wp = prevW != null ? prevW[k] : 0f;
        if (wc > 0f && wp > 0f) {
            if (quat) PoseMath.nlerp(prev, o, cur, o, a, cur, o);
            else for (int c = 0; c < comps; c++) cur[o + c] = prev[o + c] + (cur[o + c] - prev[o + c]) * a;
            w[k] = wp + (wc - wp) * a;
        } else if (wc > 0f) {
            w[k] = wc * a;
        } else if (wp > 0f) {
            System.arraycopy(prev, o, cur, o, comps);
            w[k] = wp * (1f - a);
        }
    }

    private static boolean sampleRes(Skeleton sk, @Nullable Object res, float time, @Nullable ClipInfo[] clips,
                                     @Nullable float[] weights, Pose out, float[] w, Pose scratch) {
        if (res instanceof ClipInfo ci) {
            // no reset: readers only look at entries the weights mark as animated
            ci.clip().sample(time, out);
            System.arraycopy(ci.clip().pathMask(), 0, w, 0, w.length);
            return true;
        }
        if (res instanceof BlendSpace bs && clips != null && weights != null) {
            return sampleBlend(sk, bs, time, clips, weights, out, w, scratch);
        }
        return false;
    }

    /**
     * Weighted average of the blend space's clips. Only animated properties are touched: every property some clip
     * animates starts at the rest value and each clip adds {@code weight * (value - rest)} for the properties it
     * animates (clips that leave a property alone contribute the rest value, as a full-pose average would).
     * Clips sample straight into the scratch pose, without resetting it, since only their own entries are read.
     */
    private static boolean sampleBlend(Skeleton sk, BlendSpace bs, float phase, ClipInfo[] clips, float[] weights,
                                       Pose out, float[] w, Pose scratch) {
        float total = 0f;
        Arrays.fill(w, 0f);
        for (int i = 0; i < clips.length; i++) {
            if (clips[i] == null || weights[i] <= WEIGHT_EPSILON) continue;
            total += weights[i];
            for (int k : clips[i].clip().animated()) w[k] = 1f;
        }
        if (total <= 0f) return false;

        float[] rt = sk.restT(), rr = sk.restR(), rs = sk.restS(), rm = sk.restMorph();
        // start every animated property at rest
        for (int k = 0, n = w.length; k < n; k++) {
            if (w[k] == 0f) continue;
            int node = k >> 2;
            switch (k & 3) {
                case Channel.TRANSLATION -> System.arraycopy(rt, node * 3, out.t, node * 3, 3);
                case Channel.ROTATION -> System.arraycopy(rr, node * 4, out.r, node * 4, 4);
                case Channel.SCALE -> System.arraycopy(rs, node * 3, out.s, node * 3, 3);
                default -> {
                    int o = sk.morphOffset(node);
                    System.arraycopy(rm, o, out.m, o, sk.morphCount(node));
                }
            }
        }
        float inv = 1f / total;
        for (int i = 0; i < clips.length; i++) {
            ClipInfo ci = clips[i];
            if (ci == null || weights[i] <= WEIGHT_EPSILON) continue;
            float wi = weights[i] * inv;
            ci.clip().sample(phase * ci.duration(), scratch);
            for (int k : ci.clip().animated()) {
                int node = k >> 2;
                switch (k & 3) {
                    case Channel.TRANSLATION, Channel.SCALE -> {
                        float[] src = (k & 3) == Channel.TRANSLATION ? scratch.t : scratch.s;
                        float[] dst = (k & 3) == Channel.TRANSLATION ? out.t : out.s;
                        float[] rest = (k & 3) == Channel.TRANSLATION ? rt : rs;
                        int o = node * 3;
                        dst[o] += (src[o] - rest[o]) * wi;
                        dst[o + 1] += (src[o + 1] - rest[o + 1]) * wi;
                        dst[o + 2] += (src[o + 2] - rest[o + 2]) * wi;
                    }
                    case Channel.ROTATION -> {
                        int o = node * 4;
                        float sign = PoseMath.dot4(rr, o, scratch.r, o) < 0f ? -wi : wi;
                        out.r[o] += scratch.r[o] * sign - rr[o] * wi;
                        out.r[o + 1] += scratch.r[o + 1] * sign - rr[o + 1] * wi;
                        out.r[o + 2] += scratch.r[o + 2] * sign - rr[o + 2] * wi;
                        out.r[o + 3] += scratch.r[o + 3] * sign - rr[o + 3] * wi;
                    }
                    default -> {
                        for (int o = sk.morphOffset(node), end = o + sk.morphCount(node); o < end; o++) out.m[o] += (scratch.m[o] - rm[o]) * wi;
                    }
                }
            }
        }
        for (int k = 1, n = w.length; k < n; k += 4) {
            if (w[k] > 0f) PoseMath.normalizeQuat(out.r, (k >> 2) * 4);
        }
        return true;
    }
}
