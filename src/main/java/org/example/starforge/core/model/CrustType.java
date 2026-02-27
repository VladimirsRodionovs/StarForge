package org.example.starforge.core.model;

/**
 * High-level crust composition tag.
 *
 * This is primarily used for gameplay (resources, events) and for
 * secondary atmosphere/outgassing later. The bulk (Fe/Rock/Ice) is the
 * primary driver for radius/density.
 */
public enum CrustType {
    BASALTIC,
    GRANITIC,
    ICY,
    CARBONACEOUS,
    METALLIC,
    MIXED
}
