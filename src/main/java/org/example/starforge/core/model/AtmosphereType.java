package org.example.starforge.core.model;

/**
 * High-level atmosphere classification for UI/cataloging.
 * Detailed composition is stored separately (partial pressures per gas).
 */
public enum AtmosphereType {
    NONE,
    THIN,
    SUB_EARTH,
    N2_DOMINATED,
    CO2_THICK,
    STEAM,
    HHE
}
