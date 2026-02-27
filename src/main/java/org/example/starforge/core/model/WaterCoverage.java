package org.example.starforge.core.model;

/**
 * Coarse classification of surface water coverage.
 *
 * This is used for gameplay/biome hooks and does not attempt to be a full
 * geophysical ocean/continent model.
 */
public enum WaterCoverage {
    DRY,
    LAKES,
    SEAS,
    OCEAN,
    OCEANS,
    MANY_OCEANS,
    ARCHIPELAGOS,
    OCEAN_PLANET
}
