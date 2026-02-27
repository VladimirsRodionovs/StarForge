package org.example.starforge.core.model;

import java.util.Map;

public record MoonModel(
        String name,
        double massEarth,
        double radiusEarth,
        double surfaceG,
        Orbit orbitAroundPlanet,
        boolean tidallyLockedToPlanet,
        String tidalHeatingLevel,
        boolean habitableRegionPresent,
        double pressureBar,
        AtmosphereType atmosphereType,
        double envelopeMassEarth,
        String type,
        double basePressureBar,
        double fracIron,
        double fracRock,
        double fracIce,
        double waterMassFrac,
        double teqK,
        double greenhouseDeltaK,
        double tMeanK,
        double tMinK,
        double tMaxK,
        WaterCoverage waterCoverage,
        double waterGELkm,
        Map<String, Double> atmosphere,

        BiosphereSurfaceStatus biosphereSurface,
        BiosphereMicrobialStatus biosphereMicrobial,
        BiosphereSubsurfaceStatus biosphereSubsurface,

        BiosphereProvenance biosphereProvenance,

        boolean heavyHydrocarbons,
        boolean lightHydrocarbons,

        // NEW: event explanation
        String event,
        double eventAgeMyr
) {
}
