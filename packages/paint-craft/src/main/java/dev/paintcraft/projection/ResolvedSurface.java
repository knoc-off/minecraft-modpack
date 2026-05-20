package dev.paintcraft.projection;

import java.util.List;

public record ResolvedSurface(
    List<SurfaceFragment> fragments,
    int[] backgroundPixels,
    float[] depthMap,
    float minDepth,
    List<ProjectionResolver.FaceCandidate> candidates
) {
    /** Backward-compatible constructor for callers that don't provide candidates. */
    public ResolvedSurface(List<SurfaceFragment> fragments, int[] backgroundPixels,
                           float[] depthMap, float minDepth) {
        this(fragments, backgroundPixels, depthMap, minDepth, List.of());
    }

    public boolean isEmpty() {
        return fragments.isEmpty();
    }
}
