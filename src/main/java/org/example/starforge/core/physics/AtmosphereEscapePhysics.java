package org.example.starforge.core.physics;

import org.example.starforge.core.model.PlanetModel;
import org.example.starforge.core.model.StarModel;

/**
 * Very simple atmospheric escape / stripping model (v1).
 *
 * Motivation: close-in planets under strong insolation + high stellar activity should often lose
 * a significant fraction of light secondary atmospheres, especially around active M dwarfs.
 *
 * This is NOT a full photochemistry/escape simulation. It's an energy-limited escape proxy:
 *  - compute an XUV flux from bolometric flux and activity level
 *  - compute energy-limited mass loss over an "active phase" timescale
 *  - remove mass from H2O, then N2, then CO2 (lighter species are more vulnerable)
 *  - clamp to available atmosphere
 */
public final class AtmosphereEscapePhysics {

    private AtmosphereEscapePhysics() {}

    private static final double G = 6.67430e-11;
    private static final double EARTH_MASS_KG = 5.9722e24;
    private static final double EARTH_RADIUS_M = 6_371_000.0;

    // Solar constant at 1 AU (W/m^2)
    private static final double SOLAR_CONSTANT = 1361.0;

    // Escape efficiency (energy-limited). Tuned conservatively.
    private static final double EPS = 0.10;

    /**
     * Apply atmospheric escape. Returns the total atmospheric mass lost in Earth masses.
     */
    public static double apply(StarModel star, PlanetModel p) {
        if (star == null || p == null || p.orbitAroundStar == null) return 0.0;

        double a = Math.max(1e-6, p.orbitAroundStar.aAU());
        double L = Math.max(1e-9, star.lumSolar());

        // Relative insolation S/S_earth
        double sRel = L / (a * a);

        // Activity-dependent onset threshold.
        // For low-activity stars, heavy secondary atmospheres are hard to strip near ~1 S⊕.
        // For very active stars (especially M dwarfs), XUV-driven escape can matter closer to HZ.
        String level = star.activityLevel();
        double onset;
        if ("HIGH".equalsIgnoreCase(level)) onset = 1.10;
        else if ("MEDIUM".equalsIgnoreCase(level)) onset = 1.50;
        else onset = 2.00;

        // If insolation is below onset, stripping is negligible in this v1 model.
        if (sRel < onset) return 0.0;

        // XUV fraction of bolometric flux, activity-dependent.
        // These are deliberately conservative (order-of-magnitude proxies).
        double fXuv;
        if ("HIGH".equalsIgnoreCase(level)) fXuv = 5e-4;
        else if ("MEDIUM".equalsIgnoreCase(level)) fXuv = 1e-4;
        else fXuv = 3e-5;

        // Stellar XUV flux at the planet (W/m^2)
        double fBol = SOLAR_CONSTANT * sRel;
        double fXuvAbs = fBol * fXuv;

        // Active-phase timescale (seconds)
        double tMyr;
        if ("HIGH".equalsIgnoreCase(level)) tMyr = 200.0;
        else if ("MEDIUM".equalsIgnoreCase(level)) tMyr = 120.0;
        else tMyr = 60.0;
        double tSec = tMyr * 1.0e6 * 365.25 * 24.0 * 3600.0;

        // Planet physical quantities
        double rp = Math.max(1e-9, p.radiusEarth) * EARTH_RADIUS_M;
        double mp = Math.max(1e-9, p.massEarth) * EARTH_MASS_KG;

        // Energy-limited mass loss rate (kg/s)
        // Mdot = eps * pi * Rp^3 * Fxuv / (G * Mp)
        double mdot = EPS * Math.PI * rp * rp * rp * fXuvAbs / (G * mp);
        if (!Double.isFinite(mdot) || mdot <= 0.0) return 0.0;

        double lostKg = mdot * tSec;
        if (!Double.isFinite(lostKg) || lostKg <= 0.0) return 0.0;

        // Extra stripping for extreme irradiation and low gravity (photoevaporation-like).
        double vEscKmS = 11.186 * Math.sqrt(Math.max(1e-9, p.massEarth / Math.max(1e-9, p.radiusEarth)));
        if (sRel > 200.0 && vEscKmS < 10.0) lostKg *= 5.0;
        else if (sRel > 80.0 && vEscKmS < 9.0) lostKg *= 2.0;

        // Convert to Earth masses
        double lostEarth = lostKg / EARTH_MASS_KG;

        // Only strip what's actually in the atmosphere.
        double totalAtm = Math.max(0.0, p.atmN2Earth) + Math.max(0.0, p.atmCO2Earth) + Math.max(0.0, p.atmH2OEarth);
        if (totalAtm <= 0.0) return 0.0;

        // Scale to zero at the onset threshold, ramping with insolation.
        // At ~onset we should have ~0 stripping; stronger close-in.
        double severity = clamp01((sRel - onset) / 40.0);
        lostEarth *= severity;

        double remainingLoss = Math.min(lostEarth, totalAtm);

        // Remove in order: H2O (light), N2, then CO2 (heavier)
        double dH2O = Math.min(Math.max(0.0, p.atmH2OEarth), remainingLoss);
        p.atmH2OEarth = Math.max(0.0, p.atmH2OEarth - dH2O);
        remainingLoss -= dH2O;

        // Heavy secondary gases are much harder to remove than H/He in reality.
        // Our energy-limited proxy tends to over-strip N2/CO2, so damp it unless irradiation is extreme.
        boolean heavyStrippingRegime = (sRel >= 10.0) || (p.teqK >= 500.0) || ("HIGH".equalsIgnoreCase(level));
        if (!heavyStrippingRegime && remainingLoss > 0.0) {
            double damp;
            if ("MEDIUM".equalsIgnoreCase(level)) damp = 0.20;
            else damp = 0.10; // LOW
            remainingLoss *= damp;
        }

        double dN2 = Math.min(Math.max(0.0, p.atmN2Earth), remainingLoss);
        p.atmN2Earth = Math.max(0.0, p.atmN2Earth - dN2);
        remainingLoss -= dN2;

        double dCO2 = Math.min(Math.max(0.0, p.atmCO2Earth), remainingLoss);
        p.atmCO2Earth = Math.max(0.0, p.atmCO2Earth - dCO2);
        remainingLoss -= dCO2;

        return dH2O + dN2 + dCO2;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

}
