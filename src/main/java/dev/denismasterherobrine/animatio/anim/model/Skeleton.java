package dev.denismasterherobrine.animatio.anim.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The node hierarchy of a model with its rest (bind) pose. Every glTF node is a "bone", so rigid node
 * animation and skinned animation use the same machinery.
 * <p>
 * Morph target weights belong to the nodes that instance a mesh with targets: node {@code i} owns
 * {@link #morphCount(int)} consecutive weight slots starting at {@link #morphOffset(int)} of a pose's
 * {@link Pose#m} array.
 * <p>
 * The arrays returned by accessors are shared and must not be modified.
 */
public final class Skeleton {
    private static final int[] NO_SLOTS = new int[0];

    private final String[] names;
    private final int[] parents;
    private final int[] order;
    private final float[] restT;
    private final float[] restR;
    private final float[] restS;
    private final Map<String, Integer> index;

    private final int[] morphOffset;
    private final int[] morphCount;
    private final int[] morphNodes;
    private final float[] restMorph;
    private final String[] morphNames;
    private final Map<String, int[]> morphIndex;

    public Skeleton(String[] names, int[] parents, float[] restT, float[] restR, float[] restS) {
        this(names, parents, restT, restR, restS, new int[names.length], new float[0], new String[0]);
    }

    /**
     * @param morphCount per node: number of morph weights it owns (0 for most nodes)
     * @param restMorph  default weights of every slot, in node order
     * @param morphNames target name of every slot
     */
    public Skeleton(String[] names, int[] parents, float[] restT, float[] restR, float[] restS,
                    int[] morphCount, float[] restMorph, String[] morphNames) {
        int n = names.length;
        if (parents.length != n || restT.length != n * 3 || restR.length != n * 4 || restS.length != n * 3 || morphCount.length != n) {
            throw new IllegalArgumentException("Skeleton arrays have inconsistent lengths");
        }
        this.names = names;
        this.parents = parents;
        this.restT = restT;
        this.restR = restR;
        this.restS = restS;
        this.index = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) index.putIfAbsent(names[i], i);
        this.order = topologicalOrder(parents);

        this.morphCount = morphCount;
        this.morphOffset = new int[n];
        int slots = 0, withMorphs = 0;
        for (int i = 0; i < n; i++) {
            if (morphCount[i] < 0) throw new IllegalArgumentException("Negative morph count for node " + i);
            morphOffset[i] = slots;
            slots += morphCount[i];
            if (morphCount[i] > 0) withMorphs++;
        }
        if (restMorph.length != slots || morphNames.length != slots) throw new IllegalArgumentException("Morph arrays have inconsistent lengths");
        this.restMorph = restMorph;
        this.morphNames = morphNames;
        this.morphNodes = new int[withMorphs];
        for (int i = 0, k = 0; i < n; i++) if (morphCount[i] > 0) morphNodes[k++] = i;
        Map<String, List<Integer>> byName = new HashMap<>();
        for (int node : morphNodes) {
            for (int j = 0; j < morphCount[node]; j++) {
                int slot = morphOffset[node] + j;
                byName.computeIfAbsent(morphNames[slot], k -> new ArrayList<>()).add(slot);
                byName.computeIfAbsent(names[node] + "/" + morphNames[slot], k -> new ArrayList<>()).add(slot);
            }
        }
        this.morphIndex = new HashMap<>(byName.size() * 2);
        byName.forEach((k, v) -> morphIndex.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
    }

    private static int[] topologicalOrder(int[] parents) {
        int n = parents.length;
        int[] childCount = new int[n];
        for (int i = 0; i < n; i++) {
            int p = parents[i];
            if (p >= n || p < -1 || p == i) throw new IllegalArgumentException("Invalid parent " + p + " for node " + i);
            if (p >= 0) childCount[p]++;
        }
        // children in CSR form: childStart[p]..childStart[p+1]
        int[] childStart = new int[n + 1];
        for (int i = 0; i < n; i++) childStart[i + 1] = childStart[i] + childCount[i];
        int[] children = new int[n];
        int[] fill = Arrays.copyOf(childStart, n);
        for (int i = 0; i < n; i++) {
            int p = parents[i];
            if (p >= 0) children[fill[p]++] = i;
        }
        int[] order = new int[n];
        int[] stack = new int[n];
        int sp = 0, k = 0;
        for (int i = n - 1; i >= 0; i--) {
            if (parents[i] < 0) stack[sp++] = i;
        }
        while (sp > 0) {
            int i = stack[--sp];
            if (k >= n) throw new IllegalArgumentException("Node hierarchy contains a cycle");
            order[k++] = i;
            for (int c = childStart[i + 1] - 1; c >= childStart[i]; c--) {
                if (sp >= n) throw new IllegalArgumentException("Node hierarchy contains a cycle");
                stack[sp++] = children[c];
            }
        }
        if (k != n) throw new IllegalArgumentException("Node hierarchy contains a cycle");
        return order;
    }

    public int size() {
        return names.length;
    }

    public String name(int node) {
        return names[node];
    }

    public int parent(int node) {
        return parents[node];
    }

    /** Parent index of every node (-1 for roots). Do not modify. */
    public int[] parents() {
        return parents;
    }

    /** Node indices ordered so that every parent comes before its children. */
    public int[] order() {
        return order;
    }

    /** Index of the first node with this name, or {@code -1}. */
    public int find(String name) {
        Integer i = index.get(name);
        return i == null ? -1 : i;
    }

    public float[] restT() {
        return restT;
    }

    public float[] restR() {
        return restR;
    }

    public float[] restS() {
        return restS;
    }

    public boolean isAncestor(int ancestor, int node) {
        for (int i = parents[node]; i >= 0; i = parents[i]) {
            if (i == ancestor) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ morph targets

    /** Total number of morph weight slots (length of {@link Pose#m}). */
    public int morphSlots() {
        return restMorph.length;
    }

    public int morphOffset(int node) {
        return morphOffset[node];
    }

    public int morphCount(int node) {
        return morphCount[node];
    }

    /** Nodes that own morph weights, ascending. Do not modify. */
    public int[] morphNodes() {
        return morphNodes;
    }

    /** Default weight of every slot. Do not modify. */
    public float[] restMorph() {
        return restMorph;
    }

    /** Target name of a slot ({@code extras.targetNames} in glTF, else {@code target_<k>}). */
    public String morphName(int slot) {
        return morphNames[slot];
    }

    /**
     * Slots of a morph target, by target name (every mesh that has it) or by {@code "<node>/<target>"}.
     * Returns an empty array when unknown. Do not modify.
     */
    public int[] findMorph(String name) {
        int[] slots = morphIndex.get(name);
        return slots != null ? slots : NO_SLOTS;
    }
}
