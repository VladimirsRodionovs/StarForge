package org.example.starforge.core.model;

import org.example.starforge.core.physics.HillSphere;
import org.example.starforge.core.random.DeterministicRng;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Iterative solids accretion with feeding zones and Hill merging.
 * This is a "heavy but still game-friendly" heuristic model.
 */
public final class AccretionSimulator {

    private AccretionSimulator() {}

    public static final int STEPS = 150;

    // Base feeding zone in Hill radii.
    // Slightly increased to bias toward larger terrestrial masses (game tuning):
    // embryos/planets vacuum a wider annulus and end up ~+30–60% more massive on average.
    public static final double FEEDING_K = 12.0;

    // Minimum feeding zone fraction of semi-major axis (early growth cannot be infinitely local)
    public static final double FEEDING_MIN_FRAC = 0.04; // 4% of a

    // Also a hard minimum in AU to avoid ultra-inner micro zones
    public static final double FEEDING_MIN_AU = 0.004;

    public static final double STEP_EFFICIENCY = 0.55;

    public static final double MERGE_K = 6.0;

    // -------------------- DEBUG STATS --------------------

    public record AccretionStats(
            double solidsBeforeEarth,
            double solidsAfterEarth,
            double embryoMassAfterEarth
    ) {}

    public record AccretionResult(
            List<EmbryoModel> embryos,
            AccretionStats stats,
            SolidsDiskState solids
    ) {}// -------------------- PUBLIC API --------------------

    public static List<EmbryoModel> evolveSolids(
            StarModel star,
            DiskModel disk,
            List<EmbryoModel> embryos,
            DeterministicRng rng,
            double tGasMyr
    ) {
        SolidsDiskState solids = new SolidsDiskState(disk, star, rng, 900);

        // PAY seed mass from the reservoir so the mass budget closes
        paySeedMass(solids, embryos);

        return evolveSolidsFromState(star, disk, embryos, rng, tGasMyr, solids);
    }

    public static AccretionResult evolveSolidsWithStats(
            StarModel star,
            DiskModel disk,
            List<EmbryoModel> embryos,
            DeterministicRng rng,
            double tGasMyr
    ) {
        SolidsDiskState solids = new SolidsDiskState(disk, star, rng, 900);
        double solidsBefore = solids.totalMassEarth();

        // PAY seed mass from the reservoir so the mass budget closes
        paySeedMass(solids, embryos);

        List<EmbryoModel> out = evolveSolidsFromState(star, disk, embryos, rng, tGasMyr, solids);

        double solidsAfter = solids.totalMassEarth();

        double embryoMass = 0.0;
        for (EmbryoModel e : out) embryoMass += Math.max(0.0, e.massEarth);

        return new AccretionResult(out, new AccretionStats(solidsBefore, solidsAfter, embryoMass), solids);
    }

    // -------------------- CORE LOGIC --------------------

    public static void paySeedMass(SolidsDiskState solids, List<EmbryoModel> embryos) {
        if (embryos == null || embryos.isEmpty()) return;

        double seed = 0.0;
        for (EmbryoModel e : embryos) {
            seed += Math.max(0.0, e.massEarth);
        }
        if (seed <= 0) return;

        // Global proportional removal keeps things simple and stable.
        // Later we can replace this with local removal around each embryo.
        solids.removeMassGlobal(seed);
    }

    public static List<EmbryoModel> evolveSolidsFromState(
            StarModel star,
            DiskModel disk,
            List<EmbryoModel> embryos,
            DeterministicRng rng,
            double tGasMyr,
            SolidsDiskState solids
    ) {
        List<EmbryoModel> bodies = new ArrayList<>(embryos);

        for (int step = 0; step < STEPS; step++) {
            double stepTime = (tGasMyr * step) / (double) (STEPS - 1);

            // Growth
            for (EmbryoModel e : bodies) {
                if (e.birthTime > stepTime) continue;

                double a = e.aAU;

                double hill = HillSphere.hillRadiusAU(a, Math.max(1e-9, e.massEarth), star.massSolar());

                // Feeding half-width
                double daHill = FEEDING_K * hill;
                double daMin = Math.max(FEEDING_MIN_AU, FEEDING_MIN_FRAC * a);
                double da = Math.max(daHill, daMin);

                double r1 = Math.max(disk.rInnerAU, a - da);
                double r2 = Math.min(disk.rSolidsOuterAU, a + da);
                if (r2 <= r1) continue;

                double available = solids.massIn(r1, r2);
                if (available <= 0) continue;

                // Base intake per step.
                double targetTake = (available * STEP_EFFICIENCY) / (double) STEPS;

                // --- Snow-line growth boost (game-friendly pebble/ice enhancement heuristic) ---
                // Cores just beyond the snow line tend to grow faster (more condensables / pebbles),
                // which also helps produce true giant cores in some systems.
                double snow = Math.max(disk.rInnerAU + 0.05, star.snowLineAU());
                double w = Math.max(0.25, 0.50 * snow); // width of enhanced region
                double x = (a - snow) / w;
                double boost = 1.0 + 2.00 * Math.exp(-x * x); // 1..~2.25 near snow line
                // Keep inner system mostly unchanged
                if (a < snow * 0.80) boost = 1.0;

                targetTake *= boost;


                // Small stochasticity
                double take = targetTake * rng.logNormal(1.0, 0.20);

                double got = solids.removeMass(r1, r2, take);
                if (got > 0) {
                    // Accreted solids composition depends on location vs snow line.
                    double[] comp = solidsCompositionAt(star, a);
                    CompositionUtil.mixAddSolids(e, got, comp[0], comp[1], comp[2]);
                    e.massEarth += got;
                }
            }

            mergeOverlapsInPlace(bodies, star);
        }

        bodies.sort(Comparator.comparingDouble(o -> o.aAU));
        return bodies;
    }

    private static void mergeOverlapsInPlace(List<EmbryoModel> bodies, StarModel star) {
        boolean merged;
        do {
            merged = false;
            bodies.sort(Comparator.comparingDouble(o -> o.aAU));

            outer:
            for (int i = 0; i < bodies.size(); i++) {
                EmbryoModel a = bodies.get(i);

                double hillA = HillSphere.hillRadiusAU(a.aAU, Math.max(1e-9, a.massEarth), star.massSolar());
                double zoneA = MERGE_K * hillA;

                // Minimum merge reach prevents "never merging" for tiny masses
                zoneA = Math.max(zoneA, 0.012 * a.aAU);

                for (int j = i + 1; j < bodies.size(); j++) {
                    EmbryoModel b = bodies.get(j);

                    double hillB = HillSphere.hillRadiusAU(b.aAU, Math.max(1e-9, b.massEarth), star.massSolar());
                    double zoneB = MERGE_K * hillB;
                    zoneB = Math.max(zoneB, 0.012 * b.aAU);

                    double dist = Math.abs(a.aAU - b.aAU);
                    if (dist <= (zoneA + zoneB)) {
                        EmbryoModel big = (a.massEarth >= b.massEarth) ? a : b;
                        EmbryoModel small = (big == a) ? b : a;

                        double mTot = big.massEarth + small.massEarth;
                        if (mTot <= 0) {
                            bodies.remove(small);
                            merged = true;
                            break outer;
                        }

                        double aNew = (big.aAU * big.massEarth + small.aAU * small.massEarth) / mTot;

                        big.massEarth = mTot;
                        big.aAU = aNew;

                        // Mix compositions (mass-weighted)
                        CompositionUtil.mixMergeSolids(big, small);

                        bodies.remove(small);
                        merged = true;
                        break outer;
                    }
                }
            }
        } while (merged);
    }

    /** Returns {fracIron, fracRock, fracIce} for newly accreted solids at aAU. */
    private static double[] solidsCompositionAt(StarModel star, double aAU) {
        double snow = Math.max(1e-4, star.snowLineAU());
        double in = 0.85 * snow;
        double out = 1.15 * snow;
        double ice;
        if (aAU <= in) ice = 0.0;
        else if (aAU >= out) ice = 1.0;
        else ice = (aAU - in) / (out - in);

        // Within refractory portion, split iron vs rock.
        double feShare = 0.30 + (0.22 - 0.30) * ice;
        double refr = Math.max(0.0, 1.0 - ice);
        double fe = refr * feShare;
        double rock = refr * (1.0 - feShare);
        return new double[]{fe, rock, ice};
    }
}
