package org.example.starforge.core.physics;

import org.example.starforge.core.model.Orbit;
import org.example.starforge.core.model.PlanetModel;
import org.example.starforge.core.model.StarModel;
import org.example.starforge.core.random.DeterministicRng;

import java.util.Comparator;
import java.util.List;

/**
 * Assigns orbital elements in a way that avoids obviously crossing / nearly-overlapping neighbor orbits.
 *
 * Why this exists:
 *  - PlanetModel.fromEmbryo() used to randomize eccentricity per-planet.
 *  - Even if semi-major axes are well separated, random e can create orbit overlap,
 *    producing "two planets almost on the same orbit" artifacts.
 *
 * This tool assigns modest eccentricities AFTER we know the full planet list, enforcing a peri/apo gap.
 * It does not attempt to be a full N-body stability solver; it's a fast, deterministic generator heuristic.
 */
public final class OrbitStabilityPhysics {

    private OrbitStabilityPhysics() {}

    /**
     * Mutates each planet's orbit by replacing the Orbit record.
     */
    public static void assignEccentricitiesAndAngles(StarModel star, List<PlanetModel> planets, DeterministicRng rng) {
        if (planets == null || planets.size() < 1) return;

        planets.sort(Comparator.comparingDouble(p -> p.orbitAroundStar.aAU()));

        // Base eccentricity envelope (outer planets can be a bit more excited).
        for (int i = 0; i < planets.size(); i++) {
            PlanetModel p = planets.get(i);
            double a = Math.max(1e-6, p.orbitAroundStar.aAU());
            double snow = Math.max(0.05, star != null ? star.snowLineAU() : 2.7);
            double cold01 = clamp((a / snow - 1.0) / 3.0, 0.0, 1.0);

            // Inner systems: keep e small to reduce overlap artifacts.
            double eBase = lerp(0.03, 0.12, cold01);
            double e = rng.range(0.0, eBase);

            // randomize angles here (they are purely cosmetic for now)
            double iDeg = rng.range(0.0, lerp(2.0, 4.0, cold01));
            double omega = rng.range(0.0, 360.0);
            double argPeri = rng.range(0.0, 360.0);
            double nu = rng.range(0.0, 360.0);

            p.orbitAroundStar = new Orbit(a, e, iDeg, omega, argPeri, nu);
        }

        // Now enforce a strict peri/apo gap by damping eccentricities until no neighbor overlaps.
        // Gap margin scales with semi-major axis: ~3% of a (inner) to ~2% (outer).
        int safety = 0;
        boolean changed = true;
        while (changed && safety++ < 20_000) {
            changed = false;
            planets.sort(Comparator.comparingDouble(p -> p.orbitAroundStar.aAU()));

            for (int i = 0; i < planets.size() - 1; i++) {
                PlanetModel p1 = planets.get(i);
                PlanetModel p2 = planets.get(i + 1);

                Orbit o1 = p1.orbitAroundStar;
                Orbit o2 = p2.orbitAroundStar;

                double a1 = Math.max(1e-6, o1.aAU());
                double a2 = Math.max(1e-6, o2.aAU());
                if (a2 <= a1) continue;

                double e1 = clamp(o1.e(), 0.0, 0.80);
                double e2 = clamp(o2.e(), 0.0, 0.80);

                double Q1 = a1 * (1.0 + e1); // apoapse of inner
                double q2 = a2 * (1.0 - e2); // periapse of outer

                double aMid = 0.5 * (a1 + a2);
                double margin = (aMid < 2.0) ? 0.030 * aMid : 0.020 * aMid;

                if (q2 - Q1 >= margin) continue; // ok

                // We have overlap/too small gap: damp e of the smaller mass planet first.
                double m1 = Math.max(0.0, p1.massEarth);
                double m2 = Math.max(0.0, p2.massEarth);
                PlanetModel damp = (m1 <= m2) ? p1 : p2;
                Orbit od = damp.orbitAroundStar;

                // Reduce e aggressively; if still overlapping, force both toward circular.
                double newE = od.e() * 0.50;
                if (newE < 0.005) newE = 0.0;

                damp.orbitAroundStar = new Orbit(
                        od.aAU(),
                        newE,
                        od.iDeg(),
                        od.omegaDeg(),
                        od.argPeriDeg(),
                        od.trueAnomalyDeg()
                );

                // If still bad after damping the smaller one, also damp the other.
                Orbit o1b = p1.orbitAroundStar;
                Orbit o2b = p2.orbitAroundStar;
                double Q1b = o1b.aAU() * (1.0 + clamp(o1b.e(), 0.0, 0.80));
                double q2b = o2b.aAU() * (1.0 - clamp(o2b.e(), 0.0, 0.80));
                if (q2b - Q1b < margin) {
                    PlanetModel other = (damp == p1) ? p2 : p1;
                    Orbit oo = other.orbitAroundStar;
                    double oe = oo.e() * 0.70;
                    if (oe < 0.005) oe = 0.0;
                    other.orbitAroundStar = new Orbit(
                            oo.aAU(),
                            oe,
                            oo.iDeg(),
                            oo.omegaDeg(),
                            oo.argPeriDeg(),
                            oo.trueAnomalyDeg()
                    );
                }

                changed = true;
            }
        }
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
