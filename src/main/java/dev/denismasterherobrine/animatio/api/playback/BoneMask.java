package dev.denismasterherobrine.animatio.api.playback;

import dev.denismasterherobrine.animatio.anim.model.Skeleton;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Limits a track to some bones, e.g. play a wave only on the right arm:
 * <pre>{@code
 * controller.play(WAVE, PlayOptions.defaults().mask(BoneMask.subtree("arm_right")));
 * }</pre>
 * Without a mask a track already affects only the bones its clips animate.
 */
public final class BoneMask {
    private static final BoneMask ALL = new BoneMask(Set.of(), Set.of(), Set.of(), Set.of(), true);

    private final Set<String> bones;
    private final Set<String> subtrees;
    private final Set<String> excludedBones;
    private final Set<String> excludedSubtrees;
    private final boolean includeAll;

    private record Cache(Skeleton skeleton, float[] weights) {
    }

    private volatile Cache cache;

    private BoneMask(Set<String> bones, Set<String> subtrees, Set<String> excludedBones, Set<String> excludedSubtrees, boolean includeAll) {
        this.bones = bones;
        this.subtrees = subtrees;
        this.excludedBones = excludedBones;
        this.excludedSubtrees = excludedSubtrees;
        this.includeAll = includeAll;
    }

    public static BoneMask all() {
        return ALL;
    }

    /** Only these bones (not their children). */
    public static BoneMask of(String... bones) {
        return new BoneMask(Set.of(bones), Set.of(), Set.of(), Set.of(), false);
    }

    /** These bones and all their descendants. */
    public static BoneMask subtree(String... roots) {
        return new BoneMask(Set.of(), Set.of(roots), Set.of(), Set.of(), false);
    }

    public BoneMask and(BoneMask other) {
        return new BoneMask(union(bones, other.bones), union(subtrees, other.subtrees),
                union(excludedBones, other.excludedBones), union(excludedSubtrees, other.excludedSubtrees),
                includeAll || other.includeAll);
    }

    public BoneMask excluding(String... bones) {
        return new BoneMask(this.bones, subtrees, union(excludedBones, Set.of(bones)), excludedSubtrees, includeAll);
    }

    public BoneMask excludingSubtree(String... roots) {
        return new BoneMask(bones, subtrees, excludedBones, union(excludedSubtrees, Set.of(roots)), includeAll);
    }

    public boolean includesAll() {
        return includeAll;
    }

    public Set<String> bones() {
        return bones;
    }

    public Set<String> subtrees() {
        return subtrees;
    }

    public Set<String> excludedBones() {
        return excludedBones;
    }

    public Set<String> excludedSubtrees() {
        return excludedSubtrees;
    }

    /** Internal factory used by deserialisation. */
    public static BoneMask create(boolean includeAll, List<String> bones, List<String> subtrees, List<String> excludedBones, List<String> excludedSubtrees) {
        return new BoneMask(Set.copyOf(bones), Set.copyOf(subtrees), Set.copyOf(excludedBones), Set.copyOf(excludedSubtrees), includeAll);
    }

    /** Per-node weight (0 or 1) for a skeleton. Unknown bone names are ignored. */
    public float[] resolve(Skeleton skeleton) {
        Cache c = cache;
        if (c != null && c.skeleton == skeleton) return c.weights;
        int n = skeleton.size();
        float[] w = new float[n];
        for (int i = 0; i < n; i++) {
            String name = skeleton.name(i);
            boolean included = includeAll || bones.contains(name) || selfOrAncestorIn(skeleton, i, subtrees);
            if (excludedBones.contains(name) || selfOrAncestorIn(skeleton, i, excludedSubtrees)) included = false;
            w[i] = included ? 1f : 0f;
        }
        cache = new Cache(skeleton, w);
        return w;
    }

    private static boolean selfOrAncestorIn(Skeleton skeleton, int node, Set<String> names) {
        if (names.isEmpty()) return false;
        for (int i = node; i >= 0; i = skeleton.parent(i)) {
            if (names.contains(skeleton.name(i))) return true;
        }
        return false;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        if (b.isEmpty()) return a;
        if (a.isEmpty()) return b;
        Set<String> s = new LinkedHashSet<>(a);
        s.addAll(b);
        return Set.copyOf(s);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof BoneMask m && includeAll == m.includeAll && bones.equals(m.bones)
                && subtrees.equals(m.subtrees) && excludedBones.equals(m.excludedBones) && excludedSubtrees.equals(m.excludedSubtrees);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(bones, subtrees, excludedBones, excludedSubtrees, includeAll);
    }
}
