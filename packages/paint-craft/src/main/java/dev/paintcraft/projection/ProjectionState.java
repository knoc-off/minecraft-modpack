package dev.paintcraft.projection;

import java.util.List;

/**
 * Internal state needed by ProjectionResolver for incremental re-resolution.
 * Opaque to consumers — just pass it back to resolveIncremental().
 */
public record ProjectionState(
    float[] depthMap,
    List<ProjectionResolver.FaceCandidate> candidates
) {
    public boolean canResolveIncrementally() {
        return candidates != null && !candidates.isEmpty();
    }
}
