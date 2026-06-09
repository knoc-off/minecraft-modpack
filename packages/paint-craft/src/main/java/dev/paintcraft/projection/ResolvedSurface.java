package dev.paintcraft.projection;

import java.util.List;

public record ResolvedSurface(
    List<SurfaceFragment> fragments
) {
    public boolean isEmpty() {
        return fragments.isEmpty();
    }
}
