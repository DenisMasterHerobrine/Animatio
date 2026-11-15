package dev.denismasterherobrine.animatio.api.playable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Blends clips placed along one parameter. With samples Idle (0), Walk (2) and Run (6), a speed of 3.5
 * gives Walk 62.5% and Run 37.5%. Values outside the range clamp to the first or last sample.
 * <pre>{@code
 * BlendSpace1D LOCOMOTION = BlendSpace1D.builder("locomotion", "speed")
 *         .sample(0, IDLE).sample(2, WALK).sample(6, RUN)
 *         .build();
 * }</pre>
 */
public final class BlendSpace1D extends BlendSpace {
    private final String parameter;
    private final float[] positions;

    private BlendSpace1D(String name, String parameter, AnimationRef[] clips, float[] positions, float smoothing) {
        super(name, clips, smoothing);
        this.parameter = Objects.requireNonNull(parameter, "parameter");
        this.positions = positions;
    }

    public static Builder builder(String name, String parameter) {
        return new Builder(name, parameter);
    }

    public float position(int index) {
        return positions[index];
    }

    @Override
    public String parameterX() {
        return parameter;
    }

    @Override
    public String parameterY() {
        return null;
    }

    @Override
    public void weights(float x, float y, float[] out) {
        Arrays.fill(out, 0, positions.length, 0f);
        int n = positions.length;
        if (n == 1 || !(x > positions[0])) { // also catches NaN
            out[0] = 1f;
            return;
        }
        if (x >= positions[n - 1]) {
            out[n - 1] = 1f;
            return;
        }
        int i = 0;
        while (positions[i + 1] < x) i++;
        float t = (x - positions[i]) / (positions[i + 1] - positions[i]);
        out[i] = 1f - t;
        out[i + 1] = t;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof BlendSpace1D b && name.equals(b.name) && parameter.equals(b.parameter)
                && smoothing == b.smoothing && Arrays.equals(positions, b.positions) && Arrays.equals(clips, b.clips);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, parameter, Arrays.hashCode(positions), Arrays.hashCode(clips));
    }

    @Override
    public String toString() {
        return "BlendSpace1D[" + name + " on " + parameter + ", " + clips.length + " samples]";
    }

    public static final class Builder {
        private record Sample(float x, AnimationRef clip) {
        }

        private final String name;
        private final String parameter;
        private final List<Sample> samples = new ArrayList<>();
        private float smoothing;

        private Builder(String name, String parameter) {
            this.name = Objects.requireNonNull(name, "name");
            this.parameter = Objects.requireNonNull(parameter, "parameter");
        }

        public Builder sample(float position, AnimationRef clip) {
            if (!Float.isFinite(position)) throw new IllegalArgumentException("Invalid position " + position);
            samples.add(new Sample(position, Objects.requireNonNull(clip, "clip")));
            return this;
        }

        public Builder smoothing(float seconds) {
            this.smoothing = seconds;
            return this;
        }

        public BlendSpace1D build() {
            List<Sample> sorted = new ArrayList<>(samples);
            sorted.sort(Comparator.comparingDouble(Sample::x));
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i).x == sorted.get(i - 1).x) {
                    throw new IllegalArgumentException("Blend space '" + name + "' has two samples at " + sorted.get(i).x);
                }
            }
            AnimationRef[] clips = new AnimationRef[sorted.size()];
            float[] positions = new float[sorted.size()];
            for (int i = 0; i < clips.length; i++) {
                clips[i] = sorted.get(i).clip;
                positions[i] = sorted.get(i).x;
            }
            return new BlendSpace1D(name, parameter, clips, positions, smoothing);
        }
    }
}
