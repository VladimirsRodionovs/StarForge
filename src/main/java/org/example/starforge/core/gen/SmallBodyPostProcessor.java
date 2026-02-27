package org.example.starforge.core.gen;

import org.example.starforge.core.model.*;
import org.example.starforge.core.random.DeterministicRng;

import java.util.*;

/**
 * Post-process embryos to avoid turning every leftover outer embryo into a named planet.
 *
 * Goal (game-friendly realism):
 *  - keep the inner/terrestrial planets as planets,
 *  - in the outer system, keep only the few most massive bodies,
 *  - convert the remaining mass into belts/disks (ZoneModel).
 */
public final class SmallBodyPostProcessor {

    private SmallBodyPostProcessor() {}

    public record ProcessedEmbryos(List<EmbryoModel> survivors, List<ZoneModel> zones) {}

    public static ProcessedEmbryos process(
            StarModel star,
            DiskModel disk,
            List<EmbryoModel> embryos,
            DeterministicRng rng
    ) {
        if (embryos == null || embryos.isEmpty()) {
            return new ProcessedEmbryos(List.of(), List.of());
        }

        // Boundaries scaled by snow line.
        double snow = Math.max(0.01, star.snowLineAU());

        // IMPORTANT: for very low-lum stars snow line becomes tiny; clamp belts to sane AU.
        double aAstMin = Math.max(0.10, 0.65 * snow);
        double aAstMax = Math.max(0.20, 1.40 * snow);

        double aOuterMin = aAstMax;
        double aOuterMax = Math.max(aOuterMin + 0.2, disk.rSolidsOuterAU);

        // Thresholds (scaled by star mass so M dwarfs don't become "0 planets + disk")
        double keepMassAst = 0.25;

        double mStar = Math.max(0.05, star.massSolar());
        double keepMassOuter = clamp(0.28 * Math.max(0.25, mStar / 0.8), 0.08, 0.45);

        double minZoneMass = 0.02;

        // Randomness: sometimes keep an extra outer body for variety.
        int maxOuterKeep = rng.chance(0.25) ? 3 : (rng.chance(0.55) ? 2 : 1);

        List<EmbryoModel> inner = new ArrayList<>();
        List<EmbryoModel> astBand = new ArrayList<>();
        List<EmbryoModel> outer = new ArrayList<>();

        for (EmbryoModel e : embryos) {
            if (e.aAU < aAstMin) inner.add(e);
            else if (e.aAU < aAstMax) astBand.add(e);
            else outer.add(e);
        }

        // --- Decide survivors ---
        List<EmbryoModel> survivors = new ArrayList<>();
        survivors.addAll(inner);

        // Asteroid band: keep only unusually large bodies; convert the rest into ASTEROID_BELT.
        double asteroidMass = 0.0;
        for (EmbryoModel e : astBand) {
            if (e.massEarth >= keepMassAst) survivors.add(e);
            else asteroidMass += Math.max(0.0, e.massEarth);
        }

        // Outer solids disk: keep up to maxOuterKeep bodies if they are big enough; rest -> OUTER_DISK.
        outer.sort((a, b) -> Double.compare(b.massEarth, a.massEarth));

        double outerDiskMass = 0.0;
        int kept = 0;
        for (EmbryoModel e : outer) {
            boolean keep = ((e.envelopeMassEarth > 0.02) || (e.massEarth >= keepMassOuter)) && (kept < maxOuterKeep);
            if (keep) {
                survivors.add(e);
                kept++;
            } else {
                outerDiskMass += Math.max(0.0, e.massEarth);
            }
        }

        // --- Zones ---
        // IMPORTANT: do not output a single "fixed" belt that can overlap surviving planets.
        // We carve gaps around any survivors inside the belt range and split the belt into segments.
        List<ZoneModel> zones = new ArrayList<>();

        if (asteroidMass >= minZoneMass) {
            addSegmentedZone(
                    zones,
                    ZoneType.ASTEROID_BELT,
                    aAstMin,
                    aAstMax,
                    asteroidMass,
                    survivors,
                    star,
                    rng,
                    minZoneMass
            );
        }

        if (outerDiskMass >= minZoneMass) {
            // For OUTER_DISK we allow very wide zones; still, carve a small gap around any kept massive body
            // so the zone doesn't "sit on top" of a planet.
            addSegmentedZone(
                    zones,
                    ZoneType.OUTER_DISK,
                    aOuterMin,
                    aOuterMax,
                    outerDiskMass,
                    survivors,
                    star,
                    rng,
                    minZoneMass
            );
        }

        survivors.sort(Comparator.comparingDouble(e -> e.aAU));
        return new ProcessedEmbryos(survivors, zones);
    }

    /** Very lightweight composition proxy based on snow line. Values are fractions that sum ~1. */
    public static Map<String, Double> compositionFor(StarModel star, double aAU) {
        double snow = Math.max(0.01, star.snowLineAU());
        if (aAU < snow) {
            return Map.of(
                    "rock", 0.80,
                    "metal", 0.18,
                    "ice", 0.00,
                    "organics", 0.02
            );
        } else {
            return Map.of(
                    "rock", 0.45,
                    "metal", 0.10,
                    "ice", 0.40,
                    "organics", 0.05
            );
        }
    }

    /**
     * Extremely rough population proxy.
     * Counts scale with mass; randomness gives variety without storing huge lists of bodies.
     */
    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static Map<String, Double> populationFromMass(double massEarth, DeterministicRng rng) {
        double scale = Math.max(0.0, massEarth);
        double c1 = scale * rng.range(2.0e9, 7.0e9);   // ~1 km bodies
        double c10 = scale * rng.range(2.0e7, 8.0e7);  // ~10 km bodies
        double c100 = scale * rng.range(2.0e5, 8.0e5); // ~100 km bodies
        return Map.of(
                "count_1km", c1,
                "count_10km", c10,
                "count_100km", c100
        );
    }

    // -------------------- helpers: segmented belts/disks --------------------

    private static void addSegmentedZone(
            List<ZoneModel> out,
            ZoneType type,
            double aMin,
            double aMax,
            double totalMass,
            List<EmbryoModel> survivors,
            StarModel star,
            DeterministicRng rng,
            double minZoneMass
    ) {
        if (totalMass <= 0 || aMax <= aMin) return;

        // Build forbidden intervals around survivors that lie inside [aMin..aMax].
        List<double[]> gaps = new ArrayList<>();
        for (EmbryoModel e : survivors) {
            double a = e.aAU;
            if (a <= 0) continue;
            if (a < aMin || a > aMax) continue;

            double hill = org.example.starforge.core.physics.HillSphere.hillRadiusAU(
                    a,
                    Math.max(1e-9, e.massEarth),
                    Math.max(0.05, star.massSolar())
            );

            double g = Math.max(8.0 * hill, 0.020 * a);
            g = Math.max(g, 0.010);

            double l = Math.max(aMin, a - g);
            double r = Math.min(aMax, a + g);
            if (r > l) gaps.add(new double[]{l, r});
        }

        // Merge overlapping gaps
        gaps.sort(Comparator.comparingDouble(x -> x[0]));
        List<double[]> merged = new ArrayList<>();
        for (double[] g : gaps) {
            if (merged.isEmpty()) {
                merged.add(g);
            } else {
                double[] last = merged.get(merged.size() - 1);
                if (g[0] <= last[1]) last[1] = Math.max(last[1], g[1]);
                else merged.add(g);
            }
        }

        // Compute allowed segments = [aMin..aMax] minus merged gaps
        List<double[]> segs = new ArrayList<>();
        double cur = aMin;
        for (double[] g : merged) {
            if (g[0] > cur) segs.add(new double[]{cur, g[0]});
            cur = Math.max(cur, g[1]);
        }
        if (cur < aMax) segs.add(new double[]{cur, aMax});

        // Distribute mass proportional to segment width
        double totalWidth = 0.0;
        for (double[] s : segs) totalWidth += Math.max(0.0, s[1] - s[0]);
        if (totalWidth <= 0) return;

        for (double[] s : segs) {
            double w = Math.max(0.0, s[1] - s[0]);
            if (w <= 0) continue;
            double m = totalMass * (w / totalWidth);
            if (m < minZoneMass) continue;

            double mid = 0.5 * (s[0] + s[1]);
            out.add(new ZoneModel(
                    type,
                    s[0], s[1],
                    m,
                    compositionFor(star, mid),
                    populationFromMass(m, rng),
                    null,
                    0.0
            ));
        }
    }
}
