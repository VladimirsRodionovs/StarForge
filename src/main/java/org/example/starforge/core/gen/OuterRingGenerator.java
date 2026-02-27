package org.example.starforge.core.gen;

import org.example.starforge.core.model.DiskModel;
import org.example.starforge.core.model.Orbit;
import org.example.starforge.core.model.PlanetModel;
import org.example.starforge.core.model.StarModel;
import org.example.starforge.core.model.StarRecord;
import org.example.starforge.core.random.DeterministicRng;
import org.example.starforge.core.physics.PlanetRadii;


import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 3rd-pass heuristic generator for a "far outer ring" of small rocky/icy bodies
 * + a simple asteroid belt record (string placeholder for now).
 *
 * Requirements:
 *  - Add 0..N outer bodies by spectral class + disk richness.
 *  - Orbits must be stable: not crossing, and not too close to existing planets or each other.
 *  - Bodies are rocky/icy, masses ~0.3..2.0 Mearth, skewed to smaller.
 *  - If N==0, always create an asteroid belt record.
 *
 * Notes:
 *  - "Dwarf/planetoid/planet" is just PlanetModel.type for UI.
 *  - This generator does not try to conserve leftover solids exactly; it's a content heuristic pass.
 */
public final class OuterRingGenerator {

    private OuterRingGenerator() {}

    public static final class Result {
        public final List<PlanetModel> planets;
        public final String beltRecord; // nullable

        public Result(List<PlanetModel> planets, String beltRecord) {
            this.planets = planets;
            this.beltRecord = beltRecord;
        }
    }

    public static Result generate(
            StarModel star,
            DiskModel disk,
            StarRecord rec,
            List<PlanetModel> existingPlanets,
            DeterministicRng rng
    ) {
        if (star == null || disk == null || existingPlanets == null || rng == null) {
            return new Result(List.of(), null);
        }

        char spClass = spectralClass(rec);

        // ---------- dynamic region boundaries ----------
        double snow = safe(star.snowLineAU(), 2.7);
        double aLast = maxA(existingPlanets);

        double aStart = Math.max(aLast * 1.35, snow * 2.0);

        // If there's a gas giant, push start further out so we don't pack right next to it.
        double aMaxGiant = maxAGasGiant(existingPlanets);
        if (aMaxGiant > 0) aStart = Math.max(aStart, aMaxGiant * 1.60);

        // Disk size to estimate outer system end / Oort onset (gameplay cap ~150–220 AU).
        double rSolidsOuter = safe(disk.rSolidsOuterAU, snow * 12.0);

        double aOort = rSolidsOuter * rng.range(2.5, 4.5);
        double cap = rng.range(150.0, 220.0) * Math.sqrt(Math.max(0.08, star.massSolar()));
        double aEnd = Math.min(aOort, cap);

        // Ensure we have room; otherwise just create a belt record.
        if (aEnd < aStart * 1.40) {
            String belt = makeBeltRecord(star, disk, rng, aStart, aEnd);
            return new Result(List.of(), belt);
        }

        // ---------- decide count ----------
        int[] base = baseCountRange(spClass);
        int minN = base[0];
        int maxN = base[1];

        // disk "richness" tweak (gentle)
        double solids = safe(disk.mSolidsEarth, 0.0);
        if (solids > 120.0) maxN += 2;
        else if (solids > 60.0) maxN += 1;
        else if (solids < 8.0) maxN = Math.max(minN, maxN - 1);

        // reduce a bit if already crowded
        if (existingPlanets.size() >= 8) maxN = Math.max(minN, maxN - 1);

        int n = (maxN <= 0) ? 0 : rng.rangeInt(minN, Math.max(minN, maxN));
        n = Math.max(0, n);

        // ---------- generate bodies ----------
        List<PlanetModel> out = new ArrayList<>();
        List<PlanetModel> allForChecks = new ArrayList<>(existingPlanets);

        int attemptsTotal = 0;
        int maxAttemptsTotal = 1500;

        while (out.size() < n && attemptsTotal++ < maxAttemptsTotal) {
            double a = sampleLogUniform(aStart, aEnd, rng);

            // Low eccentricity for stability in the far ring
            double e = rng.range(0.0, 0.06);
            double inc = rng.range(0.0, 2.0);
            double Omega = rng.range(0.0, 360.0);
            double argPeri = rng.range(0.0, 360.0);
            double nu = rng.range(0.0, 360.0);

            if (!isOrbitAcceptable(a, e, allForChecks)) continue;

            double m = sampleOuterMassEarth(rng);
            Composition comp = sampleRockIceComposition(a, snow, aEnd, rng);

            PlanetModel p = new PlanetModel(nextOuterName(existingPlanets, out.size() + 1));

            p.orbitAroundStar = new Orbit(a, e, inc, Omega, argPeri, nu);

            p.massEarth = m;
            p.coreMassEarth = m;
            p.envelopeMassEarth = 0.0;
            p.envelopeZ = 0.0;

            p.fracIron = comp.fracIron;
            p.fracRock = comp.fracRock;
            p.fracIce = comp.fracIce;
            // Ensure radius & gravity are initialized for outer bodies (they may be added after the main physics loop)
            p.radiusEarth = PlanetRadii.solidRadiusEarth(p.massEarth, p.fracIron, p.fracRock, p.fracIce);
            p.surfaceG = p.massEarth / Math.max(1e-9, (p.radiusEarth * p.radiusEarth));


            p.type = classifyOuterType(m, comp.fracIce);

            out.add(p);
            allForChecks.add(p);
        }

        out.sort(Comparator.comparingDouble(o -> o.orbitAroundStar.aAU()));

        // ---------- belt record ----------
        String beltRecord = null;
        if (out.isEmpty()) {
            beltRecord = makeBeltRecord(star, disk, rng, aStart, aEnd);
        }

        return new Result(out, beltRecord);
    }

    // ---------------------------- stability checks ----------------------------

    private static boolean isOrbitAcceptable(double aNew, double eNew, List<PlanetModel> existing) {
        if (existing == null || existing.isEmpty()) return true;

        final double ratioMin = 1.25;
        final double logMin = 0.22;

        double qNew = aNew * (1.0 - eNew);
        double QNew = aNew * (1.0 + eNew);

        for (PlanetModel p : existing) {
            if (p == null || p.orbitAroundStar == null) continue;

            Orbit o = p.orbitAroundStar;
            double a = Math.max(1e-9, o.aAU());
            double e = clamp(Math.abs(o.e()), 0.0, 0.95);

            // ratio + log spacing (works even for tiny masses)
            double r = (aNew > a) ? (aNew / a) : (a / aNew);
            if (r < ratioMin) return false;

            double log = Math.abs(Math.log(aNew / a));
            if (log < logMin) return false;

            // non-crossing-ish with peri/apo gap + margin
            double q = a * (1.0 - e);
            double Q = a * (1.0 + e);

            double aMid = 0.5 * (aNew + a);
            double margin = 0.03 * aMid;

            if (aNew >= a) {
                if ((qNew - Q) < margin) return false;
            } else {
                if ((q - QNew) < margin) return false;
            }
        }
        return true;
    }

    // ---------------------------- sampling ----------------------------

    private static double sampleOuterMassEarth(DeterministicRng rng) {
        // 75%: 0.30–0.90, 25%: 0.90–2.00 with log-skew
        if (rng.chance(0.75)) return logUniform(rng, 0.30, 0.90);
        return logUniform(rng, 0.90, 2.00);
    }

    private static String classifyOuterType(double mEarth, double fracIce) {
        // UI naming only
        if (mEarth < 0.08) return "Dwarf";
        if (mEarth < 0.30) return "Planetoid";
        // keep "Rocky" label consistent with the rest of the project;
        // icy ones will have high fracIce and show as "Rocky" unless you re-label elsewhere.
        if (fracIce >= 0.45 && mEarth < 0.9) return "Dwarf"; // optional: more dwarf-ish when very icy + small
        return "Rocky";
    }

    private static Composition sampleRockIceComposition(double a, double snow, double aEnd, DeterministicRng rng) {
        double lo = Math.max(snow * 1.2, 0.05);
        double hi = Math.max(aEnd, lo * 1.5);

        double t = clamp01(Math.log(a / lo) / Math.log(hi / lo));
        double noise = rng.range(-0.12, +0.12);
        double iceProb = clamp01(0.30 + 0.60 * t + noise);

        boolean icy = rng.chance(iceProb);

        double fracIce, fracRock, fracIron;

        if (icy) {
            fracIce = rng.range(0.55, 0.85);
            double rem = 1.0 - fracIce;
            fracIron = rem * rng.range(0.15, 0.35);
            fracRock = Math.max(0.0, 1.0 - fracIce - fracIron);
        } else {
            fracIce = rng.range(0.05, 0.25);
            double rem = 1.0 - fracIce;
            fracIron = rem * rng.range(0.18, 0.40);
            fracRock = Math.max(0.0, 1.0 - fracIce - fracIron);
        }

        double s = fracIce + fracRock + fracIron;
        if (s <= 0) return new Composition(0.30, 0.65, 0.05);
        return new Composition(fracIron / s, fracRock / s, fracIce / s);
    }

    private static final class Composition {
        final double fracIron, fracRock, fracIce;
        Composition(double fracIron, double fracRock, double fracIce) {
            this.fracIron = fracIron;
            this.fracRock = fracRock;
            this.fracIce = fracIce;
        }
    }

    // ---------------------------- belt record ----------------------------

    private static String makeBeltRecord(StarModel star, DiskModel disk, DeterministicRng rng, double aStart, double aEnd) {
        // Simple placeholder record until belts/zones are implemented.
        double lo = Math.max(aStart * 1.05, 1e-3);
        double hi = Math.max(aEnd, lo * 1.2);

        double a = sampleLogUniform(lo, hi, rng);
        double width = a * rng.range(0.10, 0.30);

        double solids = safe(disk.mSolidsEarth, 0.0);
        double beltMass = solids * rng.range(0.0005, 0.004);

        return String.format(
                "OUTER_BELT a=%.3fAU width=%.3fAU mass=%.4fM⊕ refOuter=%.2fAU",
                a, width, beltMass, safe(disk.rSolidsOuterAU, 0.0)
        );
    }

    // ---------------------------- misc ----------------------------

    private static int[] baseCountRange(char c) {
        return switch (c) {
            case 'M' -> new int[]{0, 2};
            case 'K' -> new int[]{0, 3};
            case 'G' -> new int[]{1, 3};
            case 'F' -> new int[]{2, 4};
            case 'A' -> new int[]{3, 6};
            case 'B', 'O' -> new int[]{3, 8};
            default -> new int[]{0, 3};
        };
    }

    private static char spectralClass(StarRecord rec) {
        if (rec == null) return 'G';
        String s = rec.spect();
        if (s == null) return 'G';
        s = s.trim().toUpperCase();
        if (s.isEmpty()) return 'G';
        char c = s.charAt(0);
        return (c >= 'A' && c <= 'Z') ? c : 'G';
    }

    private static double maxA(List<PlanetModel> planets) {
        double m = 0.0;
        for (PlanetModel p : planets) {
            if (p == null || p.orbitAroundStar == null) continue;
            m = Math.max(m, p.orbitAroundStar.aAU());
        }
        return m;
    }

    private static double maxAGasGiant(List<PlanetModel> planets) {
        double m = 0.0;
        for (PlanetModel p : planets) {
            if (p == null || p.orbitAroundStar == null) continue;
            boolean giant = "Gas Giant".equalsIgnoreCase(p.type) || "GAS GIANT".equalsIgnoreCase(p.type)
                    || "GAS_GIANT".equalsIgnoreCase(p.type);
            if (!giant && p.massEarth > 30.0) giant = true;
            if (giant) m = Math.max(m, p.orbitAroundStar.aAU());
        }
        return m;
    }

    private static double sampleLogUniform(double lo, double hi, DeterministicRng rng) {
        lo = Math.max(lo, 1e-6);
        hi = Math.max(hi, lo * 1.0001);
        double u = rng.nextDouble();
        return Math.exp(Math.log(lo) + (Math.log(hi) - Math.log(lo)) * u);
    }

    private static double logUniform(DeterministicRng rng, double lo, double hi) {
        lo = Math.max(lo, 1e-12);
        hi = Math.max(hi, lo * 1.0001);
        double u = rng.nextDouble();
        return Math.exp(Math.log(lo) + (Math.log(hi) - Math.log(lo)) * u);
    }

    private static double safe(double v, double fallback) {
        if (!Double.isFinite(v)) return fallback;
        if (v <= 0.0) return fallback;
        return v;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String nextOuterName(List<PlanetModel> existing, int idx) {
        // Чтобы не пересечься с P1/P2/... и т.п.
        // Внешнее кольцо: O1, O2, ...
        return "O" + idx;
    }

}
