package dev.paintcraft.projection;

import java.util.List;

public record ResolvedSurface(
    List<SurfaceFragment> fragments,
    int[] backgroundPixels,
    float[] depthMap,
    float minDepth
) {
    public boolean isEmpty() {
        return fragments.isEmpty();
    }
}
