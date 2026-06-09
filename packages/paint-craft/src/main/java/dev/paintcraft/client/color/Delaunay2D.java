package dev.paintcraft.client.color;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bowyer-Watson incremental Delaunay triangulation for 2D point sets.
 * Operates on normalized float coordinates. No external dependencies.
 */
public final class Delaunay2D {

    private Delaunay2D() {}

    /**
     * A unique undirected edge between two point indices.
     */
    public record Edge(int a, int b) {
        public Edge {
            // Canonical ordering for dedup
            if (a > b) { int tmp = a; a = b; b = tmp; }
        }
    }

    /**
     * Result of triangulation: triangle vertex indices packed as triplets,
     * and the set of unique edges.
     */
    public record Result(int[] triangles, int numTriangles, Set<Edge> edges) {}

    /**
     * Compute the Delaunay triangulation of n points.
     * Points are given as parallel arrays xs[0..n-1], ys[0..n-1].
     * Returns triangles referencing indices 0..n-1 (super-triangle removed).
     */
    public static Result triangulate(float[] xs, float[] ys, int n) {
        if (n < 3) return new Result(new int[0], 0, Set.of());

        // Super-triangle vertices (indices n, n+1, n+2)
        // Must enclose all points in [0,1]×[0,1] with generous margin
        float stX0 = -10f, stY0 = -10f;
        float stX1 = 20f,  stY1 = -10f;
        float stX2 = 5f,   stY2 = 20f;

        // Working arrays: extend xs/ys to include super-triangle vertices
        float[] px = new float[n + 3];
        float[] py = new float[n + 3];
        System.arraycopy(xs, 0, px, 0, n);
        System.arraycopy(ys, 0, py, 0, n);
        px[n] = stX0; py[n] = stY0;
        px[n+1] = stX1; py[n+1] = stY1;
        px[n+2] = stX2; py[n+2] = stY2;

        // Triangle storage: each triangle is (v0, v1, v2, circumX, circumY, circumR2)
        // Using parallel lists for simplicity with small n
        List<int[]> tris = new ArrayList<>(n * 3);
        List<float[]> circs = new ArrayList<>(n * 3);

        // Start with the super-triangle
        tris.add(new int[]{n, n+1, n+2});
        circs.add(circumcircle(px, py, n, n+1, n+2));

        // Temporary edge buffer for the boundary polygon
        List<long[]> edgeBuf = new ArrayList<>();

        // Insert each point
        for (int i = 0; i < n; i++) {
            float ix = px[i], iy = py[i];

            // Find all "bad" triangles whose circumcircle contains point i
            edgeBuf.clear();
            int size = tris.size();
            for (int t = size - 1; t >= 0; t--) {
                float[] cc = circs.get(t);
                float dx = ix - cc[0];
                float dy = iy - cc[1];
                if (dx * dx + dy * dy <= cc[2]) {
                    // Bad triangle — collect its edges
                    int[] tri = tris.get(t);
                    edgeBuf.add(new long[]{tri[0], tri[1]});
                    edgeBuf.add(new long[]{tri[1], tri[2]});
                    edgeBuf.add(new long[]{tri[2], tri[0]});
                    // Remove by swap-with-last
                    tris.set(t, tris.get(tris.size() - 1));
                    tris.remove(tris.size() - 1);
                    circs.set(t, circs.get(circs.size() - 1));
                    circs.remove(circs.size() - 1);
                }
            }

            // Find boundary edges (edges that appear exactly once among bad triangles)
            int eCount = edgeBuf.size();
            for (int e = 0; e < eCount; e++) {
                long[] edge = edgeBuf.get(e);
                if (edge == null) continue;
                boolean shared = false;
                for (int f = e + 1; f < eCount; f++) {
                    long[] other = edgeBuf.get(f);
                    if (other == null) continue;
                    if ((edge[0] == other[0] && edge[1] == other[1]) ||
                        (edge[0] == other[1] && edge[1] == other[0])) {
                        // Shared edge — mark both as null
                        edgeBuf.set(f, null);
                        shared = true;
                        break;
                    }
                }
                if (shared) {
                    edgeBuf.set(e, null);
                }
            }

            // Create new triangles from point i to each boundary edge
            for (long[] edge : edgeBuf) {
                if (edge == null) continue;
                int a = (int) edge[0], b = (int) edge[1];
                tris.add(new int[]{i, a, b});
                circs.add(circumcircle(px, py, i, a, b));
            }
        }

        // Remove triangles that reference super-triangle vertices (index >= n)
        Set<Edge> edges = new HashSet<>();
        int[] result = new int[tris.size() * 3];
        int count = 0;
        for (int[] tri : tris) {
            if (tri[0] >= n || tri[1] >= n || tri[2] >= n) continue;
            result[count * 3] = tri[0];
            result[count * 3 + 1] = tri[1];
            result[count * 3 + 2] = tri[2];
            edges.add(new Edge(tri[0], tri[1]));
            edges.add(new Edge(tri[1], tri[2]));
            edges.add(new Edge(tri[2], tri[0]));
            count++;
        }

        return new Result(result, count, edges);
    }

    /**
     * Compute circumcircle of triangle (a, b, c).
     * Returns [centerX, centerY, radiusSquared].
     */
    private static float[] circumcircle(float[] px, float[] py, int a, int b, int c) {
        float ax = px[a], ay = py[a];
        float bx = px[b], by = py[b];
        float cx = px[c], cy = py[c];

        float d = 2f * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by));
        if (Math.abs(d) < 1e-10f) {
            // Degenerate (collinear) — return huge radius so it gets cleaned up
            float midX = (ax + bx + cx) / 3f;
            float midY = (ay + by + cy) / 3f;
            return new float[]{midX, midY, Float.MAX_VALUE};
        }

        float ax2 = ax * ax + ay * ay;
        float bx2 = bx * bx + by * by;
        float cx2 = cx * cx + cy * cy;

        float ux = (ax2 * (by - cy) + bx2 * (cy - ay) + cx2 * (ay - by)) / d;
        float uy = (ax2 * (cx - bx) + bx2 * (ax - cx) + cx2 * (bx - ax)) / d;

        float dx = ax - ux;
        float dy = ay - uy;
        return new float[]{ux, uy, dx * dx + dy * dy};
    }
}
