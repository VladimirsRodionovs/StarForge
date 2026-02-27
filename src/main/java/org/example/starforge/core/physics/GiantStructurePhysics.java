package org.example.starforge.core.physics;

import org.example.starforge.core.model.AtmosphereType;
import org.example.starforge.core.model.PlanetModel;
import org.example.starforge.core.model.StarModel;

/**
 * Structure/appearance model for planets with significant H/He envelopes:
 * mini-neptunes, ice giants, gas giants.
 *
 * Computes radius (Earth radii), surface gravity (Earth g) at ~1 bar level,
 * and an estimated envelope-base pressure (bar).
 */
public final class GiantStructurePhysics {

    private GiantStructurePhysics() {}

    public static void computeForPlanet(StarModel star, PlanetModel p) {
        if (p == null) return;
        if (!(p.envelopeMassEarth > 0.0)) return;

        double core = Math.max(0.01, p.coreMassEarth > 0 ? p.coreMassEarth : Math.max(0.01, p.massEarth - p.envelopeMassEarth));
        double env = Math.max(0.0, p.envelopeMassEarth);
        double m = Math.max(0.05, core + env);

        // Equilibrium temperature proxy (K)
        double a = Math.max(0.02, p.orbitAroundStar.aAU());
        double teq = 278.0 * Math.pow(Math.max(1e-6, star.lumSolar()), 0.25) / Math.sqrt(a);

        // Core radius from existing solid M-R relation
        double rCore = PlanetRadii.solidRadiusEarth(core, p.fracIron, p.fracRock, p.fracIce);

        double envFrac = env / m;

        // "Inflation" for hot giants (only meaningful for true giants)
        double inflation = 1.0 + 0.12 * clamp((teq - 900.0) / 700.0, 0.0, 1.0);

        // ✅ default initialization prevents "might not have been initialized"
        double r = rCore;

        if (m < 30.0) {
            // --- Sub-neptune / puffy super-earth regime (conservative) ---
            double f = clamp(envFrac, 0.0, 0.60);

            // Very thin envelope: mildly puffy super-earth
            if (env < 0.03 || f < 0.02) {
                double puff = 1.0 + 0.20 * Math.sqrt(clamp(f / 0.02, 0.0, 1.0));
                r = rCore * puff;
                r = clamp(r, rCore, rCore * 1.60);
            } else {
                // Thresholded + conservative growth
                double fEff = Math.max(0.0, f - 0.01);

                double boost = 1.0 + 3.2 * Math.pow(clamp(fEff, 0.0, 0.60), 0.65);

                // modest temperature puffing only for very hot worlds
                double tPuff = 1.0 + 0.05 * clamp((teq - 800.0) / 900.0, 0.0, 1.0);

                r = rCore * boost * tPuff;

                // sanity bounds
                r = clamp(r, rCore * 1.10, 5.0);
            }
        } else {
            // --- Giant regime ---
            double mJ = m / Units.M_JUPITER_IN_M_EARTH;
            double rJ = 1.0 * Math.pow(mJ, 0.01); // near-flat
            r = 11.2 * rJ * inflation;

            // Blend down toward ice-giant sizes for lower envelopes (higher Z)
            if (envFrac < 0.60) {
                double rIce = 4.0 + 4.0 * clamp((m - 30.0) / 70.0, 0.0, 1.0);
                double t = clamp((envFrac - 0.25) / 0.35, 0.0, 1.0);
                r = lerp(rIce, r, t) * inflation;
            }

            r = clamp(r, 4.0, 16.0);
        }

        p.radiusEarth = r;
        p.massEarth = m;
        p.surfaceG = m / (r * r);

        // Atmosphere classification for UI
        p.atmosphereType = AtmosphereType.HHE;

        // "Surface pressure" at visible level is not meaningful for giants.
        p.pressureBar = 1.0;

        // Estimated envelope-base pressure at the condensed boundary (bar): P ≈ g * (M_env / 4πR²)
        double gSI = p.surfaceG * 9.80665;
        double menvKg = env * Units.M_EARTH_KG;
        double rMeters = r * Units.R_EARTH_M;
        double area = 4.0 * Math.PI * rMeters * rMeters;
        double sigma = (area > 0) ? (menvKg / area) : 0.0;
        double pBasePa = gSI * sigma;
        p.basePressureBar = (pBasePa / Units.BAR_PA);

        // Update UI type based on envelope fraction + mass now that structure is known.
        if (envFrac >= 0.60 && m >= 30.0) {
            p.type = "Gas Giant";
        } else if (envFrac >= 0.35 && m >= 10.0) {
            p.type = "Ice Giant";
        } else if (envFrac >= 0.10) {
            p.type = "Sub-Neptune";
        } else if (m < 2.0) {
            p.type = "Rocky";
        } else {
            p.type = "Super-Earth";
        }

    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
