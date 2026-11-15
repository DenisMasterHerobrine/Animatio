package dev.denismasterherobrine.animatio.anim.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * The node hierarchy of a model with its rest (bind) pose. Every glTF node is a "bone", so rigid node
 * animation and skinned animation use the same machinery.
 * <p>
 * The arrays returned by accessors are shared and must not be modified.
 */
public final class Skeleton {
    private final String[] names;
    private final int[] parents;
    private final int[] order;
    private final float[] restT;
    private final float[] restR;
    private final float[] restS;
    private final Map<String, Integer> index;

    public Skeleton(String[] names, int[] parents, float[] restT, float[] restR, float[] restS) {
        int n = names.length;
        if (parents.length != n || restT.length != n * 3 || restR.length != n * 4 || restS.length != n * 3) {
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
    }

    private static int[] topologicalOrder(int[] parents) {
        int n = parents.length;
        int[] childCount = new int[n];
        for (int i = 0; i < n; i++) {
            int p = parents[i];
            if (p >= n || p < -1 || p == i) throw new IllegalArgumentException("Invalid parent " + p + " for node " + i);
            if (p >= 0) childCount[p]++;
        }
        int[][] children = new int[n][];
        for (int i = 0; i < n; i++) children[i] = new int[childCount[i]];
        int[] fill = new int[n];
        for (int i = 0; i < n; i++) {
            int p = parents[i];
            if (p >= 0) children[p][fill[p]++] = i;
        }
        int[] order = new int[n];
        int k = 0;
        Deque<Integer> stack = new ArrayDeque<>();
        for (int i = n - 1; i >= 0; i--) {
            if (parents[i] < 0) stack.push(i);
        }
        while (!stack.isEmpty()) {
            int i = stack.pop();
            order[k++] = i;
            for (int c = children[i].length - 1; c >= 0; c--) stack.push(children[i][c]);
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
}
