package org.example.starforge.core.gen;

public final class EventCatalog {
    private EventCatalog() {}

    public static final String EVT_RECENT_OUTGASSING = "RECENT_OUTGASSING";
    public static final String EVT_RECENT_IMPACT_VOLATILES = "RECENT_IMPACT_VOLATILES";
    public static final String EVT_TIDAL_SUPERVOLCANISM = "TIDAL_SUPERVOLCANISM";
    public static final String EVT_RECENT_CAPTURE = "RECENT_CAPTURE";
    public static final String EVT_RECENT_ATMOSPHERE_STRIPPING = "RECENT_ATMOSPHERE_STRIPPING";
    public static final String EVT_RADIogenic_ENRICHMENT = "RADIOGENIC_ENRICHMENT";

    // New for far outer planets / dynamics
    public static final String EVT_SCATTERED_BY_GIANT = "SCATTERED_BY_GIANT";
    public static final String EVT_CAPTURED_PLANET = "CAPTURED_PLANET";

    // Biosphere collapse / extinction (unified life module)
    public static final String EVT_BIOSPHERE_COLLAPSE_IMPACT_WINTER = "BIOSPHERE_COLLAPSE_IMPACT_WINTER";
    public static final String EVT_BIOSPHERE_COLLAPSE_SNOWBALL = "BIOSPHERE_COLLAPSE_SNOWBALL";
    public static final String EVT_BIOSPHERE_COLLAPSE_RUNAWAY_GREENHOUSE = "BIOSPHERE_COLLAPSE_RUNAWAY_GREENHOUSE";
    public static final String EVT_BIOSPHERE_COLLAPSE_ATMOSPHERE_STRIPPING = "BIOSPHERE_COLLAPSE_ATMOSPHERE_STRIPPING";
    public static final String EVT_BIOSPHERE_COLLAPSE_SUPERVOLCANISM = "BIOSPHERE_COLLAPSE_SUPERVOLCANISM";
    public static final String EVT_BIOSPHERE_COLLAPSE_STELLAR_EVENT = "BIOSPHERE_COLLAPSE_STELLAR_EVENT";
}
