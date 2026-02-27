package org.example.starforge.core.model;

public record CometModel(
        String name,
        double massEarth,
        double radiusKm,
        Orbit orbitAroundStar,
        String sourceZone,   // e.g. "KUIPER" or "OORT"
        String event,
        double eventAgeMyr
) {}
