package org.example.starforge.core.model;

/**
 * High-level origin of life on a body.
 *
 * <p>This enum describes where the biosphere came from (native vs seeded), and
 * whether it is still active today. The *_DORMANT variants mean there are signs
 * of past life (e.g. organics / hydrocarbons), but no currently active biosphere.</p>
 */
public enum BiosphereProvenance {
    /** No life (abiotic). */
    ABIOGENIC,

    /** Native, long-run biosphere that formed naturally in-system. */
    PRIMORDIAL,

    /** Native biosphere existed in the past, but is currently extinct/dormant. */
    PRIMORDIAL_DORMANT,

    /** Life was seeded comparatively recently (multiple stages, includes higher life). */
    SEEDED_RECENT,

    /** Seeded life existed in the past but is currently extinct/dormant. */
    SEEDED_RECENT_DORMANT,

    /** Backward-compat / placeholder: treat as ABIOGENIC at runtime. */
    UNKNOWN
}
