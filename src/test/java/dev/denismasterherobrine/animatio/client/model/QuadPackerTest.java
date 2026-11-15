package dev.denismasterherobrine.animatio.client.model;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class QuadPackerTest {
    /** The triangles the game draws for a quad list: (0,1,2) and (2,3,0) of each quad, as canonical rotations. */
    private static Set<List<Integer>> drawnTriangles(int[] quads) {
        Set<List<Integer>> out = new HashSet<>();
        for (int i = 0; i < quads.length; i += 4) {
            add(out, quads[i], quads[i + 1], quads[i + 2]);
            add(out, quads[i + 2], quads[i + 3], quads[i]);
        }
        return out;
    }

    private static Set<List<Integer>> triangles(int[] tris) {
        Set<List<Integer>> out = new HashSet<>();
        for (int i = 0; i < tris.length; i += 3) add(out, tris[i], tris[i + 1], tris[i + 2]);
        return out;
    }

    private static void add(Set<List<Integer>> out, int a, int b, int c) {
        if (a == b || b == c || a == c) return; // degenerate
        // canonical rotation keeps winding
        if (a < b && a < c) out.add(List.of(a, b, c));
        else if (b < a && b < c) out.add(List.of(b, c, a));
        else out.add(List.of(c, a, b));
    }

    @Test
    void mergesQuadSplitAlongDiagonal() {
        int[] tris = {0, 1, 2, 0, 2, 3};
        int[] quads = QuadPacker.pack(tris);
        assertEquals(4, quads.length);
        assertEquals(triangles(tris), drawnTriangles(quads));
    }

    @Test
    void mergesOtherRotations() {
        int[] tris = {5, 6, 7, 7, 8, 5};
        int[] quads = QuadPacker.pack(tris);
        assertEquals(4, quads.length);
        assertEquals(triangles(tris), drawnTriangles(quads));
    }

    @Test
    void keepsLoneTrianglesAsDegenerateQuads() {
        int[] tris = {0, 1, 2, 3, 4, 5};
        int[] quads = QuadPacker.pack(tris);
        assertArrayEquals(new int[]{0, 1, 2, 2, 3, 4, 5, 5}, quads);
    }

    @Test
    void doesNotMergeMismatchedWinding() {
        int[] tris = {0, 1, 2, 0, 3, 2}; // second triangle walks the shared edge the same way: flipped face
        int[] quads = QuadPacker.pack(tris);
        assertEquals(8, quads.length);
        assertEquals(triangles(tris), drawnTriangles(quads));
    }

    @Test
    void preservesEveryTriangleOfABox() {
        // a cube: 6 faces, each split into two triangles like the debug robot generator does
        int[] tris = new int[36];
        for (int f = 0; f < 6; f++) {
            int b = f * 4;
            int[] face = {b, b + 1, b + 2, b, b + 2, b + 3};
            System.arraycopy(face, 0, tris, f * 6, 6);
        }
        int[] quads = QuadPacker.pack(tris);
        assertEquals(24, quads.length, "6 faces -> 6 quads");
        assertEquals(triangles(tris), drawnTriangles(quads));
    }
}
