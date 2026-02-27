package org.example.starforge.core.model;

import java.util.Map;

public record ZoneModel(
        ZoneType type,
        double aMinAU,
        double aMaxAU,
        double massEarth,
        Map<String, Double> composition, // e.g. ice/rock/metal/organics fractions
        Map<String, Double> population,  // e.g. count_1km, count_10km, count_100km
        String event,
        double eventAgeMyr
) {}
