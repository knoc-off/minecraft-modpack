package dev.structurestash.item;

import java.util.Arrays;

/**
 * Wrapper for blueprint structure data that provides content-based equality.
 * Required for item stacking — Java's {@code byte[].equals()} uses reference
 * equality, which would prevent identical blueprints from merging.
 */
public record BlueprintData(byte[] data) {

    @Override
    public boolean equals(Object o) {
        return o instanceof BlueprintData bd && Arrays.equals(data, bd.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }
}
