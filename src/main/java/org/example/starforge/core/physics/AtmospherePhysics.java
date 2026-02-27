package org.example.starforge.core.physics;

import org.example.starforge.core.model.*;
import org.example.starforge.core.random.DeterministicRng;

import java.util.LinkedHashMap;

/**
 * Atmosphere & climate (v1): N2, CO2, H2O.
 *
 * Goals:
 *  - pressure derived from atmospheric mass (not arbitrary)
 *  - greenhouse scales sensibly across Mars/Earth/Venus
 *  - allow water-vapor feedback via saturation/"evaporation" with an inventory cap
 *  - provide Tmin/Tmax and a coarse oasis classification
 */
public final class AtmospherePhysics {

    private AtmospherePhysics() {}

    // Physical constants
    private static final double EARTH_RADIUS_M = 6_371_000.0;
    private static final double EARTH_MASS_KG = 5.9722e24;
    private static final double G_EARTH_MPS2 = 9.80665;

    // Greenhouse calibration (v2): grey (single-layer) atmosphere via optical depth tau.
// Temperature scales with insolation: Tsurf = Teq * (1 + 3/4 * tau)^(1/4).
// This prevents unphysical "hot at 20 AU" outcomes that an additive ΔT model can produce.
private static final double TAU0 = 0.10; // baseline (clouds/other gases not modeled explicitly)

// CO2 optical depth (piecewise: weak below 1 bar, strong above 1 bar to allow Venus-like states)
private static final double K_CO2_LOW = 1.2;    // sub-bar CO2
private static final double K_CO2_HIGH = 45.0;  // multi-bar CO2 (Venus-like)
private static final double CO2_SWITCH_BAR = 1.0;
private static final double P0_CO2 = 0.10;

// H2O optical depth (water-vapor feedback handled separately via saturation logic)
private static final double K_H2O = 1.0;
private static final double P0_H2O = 0.01;

// Pressure broadening / continuum (very mild)
private static final double K_BROAD = 0.30;
private static final double P0_BROAD = 1.0;

    // Heat redistribution as function of pressure
    private static final double K_REDIS = 1.2; // 1/bar

    public static void computeForPlanet(StarModel star, PlanetModel p, DeterministicRng rng) {
        if (star == null || p == null || p.orbitAroundStar == null) return;

        // 1) Determine tidal locking (star-facing) - simple v1 heuristic.
        p.tidallyLockedToStar = isLikelyTidallyLockedToStar(star, p);

        // 2) Build volatile inventories (Earth masses)
        // Water: use the existing inventory proxy (mass * waterMassFrac)
        p.invH2OEarth = Math.max(0.0, p.massEarth * clamp01(p.waterMassFrac));

        // N2/CO2: small inventory inside snowline; larger beyond. These are "total accessible" volatiles
        // for secondary atmosphere (mantle+surface), not just present-day atmosphere.
        double snow = Math.max(0.01, star.snowLineAU());
        double a = Math.max(1e-6, p.orbitAroundStar.aAU());
        double iceProxy = clamp01((a - 0.85 * snow) / (0.30 * snow)); // 0..1-ish
        iceProxy = clamp01(iceProxy);

        // Inventory scales weakly with mass and volatile richness.
        // IMPORTANT: keep baseline secondary-atmosphere inventories modest.
        // In early v1 this was too high, producing multi-bar CO2 almost everywhere,
        // which in turn triggers runaway steam for temperate worlds. Venus-like
        // CO2 should be rare and generally event-driven.
        double n2Frac = lerp(5e-6, 2.0e-5, iceProxy);   // Earth mass fraction
        double co2Frac = lerp(1.0e-5, 6.0e-5, iceProxy);
        // Add mild stochasticity but keep deterministic per RNG.
        n2Frac *= rng.logNormal(1.0, 0.25);
        co2Frac *= rng.logNormal(1.0, 0.35);
        p.invN2Earth = Math.max(0.0, p.massEarth * n2Frac);
        p.invCO2Earth = Math.max(0.0, p.massEarth * co2Frac);

        // 3) Convert part of inventories to atmospheric masses (secondary outgassing)
        double retention = retentionFactor(star, p);

        // Outgassing fraction (accessible inventory -> atmosphere).
        // Tuned so that Earth-mass worlds in/near the HZ typically land in ~0.8–1.5 bar
        // (mostly N2), while still allowing thin atmospheres for low-mass or poorly-retaining
        // bodies and avoiding Venus-like outcomes unless conditions/events push them there.
        double outgasN2 = clamp(
                0.060 + 0.025 * Math.log10(1.0 + Math.max(0.0, p.massEarth)),
                0.04,
                0.16
        );
        double outgasCO2 = clamp(
                0.050 + 0.020 * Math.log10(1.0 + Math.max(0.0, p.massEarth)),
                0.03,
                0.12
        );


        // HZ retention boost: if the planet is in the optimistic HZ and can retain volatiles,
        // bias toward a more Earth-like (N2-dominated) secondary atmosphere.
        boolean inHZ = (a >= star.hzInnerAU_opt()) && (a <= star.hzOuterAU_opt());
        // For UI and downstream biosphere heuristics. This is an orbit-based flag (not a climate guarantee).
        p.habitableRegionPresent = inHZ;
        if (inHZ && p.massEarth >= 0.50 && retention >= 0.60) {
            // Small bias toward Earth-like (N2-dominated) atmospheres in the HZ.
            // Keep it mild since the base curve is already calibrated to the target pressures.
            outgasN2 = clamp(outgasN2 * 1.15, 0.04, 0.18);
            outgasCO2 = clamp(outgasCO2 * 0.90, 0.02, 0.12);
        }
        p.atmN2Earth = p.invN2Earth * outgasN2 * retention;
        p.atmCO2Earth = p.invCO2Earth * outgasCO2 * retention;
        p.atmH2OEarth = 0.0; // will be iterated from evaporation/saturation
        p.atmO2Earth = 0.0;  // may be set later by BiospherePhysics

        // 4) Water vapor (H2O) from saturation ("evaporation") with an inventory cap.
        // IMPORTANT: we avoid runaway feedback by computing a "dry" temperature first (no H2O),
        // then adding water vapor as a secondary component. Runaway steam is only enabled if dry
        // climate is already hot enough.
        // Climate pass with optional CO2 thermostat (only draws down CO2 if water is possible).
        for (int pass = 0; pass < 2; pass++) {
            // Base (surface) albedo from ice fraction; we then optionally add a cloud term
            // driven by water vapor. This is iterated a few times because clouds affect Teq.
            final double baseAlbedo = defaultAlbedo(p);
            double albedoEff = baseAlbedo;

            double teq = 0.0;
            // Cloud/albedo fixed-point iteration
            for (int cloudIt = 0; cloudIt < 3; cloudIt++) {
                teq = equilibriumTemperatureK(star, p, albedoEff);
                p.teqK = teq;

        // Base partial pressures (fixed by outgassed N2/CO2; O2 may be added later by biosphere)
        double pN2 = massToPressureBar(p, p.atmN2Earth);
        double pCO2 = massToPressureBar(p, p.atmCO2Earth);
        // Dry greenhouse (no water vapor).
        // NOTE: CO2 can condense on cold worlds, strongly limiting its effective greenhouse.
        // We therefore cap atmospheric CO2 to its vapor pressure as a function of temperature.
        // Do a few quick fixed-point iterations.
        double pN2Eff = pN2;
        double pO2 = massToPressureBar(p, p.atmO2Earth);
        double pO2Eff = pO2;
        double pCO2Eff = pCO2;
        double tDry = teq;
        double pSurfDry = Math.max(0.0, pN2Eff + pO2Eff + pCO2Eff);
        for (int it = 0; it < 8; it++) {
            pSurfDry = Math.max(0.0, pN2Eff + pO2Eff + pCO2Eff);
            double tauDry = opticalDepthTau(pCO2Eff, 0.0, pSurfDry);
            tDry = surfaceTemperatureK(teq, tauDry);

            // Condensation caps: at very low temperatures, N2/CO2 largely freeze out
            // and the remaining gas-phase pressure is limited by vapor pressure.
            pCO2Eff = Math.min(pCO2, co2VaporPressureBar(tDry));
            pN2Eff = Math.min(pN2, n2VaporPressureBar(tDry));
            pO2Eff = Math.min(pO2, o2VaporPressureBar(tDry));
        }

        // Reflect any caps back into atmospheric masses (the remainder is condensed on the surface).
        if (pN2 > 0.0) {
            p.atmN2Earth = pressureToMassEarth(p, pN2Eff);
            pN2 = pN2Eff;
        }
        if (pCO2 > 0.0) {
            p.atmCO2Earth = pressureToMassEarth(p, pCO2Eff);
            pCO2 = pCO2Eff;
        }
        if (pO2 > 0.0) {
            p.atmO2Earth = pressureToMassEarth(p, pO2Eff);
            pO2 = pO2Eff;
        }

        // Maximum possible H2O pressure if all inventory were steam
        double pCapAllWater = Math.max(0.0, massToPressureBar(p, p.invH2OEarth));
        p.h2oInventoryCapBar = pCapAllWater;

        // Runaway gate: only allow very large steam atmospheres if already quite hot without H2O,
        // AND the insolation is high. This prevents "cold-but-CO2-rich" worlds from becoming
        // steam planets in our simplified model.
        boolean runaway = (tDry >= 340.0) && (teq >= 300.0); // v1 default
        p.h2oRunaway = runaway;

        double pH2O;
        double psatDebug;
        boolean iceRegimeDebug;

        if (!runaway) {
            // Temperate/cold: water vapor limited by saturation at DRY temperature (no water feedback loop).
            // This avoids runaway self-amplification in our simplified model.
            double pSatDry = saturationVaporPressureBar(tDry);
            double pSoftCap = 0.10; // bar: very humid at most, unless runaway is triggered
            pH2O = clamp(pSatDry, 0.0, Math.min(pCapAllWater, pSoftCap));

            psatDebug = pSatDry;
            iceRegimeDebug = (tDry < 273.15);
        } else {
            // Hot: allow steam buildup up to critical water pressure cap (and inventory).
            double pRunCap = Math.min(pCapAllWater, 220.0); // ~critical pressure of water
            double pSatDry = saturationVaporPressureBar(tDry);
            pH2O = clamp(pSatDry, 0.0, pRunCap);

            psatDebug = pSatDry;
            iceRegimeDebug = (tDry < 273.15);

            for (int iter = 0; iter < 6; iter++) {
                double pSurf = Math.max(0.0, pSurfDry + pH2O);
                double tau = opticalDepthTau(pCO2, pH2O, pSurf);
                double tMean = surfaceTemperatureK(teq, tau);

                double pSat = saturationVaporPressureBar(tMean);
                double target = clamp(pSat, 0.0, pRunCap);

                // Relaxation to avoid oscillations
                pH2O = 0.60 * pH2O + 0.40 * target;

                psatDebug = pSat;
                iceRegimeDebug = (tMean < 273.15);
            }
        }

        // Debug/telemetry for saturation logic
        // Store H2O saturation debug (for SystemText)
        p.h2oRunaway = runaway;
        p.h2oPsatBar = Math.max(0.0, psatDebug);
        p.h2oUsedBar = Math.max(0.0, pH2O);
        p.h2oIceRegime = iceRegimeDebug;

        // Apply final partition + pressures

        p.atmH2OEarth = pressureToMassEarth(p, pH2O);
        p.pN2Bar = massToPressureBar(p, p.atmN2Earth);
        p.pCO2Bar = massToPressureBar(p, p.atmCO2Earth);
        p.pH2OBar = massToPressureBar(p, p.atmH2OEarth);
        p.pO2Bar = massToPressureBar(p, p.atmO2Earth);
        p.pressureBar = p.pN2Bar + p.pO2Bar + p.pCO2Bar + p.pH2OBar;

        double tauFinal = opticalDepthTau(p.pCO2Bar, p.pH2OBar, p.pressureBar);
        p.tMeanK = surfaceTemperatureK(p.teqK, tauFinal);
        p.greenhouseDeltaK = p.tMeanK - p.teqK;

        // 5) Heat redistribution -> Tmin/Tmax
        p.heatRedistribution = 1.0 - Math.exp(-K_REDIS * Math.max(0.0, p.pressureBar));
        double amp = temperatureAmplitude(p);
        p.tMaxK = p.tMeanK + amp;
        p.tMinK = Math.max(1.0, p.tMeanK - amp);

        // 6) Oasis classification
        p.oasisPotential = classifyOasis(p);

        // CO2 thermostat: if liquid water is possible anywhere, long-term weathering draws down CO2.
        if (pass == 0 && p.oasisPotential != OasisPotential.NONE) {
            double targetCO2 = co2ThermostatTargetBar(star, p);
            if (p.pCO2Bar > targetCO2) {
                p.atmCO2Earth = pressureToMassEarth(p, targetCO2);
                // restart climate with reduced CO2
                continue;
            }
        }

        p.atmosphereType = classifyAtmosphere(p);

        // 7) Composition map (partial pressures)
        p.atmosphere = new LinkedHashMap<>();
        if (p.pN2Bar > 0) p.atmosphere.put("N2", p.pN2Bar);
        if (p.pO2Bar > 0) p.atmosphere.put("O2", p.pO2Bar);
        if (p.pCO2Bar > 0) p.atmosphere.put("CO2", p.pCO2Bar);
        if (p.pH2OBar > 0) p.atmosphere.put("H2O", p.pH2OBar);

            // Update cloud albedo estimate from current water vapor and temperature, then iterate.
            double newAlbedo = cloudAdjustedAlbedo(baseAlbedo, p.pH2OBar, p.tMeanK, p.h2oRunaway);
            if (Math.abs(newAlbedo - albedoEff) < 0.01) {
                albedoEff = newAlbedo;
                break;
            }
            albedoEff = newAlbedo;
            // continue cloudIt loop
            }

            break;
        }
    }

    // -------------------- climate helpers --------------------

    /**
     * Equilibrium temperature (K) from insolation, with a simple albedo.
     * Uses the standard 278.5 K scaling for Earth at 1 AU, A=0.3.
     */
    public static double equilibriumTemperatureK(StarModel star, PlanetModel p, double albedo) {
        double L = Math.max(1e-9, star.lumSolar());
        double a = Math.max(1e-6, p.orbitAroundStar.aAU());
        // 278.5 K corresponds roughly to Earth with albedo ~0.3.
        double base = 278.5 * Math.pow(L, 0.25) / Math.sqrt(a);
        double alb = clamp(albedo, 0.05, 0.85);
        // Adjust relative to (1-0.3)=0.7
        double fac = Math.pow((1.0 - alb) / 0.70, 0.25);
        return base * fac;
    }

    
    /**
     * Grey-atmosphere optical depth proxy used by the current climate model.
     * Exposed so other modules (e.g. escape) can recompute climate after mutating atmospheric masses.
     */
    public static double opticalDepthTau(double pCO2, double pH2O, double pSurf) {
        double tau = TAU0;

        // CO2: weak below 1 bar, strong above 1 bar.
        double p = Math.max(0.0, pCO2);
        if (p <= CO2_SWITCH_BAR) {
            tau += K_CO2_LOW * Math.log1p(p / P0_CO2);
        } else {
            double lowPart = K_CO2_LOW * Math.log1p(CO2_SWITCH_BAR / P0_CO2);
            double highPart = K_CO2_HIGH * Math.log1p((p - CO2_SWITCH_BAR) / P0_CO2);
            tau += lowPart + highPart;
        }

        // Water vapor + broadening
        tau += K_H2O * Math.log1p(Math.max(0.0, pH2O) / P0_H2O);
        tau += K_BROAD * Math.log1p(Math.max(0.0, pSurf) / P0_BROAD);

        return Math.max(0.0, tau);
    }

    /** Grey-atmosphere surface temperature from Teq and optical depth proxy. */
    public static double surfaceTemperatureK(double teqK, double tau) {
        double teq = Math.max(1e-6, teqK);
        double t = teq * Math.pow(1.0 + 0.75 * Math.max(0.0, tau), 0.25);
        return Math.max(1.0, t);
    }

    /**
     * Saturation vapor pressure of water (bar) as a function of temperature.
     * Uses Murphy & Koop (2005) style fits (ice vs liquid), returned in bar.
     */
    public static double saturationVaporPressureBar(double tK) {
        if (!Double.isFinite(tK)) return 0.0;
        if (tK <= 1.0) return 0.0;

        // Cap at critical point (647 K) ~ 220 bar.
        if (tK >= 647.096) return 220.0;

        final double lnP;
        if (tK < 273.15) {
            // Ice (Murphy & Koop 2005)
            // ln(P/Pa) = 9.550426 - 5723.265/T + 3.53068 ln(T) - 0.00728332 T
            lnP = 9.550426 - 5723.265 / tK + 3.53068 * Math.log(tK) - 0.00728332 * tK;
        } else {
            // Liquid water (Murphy & Koop 2005)
            // ln(P/Pa) = 54.842763 - 6763.22/T - 4.210 ln(T) + 0.000367 T
            //           + tanh(0.0415 (T-218.8)) * (53.878 - 1331.22/T - 9.44523 ln(T) + 0.014025 T)
            double term1 = 54.842763 - 6763.22 / tK - 4.210 * Math.log(tK) + 0.000367 * tK;
            double term2 = 53.878 - 1331.22 / tK - 9.44523 * Math.log(tK) + 0.014025 * tK;
            lnP = term1 + Math.tanh(0.0415 * (tK - 218.8)) * term2;
        }

        double pPa = Math.exp(lnP);
        if (!Double.isFinite(pPa) || pPa < 0) return 0.0;
        return pPa / 1.0e5;
    }

    /**
     * Boiling point (K) of water at a given surface pressure (bar),
     * approximated by inverting the saturation vapor pressure curve.
     *
     * We cap to the critical point (647 K) for very high pressures.
     */
    private static double boilingPointK(double pBar) {
        double P = Math.max(0.0, pBar);
        // Below triple point, liquid water is not stable in this simplified model.
        if (P < 0.0062) return 273.15;
        if (P >= 220.0) return 647.096;

        // Binary search in [273K, 647K]
        double lo = 273.15;
        double hi = 647.096;
        for (int i = 0; i < 40; i++) {
            double mid = 0.5 * (lo + hi);
            double ps = saturationVaporPressureBar(mid);
            if (ps > P) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return 0.5 * (lo + hi);
    }


    /**
     * Rough vapor pressure of CO2 (bar) as a function of temperature (K).
     * Simple Clausius-Clapeyron fit around 195..217K; good enough for condensation gating.
     */
    private static double co2VaporPressureBar(double tK) {
        if (!Double.isFinite(tK) || tK <= 1.0) return 0.0;
        // Fit using points:
        //  - T=194.7K, P=1 bar
        //  - T=216.6K, P=5.1 bar (CO2 triple point)
        // ln(P) = A - B/T
        double A = 16.16;
        double B = 3146.0;
        double lnP = A - B / tK;
        double p = Math.exp(lnP);
        if (!Double.isFinite(p) || p < 0.0) return 0.0;
        // Above triple point this will underpredict, but we only use it as a cold-world cap.
        return Math.min(p, 100.0);
    }

    /**
     * Rough vapor pressure of N2 (bar) as a function of temperature (K).
     *
     * We use a simple Clausius–Clapeyron form ln(P) = A - B/T fitted to:
     *  - T=77.36 K, P≈1.013 bar (normal boiling point)
     *  - T=63.15 K, P≈0.125 bar (triple point)
     *
     * This is only used as a *cold-world* cap so we also clamp at high T.
     */
    private static double n2VaporPressureBar(double tK) {
        if (!Double.isFinite(tK) || tK <= 1.0) return 0.0;

        // Above N2 critical temperature (~126 K), treat as non-condensing in this simplified cap.
        if (tK >= 126.2) return 50.0;

        // ln(P/bar) = A - B/T
        final double A = 9.311466048114111;
        final double B = 719.3358142954877;
        double lnP = A - B / tK;
        double p = Math.exp(lnP);
        if (!Double.isFinite(p) || p < 0.0) return 0.0;
        return clamp(p, 0.0, 50.0);
    }

    /**
     * Very rough oxygen vapor pressure cap (bar) used only to eliminate
     * nonsensical"thick O2 atmospheres" on 10..50K worlds in our simplified model.
     */
    private static double o2VaporPressureBar(double tK) {
        if (!Double.isFinite(tK) || tK <= 1.0) return 0.0;

        // Below ~60K oxygen is essentially solid in this simplified model.
        if (tK < 60.0) return 0.001;

        // Two-point CC fit: ~1 bar at 90K, ~0.01 bar at 54K.
        // ln(P) = A - B/T
        double A = 6.907; // ~= ln(1000)
        double B = 621.7;
        double lnP = A - B / tK;
        double p = Math.exp(lnP);
        if (!Double.isFinite(p) || p < 0.0) return 0.0;
        return Math.min(p, 50.0); // cap near-ish critical pressures
    }

    private static double defaultAlbedo(PlanetModel p) {
        // Simple albedo: higher for ice-rich.
        double ice = clamp01(p.fracIce);
        double base = lerp(0.28, 0.55, ice);
        return base;
    }

    /**
     * Very simple cloud albedo feedback: more H2O vapor -> more clouds -> higher albedo.
     * This is intentionally conservative and bounded to avoid washing out temperature structure.
     */
    private static double cloudAdjustedAlbedo(double baseAlbedo, double pH2OBar, double tMeanK, boolean runaway) {
        double alb = clamp(baseAlbedo, 0.05, 0.85);
        double p = Math.max(0.0, pH2OBar);

        // No water vapor => no cloud adjustment
        if (p <= 1e-6) return alb;

        // Strength rises roughly logarithmically with humidity, saturates.
        // 0.01 bar -> small; 0.1 bar -> noticeable; >1 bar -> near max.
        double strength = 0.10 * Math.log1p(p / 0.01);

        // Clouds are less reflective in very hot, runaway steam states (absorbing greenhouse dominates).
        if (runaway || tMeanK > 340.0) strength *= 0.35;

        // Bound the cloud contribution to +0.20 albedo points.
        double delta = clamp(strength, 0.0, 0.20);
        return clamp(alb + delta, 0.05, 0.90);
    }

    private static double temperatureAmplitude(PlanetModel p) {
        // Larger amplitude for thin atmospheres and tidal lock.
        double H = clamp01(p.heatRedistribution);
        double ecc = clamp01(p.orbitAroundStar.e());

        double mean = Math.max(1.0, p.tMeanK);
        if (p.tidallyLockedToStar) {
            double amp = mean * (0.45 * (1.0 - H) + 0.05);
            amp += mean * 0.10 * ecc;
            return clamp(amp, 0.0, 0.80 * mean);
        }

        double amp = mean * (0.25 * (1.0 - H));
        amp += mean * 0.08 * ecc;
        return clamp(amp, 0.0, 0.60 * mean);
    }

    private static OasisPotential classifyOasis(PlanetModel p) {
        // Pressure-aware liquid-water window.
        // We model only a coarse stability check:
        //  - requires sufficient surface pressure (above water triple point)
        //  - and some part of the temperature range intersects [273K, Tboil(P)].

        double P = Math.max(0.0, p.pressureBar);
        // Below water triple point (~0.0062 bar), liquid water is not stable.
        if (P < 0.0062) return OasisPotential.NONE;

        // If there is no accessible water inventory, don't claim an oasis just from humidity.
        if (p.invH2OEarth <= 0.0) return OasisPotential.NONE;

        // Hard stop for runaway steam worlds
        if (p.h2oRunaway) return OasisPotential.NONE;

        double tFreeze = 273.15;
        double tBoil = boilingPointK(P);

        // Liquid window can collapse if pressure is too low
        if (tBoil <= tFreeze + 0.5) return OasisPotential.NONE;

        double lo = Math.max(tFreeze, Math.min(p.tMinK, p.tMaxK));
        double hi = Math.min(tBoil, Math.max(p.tMinK, p.tMaxK));
        boolean intersects = hi >= lo;
        if (!intersects) return OasisPotential.NONE;

        // If mean is inside the liquid window and the swing is not extreme -> global oceans possible
        if (P >= 0.10 && p.tMeanK >= tFreeze && p.tMeanK <= tBoil && (p.tMaxK - p.tMinK) <= 80.0) {
            return OasisPotential.GLOBAL_WATER_POSSIBLE;
        }

        // Otherwise: localized oases (allowed for thin atmospheres too, but require some pressure)
        if (P < 0.02) return OasisPotential.NONE;

        if (p.tidallyLockedToStar) return OasisPotential.TERMINATOR_OASIS;
        // If mean is below freezing but max crosses into liquid -> polar melt
        if (p.tMeanK < tFreeze) return OasisPotential.POLAR_OASIS;
        return OasisPotential.EQUATORIAL_OASIS;
    }

    private static AtmosphereType classifyAtmosphere(PlanetModel p) {
        double P = Math.max(0.0, p.pressureBar);
        if (P <= 0.001) return AtmosphereType.NONE;
        if (P < 0.10) return AtmosphereType.THIN;
        if (P < 0.50) return AtmosphereType.SUB_EARTH;

        // Steam takes precedence if water vapor dominates a thick atmosphere
        if (p.pH2OBar >= 0.50 * P && P >= 1.0) return AtmosphereType.STEAM;
        if (p.pCO2Bar >= 0.50 * P && P >= 1.0) return AtmosphereType.CO2_THICK;
        return AtmosphereType.N2_DOMINATED;
    }

    /**
     * Recompute pressures, climate, oasis and atmosphere classification from the current atmospheric
     * masses (atmN2Earth/atmCO2Earth/atmH2OEarth). This is useful after external modules (e.g.
     * atmospheric escape) modify atmospheric masses.
     */
    public static void recomputeDerivedFromAtmosphere(StarModel star, PlanetModel p) {
        if (star == null || p == null || p.orbitAroundStar == null) return;

        // Keep the existing tidal-lock decision (it affects Tmin/Tmax amplitude).

        // Equilibrium temperature (base albedo only; cloud iteration is handled in the full compute).
        p.teqK = equilibriumTemperatureK(star, p, defaultAlbedo(p));

        // Start from current partial pressures
        double pN2 = massToPressureBar(p, p.atmN2Earth);
        double pCO2 = massToPressureBar(p, p.atmCO2Earth);
        double pH2O = massToPressureBar(p, p.atmH2OEarth);
        double pO2 = massToPressureBar(p, p.atmO2Earth);

        // Cold-world condensation caps can change the effective atmospheric mass.
        // We apply a small fixed-point loop to keep the gas-phase pressure consistent
        // with the resulting temperature.
        double tMean = p.teqK;
        for (int it = 0; it < 6; it++) {
            double pSurf = Math.max(0.0, pN2 + pO2 + pCO2 + pH2O);
            double tau = opticalDepthTau(pCO2, pH2O, pSurf);
            tMean = surfaceTemperatureK(p.teqK, tau);

            // Cap CO2, N2, O2 by vapor pressure on cold worlds
            pCO2 = Math.min(pCO2, co2VaporPressureBar(tMean));
            pN2 = Math.min(pN2, n2VaporPressureBar(tMean));
            pO2 = Math.min(pO2, o2VaporPressureBar(tMean));

            // Also cap water vapor by saturation at the current mean temperature
            // and by the planet's inventory cap if available.
            double pCap = (p.h2oInventoryCapBar > 0.0) ? p.h2oInventoryCapBar : Double.POSITIVE_INFINITY;
            pH2O = Math.min(pH2O, Math.min(pCap, saturationVaporPressureBar(tMean)));
        }

        // Reflect updated effective pressures back into stored masses and fields
        p.atmN2Earth = pressureToMassEarth(p, pN2);
        p.atmCO2Earth = pressureToMassEarth(p, pCO2);
        p.atmH2OEarth = pressureToMassEarth(p, pH2O);
        p.atmO2Earth = pressureToMassEarth(p, pO2);

        p.pN2Bar = pN2;
        p.pCO2Bar = pCO2;
        p.pH2OBar = pH2O;
        p.pO2Bar = pO2;
        p.pressureBar = Math.max(0.0, pN2 + pO2 + pCO2 + pH2O);

        // Climate (final)
        double tauFinal = opticalDepthTau(p.pCO2Bar, p.pH2OBar, p.pressureBar);
        p.tMeanK = surfaceTemperatureK(p.teqK, tauFinal);
        p.greenhouseDeltaK = p.tMeanK - p.teqK;

        // Heat redistribution -> Tmin/Tmax
        p.heatRedistribution = 1.0 - Math.exp(-K_REDIS * Math.max(0.0, p.pressureBar));
        double amp = temperatureAmplitude(p);
        p.tMaxK = p.tMeanK + amp;
        p.tMinK = Math.max(1.0, p.tMeanK - amp);

        // Keep saturation debug consistent enough for printing
        p.h2oUsedBar = Math.max(0.0, p.pH2OBar);

        // Classifiers
        p.oasisPotential = classifyOasis(p);
        p.atmosphereType = classifyAtmosphere(p);

        // Composition map (partial pressures)
        p.atmosphere = new LinkedHashMap<>();
        if (p.pN2Bar > 0) p.atmosphere.put("N2", p.pN2Bar);
        if (p.pO2Bar > 0) p.atmosphere.put("O2", p.pO2Bar);
        if (p.pCO2Bar > 0) p.atmosphere.put("CO2", p.pCO2Bar);
        if (p.pH2OBar > 0) p.atmosphere.put("H2O", p.pH2OBar);
    }

    
    /**
     * CO2 thermostat target (bar) when liquid water is possible.
     * We approximate that lower stellar flux requires more CO2 to stay warm, but cap the effect.
     *
     * This only ever DRAWS DOWN CO2 (it never adds CO2).
     */
    private static double co2ThermostatTargetBar(StarModel star, PlanetModel p) {
        double L = Math.max(1e-9, star.lumSolar());
        double a = Math.max(1e-6, p.orbitAroundStar.aAU());
        double flux = L / (a * a); // relative to Earth at 1 AU around 1 Lsun

        // Scale relative to Earth's ~400 ppm = 0.0004 bar.
        // Colder (lower flux) -> higher allowed CO2.
        double target = 0.0004 * Math.pow(1.0 / Math.max(0.05, flux), 3.0);

        // Keep within reasonable bounds for rocky worlds with oceans.
        return clamp(target, 1.0e-4, 5.0);
    }

// -------------------- tidal locking heuristic --------------------

    public static boolean isLikelyTidallyLockedToStar(StarModel star, PlanetModel p) {
        double a = Math.max(1e-6, p.orbitAroundStar.aAU());
        double M = Math.max(0.05, star.massSolar());
        // Orbital period in days (Kepler's third law in solar units)
        double periodYears = Math.sqrt((a * a * a) / M);
        double periodDays = periodYears * 365.25;

        // More massive planets lock a bit faster; but keep it simple.
        double mass = Math.max(0.01, p.massEarth);
        double threshold = 20.0;
        if (mass >= 2.0) threshold = 15.0;
        if (mass >= 5.0) threshold = 12.0;

        // Also require proximity (avoid locking false positives for long orbits around low-mass stars).
        return (periodDays <= threshold) && (a <= 0.35);
    }

    // -------------------- retention / conversions --------------------

    /**
     * A coarse 0..1 retention factor based on escape velocity, insolation, and stellar activity.
     * This is intentionally simple for v1.
     */
    public static double retentionFactor(StarModel star, PlanetModel p) {
        double teq = equilibriumTemperatureK(star, p, defaultAlbedo(p));

        // Escape velocity in km/s relative to Earth.
        double vEsc = 11.186 * Math.sqrt(Math.max(1e-9, p.massEarth / Math.max(1e-9, p.radiusEarth)));

        double act = 1.0;
        String level = star.activityLevel();
        if ("HIGH".equalsIgnoreCase(level)) act = 0.60;
        else if ("MEDIUM".equalsIgnoreCase(level)) act = 0.80;

        double f = (vEsc / 10.0) * (320.0 / Math.max(150.0, teq)) * act;
        return clamp01(f);
    }

    /** Convert an atmospheric mass (Earth masses) to surface pressure (bar) on the given planet. */
    public static double massToPressureBar(PlanetModel p, double atmMassEarth) {
        if (atmMassEarth <= 0) return 0.0;
        double rM = Math.max(1e-9, p.radiusEarth) * EARTH_RADIUS_M;
        double area = 4.0 * Math.PI * rM * rM;
        double g = Math.max(1e-9, p.surfaceG) * G_EARTH_MPS2;
        double mKg = atmMassEarth * EARTH_MASS_KG;
        double pPa = (mKg * g) / area;
        return pPa / 1.0e5;
    }

    /** Convert desired surface pressure (bar) to atmospheric mass (Earth masses) on the given planet. */
    public static double pressureToMassEarth(PlanetModel p, double pBar) {
        if (pBar <= 0) return 0.0;
        double rM = Math.max(1e-9, p.radiusEarth) * EARTH_RADIUS_M;
        double area = 4.0 * Math.PI * rM * rM;
        double g = Math.max(1e-9, p.surfaceG) * G_EARTH_MPS2;
        double pPa = pBar * 1.0e5;
        double mKg = (pPa * area) / g;
        return mKg / EARTH_MASS_KG;
    }

    // -------------------- small math helpers --------------------

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp01(double v) {
        return clamp(v, 0.0, 1.0);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * clamp01(t);
    }
}
