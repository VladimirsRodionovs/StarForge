// starforge-core/src/main/java/com/yourgame/starforge/core/model/Orbit.java
package org.example.starforge.core.model;

public record Orbit(
        double aAU,
        double e,
        double iDeg,
        double omegaDeg,   // longitude of ascending node Ω
        double argPeriDeg, // argument of periapsis ω
        double trueAnomalyDeg // ν0 at epoch
) {}
