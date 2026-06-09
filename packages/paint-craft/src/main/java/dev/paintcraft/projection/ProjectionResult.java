package dev.paintcraft.projection;

/**
 * Result of a projection resolve operation.
 * Contains the resolved surface (public output) and internal state
 * needed for incremental re-resolution.
 */
public record ProjectionResult(
    ResolvedSurface surface,
    ProjectionState state
) {}
